from datetime import timedelta
from uuid import uuid4

import pytest
from django.db import transaction
from django.utils import timezone
from rest_framework.test import APIClient

from jay_server.social.alarms import schedule_occurrences
from jay_server.social.models import AlarmActivity, AlarmDelivery, AlarmOccurrence, DeliveryWork, GroupMembership, Identity, SharedAlarm, SyncVersion
from jay_server.social.worker import process_deadlines


pytestmark = pytest.mark.django_db(transaction=True)


def test_alarm_lifecycle_and_delivery_acknowledgement(client, group, alarm_payload):
    created = client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    alarm_id = alarm_payload.pop("id")
    alarm_payload.pop("group_id")
    for _ in range(2):
        assert client.post(f"/v1/alarms/{alarm_id}/deliveries", {"revision": 1}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 204
    assert AlarmDelivery.objects.count() == 1
    updated = client.put(f"/v1/alarms/{alarm_id}", {**alarm_payload, "label": "New", "saved_at": timezone.now().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert updated.status_code == 200, updated.data
    assert updated.data["revision"] == 2
    before = SyncVersion.objects.count()
    stale = client.put(f"/v1/alarms/{alarm_id}", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert stale.status_code == 409
    assert stale.data["code"] == "superseded"
    assert SyncVersion.objects.count() == before
    key = str(uuid4())
    for _ in range(2):
        assert client.delete(f"/v1/alarms/{alarm_id}", {"membership_id": alarm_payload["membership_id"]}, format="json", headers={"Idempotency-Key": key}).status_code == 204
    assert client.put(f"/v1/alarms/{alarm_id}", {**alarm_payload, "saved_at": timezone.now().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 404


def test_activity_replay_requires_current_access(client, group, alarm_payload):
    created = client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201
    payload = {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "dismissed", "occurred_at": timezone.now().isoformat(), "occurrence_key": "current", "reason": None}
    path = f"/v1/alarms/{alarm_payload['id']}/activity"
    assert client.post(path, payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    stranger = APIClient()
    secret = "stranger" * 8
    assert stranger.post("/v1/identities/register", {"id": "3" * 64, "name": "Stranger", "token": secret, "time_zone": "UTC"}, format="json").status_code == 201
    stranger.credentials(HTTP_AUTHORIZATION="Bearer " + secret, HTTP_X_JAY_IDENTITY_ID="3" * 64)
    replay = stranger.post(path, payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert replay.status_code == 404
    assert AlarmActivity.objects.count() == 1


@pytest.mark.parametrize("shared", [False, True])
def test_deadline_and_shared_answers(client, group, member, alarm_payload, shared):
    group.shared_answers = shared
    group.save(update_fields=["shared_answers"])
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    trigger = timezone.now() - timedelta(minutes=12)
    key = str(int(trigger.timestamp() * 1000))
    for identity in Identity.objects.all():
        AlarmOccurrence.objects.create(alarm=alarm, group=group, identity=identity, alarm_revision=1, occurrence_key=key, cycle_date=trigger.date(), trigger_at=trigger, deadline_at=trigger + timedelta(minutes=10))
    DeliveryWork.objects.filter(kind="reschedule").delete()
    assert process_deadlines()
    assert AlarmOccurrence.objects.filter(alarm=alarm, occurrence_key=key, state="ignored").count() == 2
    assert AlarmActivity.objects.filter(kind="ignored").count() == (1 if shared else 2)
    correction = client.post(f"/v1/alarms/{alarm.pk}/activity", {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "dismissed", "occurred_at": (trigger + timedelta(minutes=1)).isoformat(), "occurrence_key": key, "reason": None}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert correction.status_code == 201, correction.data
    assert AlarmActivity.objects.filter(kind="dismissed").count() == 1


def test_group_time_zone_schedules_one_moment(client, group, member, alarm_payload):
    group.alarm_time_basis, group.alarm_time_zone = "group_time_zone", "Europe/London"
    group.save(update_fields=["alarm_time_basis", "alarm_time_zone"])
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.select_related("group").get(pk=alarm_payload["id"])
    with transaction.atomic():
        schedule_occurrences(alarm, list(Identity.objects.values_list("id", flat=True)))
    assert AlarmOccurrence.objects.count() == 2
    assert AlarmOccurrence.objects.values("trigger_at").distinct().count() == 1


def test_permission_revocation_rejects_offline_edit(client, group, member, alarm_payload):
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm_id = alarm_payload.pop("id")
    alarm_payload.pop("group_id")
    alarm_payload["membership_id"] = str(GroupMembership.objects.get(group=group, identity_id="2" * 64).pk)
    group.alarm_permission = "leaders"
    group.save(update_fields=["alarm_permission"])
    before = SyncVersion.objects.count()
    denied = member.put(f"/v1/alarms/{alarm_id}", {**alarm_payload, "saved_at": timezone.now().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert denied.status_code == 403
    assert SyncVersion.objects.count() == before


@pytest.mark.parametrize("shared", [False, True])
def test_three_inactive_group_cycles_delete_alarm(client, group, member, alarm_payload, shared):
    from jay_server.social.alarms import record_alarm_response

    group.shared_answers = shared
    group.save(update_fields=["shared_answers"])
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.select_related("group").get(pk=alarm_payload["id"])
    for cycle in range(3):
        trigger = timezone.now() - timedelta(days=3 - cycle)
        key = str(int(trigger.timestamp() * 1000))
        for identity in Identity.objects.all():
            AlarmOccurrence.objects.create(alarm=alarm, group=group, identity=identity, alarm_revision=1, occurrence_key=key, cycle_date=trigger.date(), trigger_at=trigger, deadline_at=trigger + timedelta(minutes=10))
        with transaction.atomic():
            for identity in Identity.objects.all():
                if AlarmOccurrence.objects.get(alarm=alarm, identity=identity, occurrence_key=key).state == "pending":
                    record_alarm_response(alarm, identity, {"id": uuid4(), "alarm_revision": 1, "kind": "ignored", "occurred_at": trigger + timedelta(minutes=10), "occurrence_key": key, "reason": "deadline"})
        alarm.refresh_from_db()
        assert alarm.inactive_cycle_streak == cycle + 1
    assert alarm.deleted_at is not None


@pytest.mark.parametrize("shared", [False, True])
def test_snooze_resolves_other_members_only_when_shared(client, group, member, alarm_payload, shared):
    group.shared_answers = shared
    group.save(update_fields=["shared_answers"])
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    trigger = timezone.now() - timedelta(minutes=1)
    key = str(int(trigger.timestamp() * 1000))
    for identity in Identity.objects.all():
        AlarmOccurrence.objects.create(alarm=alarm, group=group, identity=identity, alarm_revision=1, occurrence_key=key, cycle_date=trigger.date(), trigger_at=trigger, deadline_at=trigger + timedelta(minutes=10))
    response = client.post(f"/v1/alarms/{alarm.pk}/activity", {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "snoozed", "occurred_at": timezone.now().isoformat(), "occurrence_key": key, "reason": None}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 201, response.data
    assert AlarmOccurrence.objects.get(alarm=alarm, identity_id="1" * 64, occurrence_key=key).state == "pending"
    assert AlarmOccurrence.objects.get(alarm=alarm, identity_id="2" * 64, occurrence_key=key).state == ("snoozed" if shared else "pending")
