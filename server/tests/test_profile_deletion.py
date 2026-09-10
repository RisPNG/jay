from datetime import timedelta
from io import StringIO
from uuid import uuid4

import pytest
from django.core.management import call_command
from django.utils import timezone

from jay_server.social.models import AlarmActivity, AlarmDelivery, AlarmOccurrence, DeliveryWork, GroupActivity, GroupInvitation, GroupMembership, Identity, OperationReceipt, PushSubscription, SharedAlarm, SharedSound, SharedSoundEntitlement, SharedTimer, SyncVersion
from jay_server.social.worker import maintain_state, process_group_cleanup, process_identity_retirement


pytestmark = pytest.mark.django_db(transaction=True)


def test_profile_deletion_removes_personal_history_and_preserves_shared_content(client, group, member, alarm_payload):
    identity = Identity.objects.get(pk="1" * 64)
    GroupMembership.objects.filter(group=group, identity_id="2" * 64).update(role="leader")
    call_command("grant_sound_access", identity.pk, stdout=StringIO())
    sound = SharedSound.objects.create(group=group, uploaded_by=identity, title="Birds", status="ready", object_key="sounds/kept/verified.flac", staging_key="sounds/kept/upload.flac")
    alarm_payload["sound"] = {"mode": "shared", "sound_id": str(sound.pk)}
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    AlarmDelivery.objects.create(alarm=alarm, identity=identity, revision=1)
    AlarmActivity.objects.create(alarm=alarm, group=group, identity=identity, alarm_revision=1, kind="dismissed", occurred_at=timezone.now(), request_fingerprint="a" * 64)
    PushSubscription.objects.create(identity=identity, token="old-device")
    GroupActivity.objects.create(group=group, revision=group.scope.head_revision, ordinal=99, entity_type="alarm", entity_id=str(alarm.pk), action="created", actor=identity, actor_label=identity.name, group_label=group.name)
    GroupActivity.objects.create(group=group, revision=group.scope.head_revision, ordinal=100, entity_type="alarm", entity_id=str(alarm.pk), action="updated", actor_id="2" * 64, actor_label="Bob", group_label=group.name)
    assert client.delete("/v1/identity", headers={"Idempotency-Key": str(uuid4())}).status_code == 204
    assert not PushSubscription.objects.filter(identity=identity).exists()
    assert not SharedSoundEntitlement.objects.filter(identity=identity).exists()
    assert client.get("/v1/identity/capabilities").status_code == 401
    retirement = DeliveryWork.objects.get(kind="retire", payload__identity_id=identity.pk)
    assert not process_identity_retirement(retirement)
    assert process_identity_retirement(retirement)
    maintain_state()
    identity.refresh_from_db()
    assert identity.purged_at is None
    assert identity.name == "Alice"
    Identity.objects.filter(pk=identity.pk).update(retired_at=timezone.now() - timedelta(days=31))
    maintain_state()
    identity.refresh_from_db()
    assert identity.purged_at is not None
    assert identity.name == "Removed member"
    assert bytes(identity.token_hash) == b""
    for model in [AlarmActivity, AlarmDelivery, AlarmOccurrence, GroupMembership, OperationReceipt]:
        assert not model.objects.filter(identity=identity).exists()
    assert not GroupInvitation.objects.filter(created_by=identity).exists()
    assert not GroupActivity.objects.filter(actor=identity).exists()
    assert not GroupActivity.objects.filter(subject=identity).exists()
    assert not GroupActivity.objects.filter(recipient=identity).exists()
    assert GroupActivity.objects.filter(actor_id="2" * 64, actor_label="Bob").exists()
    assert not SyncVersion.objects.filter(scope=identity.scope).exists()
    assert not SyncVersion.objects.filter(representation__identity_id=identity.pk).exists()
    assert not SyncVersion.objects.filter(representation__actor_id=identity.pk).exists()
    assert not SyncVersion.objects.filter(representation__subject_id=identity.pk).exists()
    group.refresh_from_db()
    alarm.refresh_from_db()
    sound.refresh_from_db()
    assert group.deleted_at is None and group.created_by is None
    assert alarm.deleted_at is None and alarm.created_by is None and alarm.updated_by is None
    assert alarm.sound_id == sound.pk and sound.uploaded_by is None
    assert member.get(f"/v1/groups/{group.pk}/sync").status_code == 200
    recreated = client.post("/v1/identities/register", {"id": identity.pk, "name": "Alice", "token": "secret" * 10, "time_zone": "UTC"}, format="json")
    assert recreated.status_code == 409


def test_operator_deletion_uses_retirement_and_is_repeatable(client, group):
    call_command("grant_sound_access", "1" * 64, stdout=StringIO())
    for _ in range(2):
        call_command("delete_profile", "1" * 64, stdout=StringIO())
    assert Identity.objects.get(pk="1" * 64).retired_at is not None
    assert not SharedSoundEntitlement.objects.exists()
    assert DeliveryWork.objects.filter(kind="retire").count() == 1
    assert client.get("/v1/sync").status_code == 401


def test_only_leader_deletion_removes_group_items_and_audio_even_with_members(client, group, member, alarm_payload, timer_payload):
    call_command("grant_sound_access", "1" * 64, stdout=StringIO())
    alarm_sound = SharedSound.objects.create(group=group, title="Birds", status="ready", object_key="sounds/alarm/verified.flac", staging_key="sounds/alarm/upload.flac")
    timer_sound = SharedSound.objects.create(group=group, title="Bell", status="ready", object_key="sounds/timer/verified.flac", staging_key="sounds/timer/upload.flac")
    alarm_payload["sound"] = {"mode": "shared", "sound_id": str(alarm_sound.pk)}
    timer_payload["sound"] = {"mode": "shared", "sound_id": str(timer_sound.pk)}
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    assert client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    call_command("delete_profile", "1" * 64, stdout=StringIO())
    retirement = DeliveryWork.objects.get(kind="retire")
    assert not process_identity_retirement(retirement)
    group.refresh_from_db()
    assert group.deleted_at is not None
    assert member.get(f"/v1/groups/{group.pk}/sync").status_code == 404
    group_cleanup = DeliveryWork.objects.get(kind="delete_group")
    assert not process_group_cleanup(group_cleanup)
    assert process_group_cleanup(group_cleanup)
    assert not GroupMembership.objects.filter(group=group, removed_at=None).exists()
    assert SharedAlarm.objects.get(pk=alarm_payload["id"]).deleted_at is not None
    assert SharedTimer.objects.get(pk=timer_payload["id"]).deleted_at is not None
    assert not SharedSound.objects.filter(group=group).exists()
    assert set(DeliveryWork.objects.filter(kind="delete_sound").values_list("payload__object_key", flat=True)) == {"sounds/alarm/verified.flac", "sounds/alarm/upload.flac", "sounds/timer/verified.flac", "sounds/timer/upload.flac"}


def test_unused_sound_queues_storage_cleanup(client, group):
    sound = SharedSound.objects.create(group=group, title="Unused", status="ready", object_key="sounds/unused/verified.flac", staging_key="sounds/unused/upload.flac", created_at=timezone.now() - timedelta(days=2))
    maintain_state()
    assert not SharedSound.objects.filter(pk=sound.pk).exists()
    assert set(DeliveryWork.objects.filter(kind="delete_sound").values_list("payload__object_key", flat=True)) == {"sounds/unused/verified.flac", "sounds/unused/upload.flac"}


def test_existing_anonymized_profile_is_fully_cleaned(client):
    identity = Identity.objects.get(pk="1" * 64)
    Identity.objects.filter(pk=identity.pk).update(retired_at=timezone.now() - timedelta(days=31), name="Removed member", token_hash=b"")
    SharedSoundEntitlement.objects.create(identity=identity, expires_at=timezone.now() - timedelta(days=30))
    maintain_state()
    identity.refresh_from_db()
    assert identity.purged_at is not None
    assert not SharedSoundEntitlement.objects.filter(identity=identity).exists()
