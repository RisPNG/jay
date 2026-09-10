from datetime import timedelta
from uuid import uuid4

import pytest
from django.test import override_settings
from django.utils import timezone

from jay_server.social.models import DeliveryWork, GroupMembership, Identity, SharedSoundEntitlement, SharedAlarm, SharedSound


pytestmark = pytest.mark.django_db(transaction=True)


def test_sound_entitlement_and_unrelated_alarm_edit(client, group, alarm_payload):
    sound_id = uuid4()
    alarm_payload["sound"] = {"mode": "shared", "sound_id": str(sound_id), "title": "Birds"}
    denied = client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert denied.status_code == 403
    SharedSoundEntitlement.objects.create(identity=Identity.objects.get(pk="1" * 64), expires_at=timezone.now() + timedelta(days=1))
    created = client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    sound = SharedSound.objects.get(pk=sound_id)
    assert sound.status == "pending"
    assert sound.sha256 is None
    SharedSoundEntitlement.objects.all().delete()
    alarm_id = alarm_payload.pop("id")
    alarm_payload.pop("group_id")
    changed = client.put(f"/v1/alarms/{alarm_id}", {**alarm_payload, "label": "Later", "saved_at": timezone.now().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert changed.status_code == 200, changed.data
    assert SharedAlarm.objects.get(pk=alarm_id).sound_id == sound_id


@override_settings(SHARED_SOUND_ACCESS="everyone")
def test_upload_metadata_is_immutable_and_completion_is_queued(client, group):
    payload = {"id": str(uuid4()), "membership_id": str(GroupMembership.objects.get(group=group, identity_id="1" * 64).pk), "title": "Birds", "sha256": "a" * 64, "byte_length": 100, "duration_ms": 1000}
    created = client.post(f"/v1/groups/{group.pk}/sounds/uploads", payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    assert "url" not in created.data
    changed = client.post(f"/v1/groups/{group.pk}/sounds/uploads", {**payload, "sha256": "b" * 64}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert changed.status_code == 409
    key = str(uuid4())
    for _ in range(2):
        completed = client.post(f"/v1/sounds/{payload['id']}/complete", headers={"Idempotency-Key": key})
        assert completed.status_code == 202, completed.data
    assert DeliveryWork.objects.filter(kind="verify").count() == 1
    assert client.get(f"/v1/sounds/{payload['id']}/download").status_code == 404


@override_settings(SHARED_SOUND_ACCESS="everyone")
def test_open_sound_policy_does_not_bypass_group_permissions(client, group, member):
    group.alarm_permission = "leaders"
    group.save(update_fields=["alarm_permission"])
    payload = {"id": str(uuid4()), "membership_id": str(GroupMembership.objects.get(group=group, identity_id="2" * 64).pk), "title": "Birds", "sha256": "a" * 64, "byte_length": 100, "duration_ms": 1000}
    assert member.get("/v1/identity/capabilities").data["shared_sound_upload"]
    assert member.post(f"/v1/groups/{group.pk}/sounds/uploads", payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 403


def test_entitlement_replay_does_not_call_provider_again(client, monkeypatch):
    from jay_server.social import providers

    calls = []
    def verify(token, identity):
        calls.append((token, identity))
        return True
    monkeypatch.setattr(providers, "verify_play_entitlement", verify)
    headers = {"Idempotency-Key": str(uuid4())}
    first = client.post("/v1/identity/play-entitlement", {"integrity_token": "verified"}, format="json", headers=headers)
    second = client.post("/v1/identity/play-entitlement", {"integrity_token": "verified"}, format="json", headers=headers)
    assert first.status_code == second.status_code == 200
    assert first.data == second.data
    assert calls == [("verified", "1" * 64)]
    mismatch = client.post("/v1/identity/play-entitlement", {"integrity_token": "changed"}, format="json", headers=headers)
    assert mismatch.status_code == 409
    assert len(calls) == 1
