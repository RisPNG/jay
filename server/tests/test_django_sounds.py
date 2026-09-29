from datetime import timedelta
from uuid import uuid4

import pytest
from django.db import transaction
from django.test import override_settings
from django.utils import timezone

from jay_server.social.models import DeliveryWork, GroupMembership, Identity, ProfileSoundGrant, SharedAlarm, SharedSound
from jay_server.social.api.representations import SoundRepresentation
from jay_server.social.synchronization import publish_changes
from jay_server.social.worker import process_sound_verification


pytestmark = pytest.mark.django_db(transaction=True)


def test_sound_entitlement_and_unrelated_alarm_edit(client, group, alarm_payload):
    sound_id = uuid4()
    alarm_payload["sound"] = {"mode": "shared", "sound_id": str(sound_id), "title": "Birds"}
    denied = client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert denied.status_code == 403
    ProfileSoundGrant.objects.create(identity=Identity.objects.get(pk="1" * 64))
    created = client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    sound = SharedSound.objects.get(pk=sound_id)
    assert sound.status == "pending"
    assert sound.sha256 is None
    ProfileSoundGrant.objects.all().delete()
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
        completed = client.post(f"/v1/sounds/{payload['id']}/complete", headers={"Idempotency-Key": key, "X-Jay-Installation": "paid-installation"})
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


def test_member_without_upload_access_receives_and_selects_ready_sound(client, group, member, alarm_payload, monkeypatch):
    class Storage:
        def generate_presigned_url(self, *args, **kwargs):
            return "https://storage.example/sound.flac"

    monkeypatch.setattr("jay_server.social.api.sounds.object_storage_client", Storage)
    sound = SharedSound.objects.create(
        group=group, uploaded_by=Identity.objects.get(pk="1" * 64), title="Birds",
        status="ready", sha256="a" * 64, byte_length=100, duration_ms=1000,
        object_key=f"sounds/{uuid4()}/verified.flac", staging_key=f"sounds/{uuid4()}/upload.flac",
    )
    with transaction.atomic():
        publish_changes(group.scope_id, [("sound", str(sound.pk), "upsert", SoundRepresentation(sound).data, None)], group=group)
    assert not member.get("/v1/identity/capabilities").data["shared_sound_upload"]
    assert any(item["kind"] == "sound" and item["key"] == str(sound.pk) and item["data"]["status"] == "ready"
               for item in member.get(f"/v1/groups/{group.pk}/sync").data["items"])
    sound_download = member.get(f"/v1/sounds/{sound.pk}/download")
    assert sound_download.status_code == 200
    assert sound_download.data == {
        "url": "https://storage.example/sound.flac", "sha256": "a" * 64, "byte_length": 100,
    }
    alarm_payload["membership_id"] = str(GroupMembership.objects.get(group=group, identity_id="2" * 64).pk)
    alarm_payload["sound"] = {"mode": "shared", "sound_id": str(sound.pk)}
    created = member.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    assert SharedAlarm.objects.get(pk=created.data["id"]).sound_id == sound.pk


@override_settings(SHARED_SOUND_ACCESS="everyone", B2_BUCKET_NAME="sounds")
def test_worker_verifies_sound_with_nullable_uploader_relation(client, group, monkeypatch):
    class Storage:
        def copy_object(self, **kwargs):
            assert kwargs["Bucket"] == "sounds"

    monkeypatch.setattr("jay_server.social.worker.object_storage_client", Storage)
    monkeypatch.setattr("jay_server.social.worker.validate_sound_object", lambda sound, stopping: None)
    sound = SharedSound.objects.create(
        group=group, uploaded_by=Identity.objects.get(pk="1" * 64), title="Birds",
        status="verifying", sha256="a" * 64, byte_length=100, duration_ms=1000,
        object_key=f"sounds/{uuid4()}/verified.flac", staging_key=f"sounds/{uuid4()}/upload.flac",
    )
    owner = uuid4()
    work = DeliveryWork.objects.create(
        kind="verify", deduplication_key=f"verify:{sound.pk}", payload={"sound_id": str(sound.pk)},
        lease_owner=owner, lease_until=timezone.now() + timedelta(seconds=60),
    )

    assert process_sound_verification(work, owner)
    sound.refresh_from_db()
    assert sound.status == "ready"
    assert sound.failure_code is None
    assert sound.ready_at is not None


def test_entitlement_replay_does_not_call_provider_again(client, monkeypatch):
    from jay_server.social import providers

    calls = []
    def verify(token, identity, installation):
        calls.append((token, identity))
        return True
    monkeypatch.setattr(providers, "verify_play_entitlement", verify)
    headers = {"Idempotency-Key": str(uuid4()), "X-Jay-Installation": "paid-installation"}
    first = client.post("/v1/identity/play-entitlement", {"integrity_token": "verified"}, format="json", headers=headers)
    second = client.post("/v1/identity/play-entitlement", {"integrity_token": "verified"}, format="json", headers=headers)
    assert first.status_code == second.status_code == 200
    assert first.data == second.data
    assert calls == [("verified", "1" * 64)]
    mismatch = client.post("/v1/identity/play-entitlement", {"integrity_token": "changed"}, format="json", headers=headers)
    assert mismatch.status_code == 409
    assert len(calls) == 1
