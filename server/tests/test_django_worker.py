import asyncio
import threading
from datetime import timedelta
from io import StringIO
from types import SimpleNamespace
from uuid import uuid4

import pytest
from channels.db import database_sync_to_async
from django.core.management import call_command
from django.db import IntegrityError, transaction
from django.utils import timezone

from jay_server.social.models import DeletedResource, DeliveryWork, GroupMembership, Identity, PushSubscription, SharedSound, SharedTimer, WorkerHeartbeat
from jay_server.social.worker import Worker, claim_work, finish_work, maintain_state, push_recipients


pytestmark = pytest.mark.django_db(transaction=True)


def test_expired_lease_can_be_reclaimed_and_old_worker_cannot_complete_it():
    work = DeliveryWork.objects.create(kind="push", deduplication_key="lease", payload={"revision": 1})
    old, new = uuid4(), uuid4()
    claimed = claim_work("push", old)
    assert claim_work("push", new) is None
    DeliveryWork.objects.filter(pk=work.pk).update(lease_until=timezone.now() - timedelta(seconds=1))
    reclaimed = claim_work("push", new)
    finish_work(claimed, old)
    assert DeliveryWork.objects.get(pk=work.pk).lease_owner == new
    finish_work(reclaimed, new)
    assert not DeliveryWork.objects.filter(pk=work.pk).exists()


def test_failed_old_push_does_not_discard_a_new_revision():
    owner = uuid4()
    work = DeliveryWork.objects.create(kind="push", deduplication_key="changed", payload={"revision": 1})
    DeliveryWork.objects.filter(pk=work.pk).update(created_at=timezone.now() - timedelta(days=2))
    claimed = claim_work("push", owner)
    DeliveryWork.objects.filter(pk=work.pk).update(payload={"revision": 2})
    finish_work(claimed, owner, error=OSError())
    work.refresh_from_db()
    assert work.failed_at is None
    assert work.lease_owner is None
    assert work.payload == {"revision": 2}


@pytest.fixture
def ring_wake(group, member):
    return DeliveryWork.objects.create(kind="push", deduplication_key=f"ring:{uuid4()}", payload={
        "kind": "ring", "scope_id": str(group.scope_id), "group_id": str(group.pk),
        "session_id": str(uuid4()), "actor_id": "2" * 64,
        "expires_at": (timezone.now() + timedelta(seconds=5)).isoformat(),
    })


def test_expired_ring_wake_is_discarded_before_claim(ring_wake):
    DeliveryWork.objects.exclude(pk=ring_wake.pk).delete()
    ring_wake.payload["expires_at"] = (timezone.now() - timedelta(seconds=1)).isoformat()
    ring_wake.save(update_fields=["payload"])
    assert claim_work("push", uuid4()) is None
    assert not DeliveryWork.objects.filter(pk=ring_wake.pk).exists()


def test_ring_wake_precedes_sync_backlog_and_expired_wakes_do_not_delay_claiming(ring_wake):
    expired = DeliveryWork.objects.create(kind="push", deduplication_key=f"ring:{uuid4()}", payload={
        **ring_wake.payload, "expires_at": (timezone.now() - timedelta(seconds=1)).isoformat(),
    })
    sync = DeliveryWork.objects.filter(kind="push", payload__kind__isnull=True).first()
    assert sync is not None
    DeliveryWork.objects.filter(kind="push", payload__kind__isnull=True).update(available_at=timezone.now() - timedelta(minutes=1))
    owner = uuid4()
    claimed = claim_work("push", owner)
    assert claimed.pk == ring_wake.pk
    assert not DeliveryWork.objects.filter(pk=expired.pk).exists()
    finish_work(claimed, owner)
    assert claim_work("push", owner).payload.get("kind") != "ring"


@pytest.mark.parametrize("expired", [False, True])
def test_ring_wake_cannot_retry_beyond_its_deadline(ring_wake, expired):
    DeliveryWork.objects.exclude(pk=ring_wake.pk).delete()
    claimed = claim_work("push", uuid4())
    assert claimed.pk == ring_wake.pk
    claimed.payload["expires_at"] = (timezone.now() + timedelta(seconds=-1 if expired else 0.1)).isoformat()
    DeliveryWork.objects.filter(pk=claimed.pk).update(payload=claimed.payload)
    finish_work(claimed, claimed.lease_owner, error=OSError("Unavailable"))
    assert not DeliveryWork.objects.filter(pk=claimed.pk).exists()


@pytest.mark.parametrize("unavailable", ["released", "expired", "holder_removed", "holder_retired", "permission", "group_deleted"])
def test_ring_wake_revalidates_the_hold_before_selecting_recipients(ring_wake, group, unavailable):
    PushSubscription.objects.create(token="alice", identity_id="1" * 64, group_ring_enabled=True)
    if unavailable == "released":
        ring_wake.delete()
    elif unavailable == "expired":
        ring_wake.payload["expires_at"] = (timezone.now() - timedelta(seconds=1)).isoformat()
        ring_wake.save(update_fields=["payload"])
    elif unavailable == "holder_removed":
        GroupMembership.objects.filter(group=group, identity_id="2" * 64).update(removed_at=timezone.now())
    elif unavailable == "holder_retired":
        Identity.objects.filter(pk="2" * 64).update(retired_at=timezone.now())
    elif unavailable == "permission":
        group.alarm_permission = "leaders"
        group.save(update_fields=["alarm_permission"])
    else:
        group.deleted_at = timezone.now()
        group.save(update_fields=["deleted_at"])
    assert push_recipients(ring_wake) == []


@pytest.mark.parametrize("unavailable", ["removed", "retired", "token_reassigned"])
def test_ring_wake_filters_current_members_and_token_owners(ring_wake, group, unavailable, client):
    PushSubscription.objects.create(token="alice", identity_id="1" * 64, group_ring_enabled=True)
    PushSubscription.objects.create(token="bob", identity_id="2" * 64, group_ring_enabled=True)
    if unavailable == "removed":
        GroupMembership.objects.filter(group=group, identity_id="1" * 64).update(removed_at=timezone.now())
    elif unavailable == "retired":
        Identity.objects.filter(pk="1" * 64).update(retired_at=timezone.now())
    else:
        from rest_framework.test import APIClient
        outsider = APIClient()
        assert outsider.post("/v1/identities/register", {"id": "3" * 64, "name": "Charlie", "token": "outside" * 10, "time_zone": "UTC"}, format="json").status_code == 201
        PushSubscription.objects.filter(token="alice").update(identity_id="3" * 64)
    assert push_recipients(ring_wake) == [("bob", "2" * 64)]


def test_mixed_app_versions_on_one_profile_only_receive_supported_pushes(ring_wake):
    PushSubscription.objects.create(token="legacy", identity_id="1" * 64)
    PushSubscription.objects.create(token="current", identity_id="1" * 64, group_ring_enabled=True)
    assert push_recipients(ring_wake) == [("current", "1" * 64)]
    ring_wake.payload = {"scope_id": ring_wake.payload["scope_id"], "group_id": ring_wake.payload["group_id"], "revision": 1}
    ring_wake.save(update_fields=["payload"])
    assert push_recipients(ring_wake) == [("current", "1" * 64), ("legacy", "1" * 64)]


@pytest.mark.parametrize("ring", [False, True])
def test_push_provider_delivers_scoped_ring_wakes_and_existing_syncs(ring_wake, settings, monkeypatch, ring):
    import firebase_admin
    from firebase_admin import messaging

    settings.FIREBASE_CREDENTIALS_JSON = "configured"
    settings.PUBLIC_URL = "https://jay.example"
    DeliveryWork.objects.exclude(pk=ring_wake.pk).delete()
    if not ring:
        ring_wake.payload = {"scope_id": ring_wake.payload["scope_id"], "group_id": ring_wake.payload["group_id"], "revision": 1}
        ring_wake.save(update_fields=["payload"])
    PushSubscription.objects.create(token="alice", identity_id="1" * 64, group_ring_enabled=ring)
    PushSubscription.objects.create(token="bob", identity_id="2" * 64, group_ring_enabled=ring)
    delivered = []
    monkeypatch.setattr(firebase_admin, "get_app", lambda: object())

    async def scenario():
        worker = Worker()

        async def send(messages):
            delivered.extend(messages)
            worker.stop.set()
            return SimpleNamespace(responses=[SimpleNamespace(exception=None) for _ in messages])

        monkeypatch.setattr(messaging, "send_each_async", send)
        try:
            await asyncio.wait_for(worker.provider("push"), timeout=5)
        finally:
            await database_sync_to_async(WorkerHeartbeat.objects.filter(instance_id=worker.owner).delete, thread_sensitive=False, executor=worker.database_executor)()
            for executor in [worker.database_executor, worker.deadline_executor, worker.maintenance_executor, worker.verification_executor, worker.deletion_executor]:
                executor.shutdown(wait=True, cancel_futures=True)

    asyncio.run(scenario())
    assert not DeliveryWork.objects.filter(pk=ring_wake.pk).exists()
    assert [message.token for message in delivered] == ["alice", "bob"]
    for message, identity_id in zip(delivered, ["1" * 64, "2" * 64]):
        assert message.android.priority == "high"
        if ring:
            assert message.android.ttl == 0
            assert message.data == {
                "kind": "ring", "session_id": ring_wake.payload["session_id"], "group_id": ring_wake.payload["group_id"],
                "identity_id": identity_id, "server_url": settings.PUBLIC_URL, "expires_at": ring_wake.payload["expires_at"],
            }
        else:
            assert message.android.ttl is None
            assert message.data == {"kind": "sync", "scope_id": ring_wake.payload["scope_id"]}


def test_retry_sound_verifications_only_requeues_matching_failed_sounds(group):
    verifying = SharedSound.objects.create(group=group, title="Birds", status="verifying", object_key="sounds/verifying/verified.flac", staging_key="sounds/verifying/upload.flac")
    ready = SharedSound.objects.create(group=group, title="Bell", status="ready", object_key="sounds/ready/verified.flac", staging_key="sounds/ready/upload.flac")
    failed_at = timezone.now() - timedelta(minutes=1)
    eligible = DeliveryWork.objects.create(
        kind="verify", deduplication_key=f"verify:{verifying.pk}", payload={"sound_id": str(verifying.pk)},
        attempts=10, lease_owner=uuid4(), lease_until=timezone.now() - timedelta(minutes=2),
        failed_at=failed_at, error_code="NotSupportedError", created_at=timezone.now() - timedelta(days=2),
    )
    other_error = DeliveryWork.objects.create(
        kind="verify", deduplication_key="verify:other-error", payload={"sound_id": str(verifying.pk)},
        failed_at=failed_at, error_code="sound_mismatch",
    )
    wrong_sound = DeliveryWork.objects.create(
        kind="verify", deduplication_key="verify:wrong-sound", payload={"sound_id": str(verifying.pk)},
        failed_at=failed_at, error_code="NotSupportedError",
    )
    ready_sound = DeliveryWork.objects.create(
        kind="verify", deduplication_key=f"verify:{ready.pk}", payload={"sound_id": str(ready.pk)},
        failed_at=failed_at, error_code="NotSupportedError",
    )

    resumed_at = timezone.now()
    output = StringIO()
    call_command("retry_sound_verifications", stdout=output)
    assert output.getvalue() == "Queued 1 shared-sound verification jobs.\n"
    eligible.refresh_from_db()
    assert eligible.failed_at is None and eligible.error_code is None
    assert eligible.lease_owner is None and eligible.lease_until is None
    assert resumed_at <= eligible.available_at <= timezone.now()
    assert eligible.created_at == eligible.available_at
    assert eligible.attempts == 0
    other_error.refresh_from_db()
    wrong_sound.refresh_from_db()
    ready_sound.refresh_from_db()
    assert other_error.failed_at == wrong_sound.failed_at == ready_sound.failed_at == failed_at
    output = StringIO()
    call_command("retry_sound_verifications", stdout=output)
    assert output.getvalue() == "Queued 0 shared-sound verification jobs.\n"


def test_compacted_timer_cannot_be_recreated(client, group, timer_payload):
    result = client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert result.status_code == 201
    timer = SharedTimer.objects.get(pk=timer_payload["id"])
    SharedTimer.objects.filter(pk=timer.pk).update(deleted_at=timezone.now() - timedelta(days=31))
    maintain_state()
    assert not SharedTimer.objects.filter(pk=timer.pk).exists()
    assert DeletedResource.objects.filter(pk=timer.pk, kind="timer").exists()
    timer._state.adding = True
    with pytest.raises(IntegrityError), transaction.atomic():
        timer.save(force_insert=True)


def test_worker_starts_all_roles_and_stops_its_executors():
    async def lifecycle():
        worker = Worker()
        task = asyncio.create_task(worker.run())
        try:
            async with asyncio.timeout(5):
                while len(worker.heartbeats) < 5:
                    if task.done():
                        task.result()
                    await asyncio.sleep(0.01)
            worker.stop.set()
            await asyncio.wait_for(task, timeout=5)
        finally:
            worker.stop.set()
            if not task.done():
                await task
        return worker.owner
    owner = asyncio.run(lifecycle())
    assert not WorkerHeartbeat.objects.filter(instance_id=owner).exists()
    assert not any(thread.name.startswith(("jay-worker-", "jay-deadlines", "jay-maintenance", "jay-verification", "jay-deletion")) for thread in threading.enumerate())
