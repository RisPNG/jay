import asyncio
import threading
from datetime import timedelta
from uuid import uuid4

import pytest
from django.db import IntegrityError, transaction
from django.utils import timezone

from jay_server.social.models import DeletedResource, DeliveryWork, SharedTimer, WorkerHeartbeat
from jay_server.social.worker import Worker, claim_work, finish_work, maintain_state


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
