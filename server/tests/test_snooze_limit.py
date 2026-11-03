from datetime import timedelta
from uuid import uuid4

import pytest
from django.db import connection
from django.db.migrations.executor import MigrationExecutor
from django.utils import timezone

from jay_server.social.models import AlarmActivity, AlarmOccurrence, GroupMembership, Identity, SharedAlarm


pytestmark = pytest.mark.django_db(transaction=True)


def test_snooze_migration_preserves_existing_rows_and_accepts_old_server_inserts(group):
    before = [("social", "0011_timer_timeout_default")]
    executor = MigrationExecutor(connection)
    after = executor.loader.graph.leaf_nodes()
    executor.migrate(before)
    old_apps = executor.loader.project_state(before).apps
    old_alarms = old_apps.get_model("social", "SharedAlarm").objects
    old_occurrences = old_apps.get_model("social", "AlarmOccurrence").objects
    trigger = timezone.now().replace(second=0, microsecond=0)
    alarm_fields = {"group_id": group.pk, "local_time_ms": 8 * 3600000, "label": "Stable alarm", "enabled": True, "days": list(range(7)), "vibrate": True, "start_date": trigger.date(), "repeat_interval": 1, "repeat_unit": "DAY", "repeat_anchor": "DAY_OF_MONTH", "repeat_duration": None, "repeat_duration_unit": "DAY", "end_date": None, "end_occurrences": None, "snooze_enabled": True, "snooze_minutes": 10, "vibration_pattern": [0, 100], "vibration_pattern_name": "Default"}
    occurrence_fields = {"group_id": group.pk, "identity_id": "1" * 64, "alarm_revision": 1, "occurrence_key": str(int(trigger.timestamp() * 1000)), "cycle_date": trigger.date(), "trigger_at": trigger, "deadline_at": trigger + timedelta(minutes=5)}
    try:
        existing_alarm = old_alarms.create(**alarm_fields)
        existing_occurrence = old_occurrences.create(alarm_id=existing_alarm.pk, **occurrence_fields)
    finally:
        MigrationExecutor(connection).migrate(after)
    assert SharedAlarm.objects.get(pk=existing_alarm.pk).snooze_limit == 0
    assert AlarmOccurrence.objects.get(pk=existing_occurrence.pk).snooze_count == 0
    rolling_alarm = old_alarms.create(**alarm_fields)
    rolling_occurrence = old_occurrences.create(alarm_id=rolling_alarm.pk, **occurrence_fields)
    assert SharedAlarm.objects.get(pk=rolling_alarm.pk).snooze_limit == 0
    assert AlarmOccurrence.objects.get(pk=rolling_occurrence.pk).snooze_count == 0


def test_stable_alarm_writes_default_to_unlimited_and_preserve_a_new_client_limit(client, alarm_payload):
    client.credentials(HTTP_AUTHORIZATION="Bearer " + "secret" * 10, HTTP_X_JAY_IDENTITY_ID="1" * 64, HTTP_X_JAY_PROTOCOL="2")
    alarm_payload.update(label_color=0, timeout_minutes=5, missed_restart_enabled=False, missed_restart_minutes=10)
    created = client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    assert created.data["snooze_limit"] == 0
    alarm_id = alarm_payload.pop("id")
    alarm_payload.pop("group_id")
    path = f"/v1/alarms/{alarm_id}"
    updated = client.put(path, {**alarm_payload, "saved_at": timezone.now().isoformat(), "snooze_limit": 2}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert updated.status_code == 200, updated.data
    legacy_edit = client.put(path, {**alarm_payload, "saved_at": timezone.now().isoformat(), "label": "Stable client edit"}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert legacy_edit.status_code == 200, legacy_edit.data
    assert legacy_edit.data["snooze_limit"] == 2
    assert legacy_edit.data["label"] == "Stable client edit"


def test_stable_unlimited_alarm_accepts_repeated_snoozes_and_dismissal(client, alarm_payload):
    client.credentials(HTTP_AUTHORIZATION="Bearer " + "secret" * 10, HTTP_X_JAY_IDENTITY_ID="1" * 64, HTTP_X_JAY_PROTOCOL="2")
    alarm_payload.update(label_color=0, timeout_minutes=5, missed_restart_enabled=False, missed_restart_minutes=10)
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    path = f"/v1/alarms/{alarm_payload['id']}"
    trigger = timezone.now().replace(second=0, microsecond=0)
    key = str(int(trigger.timestamp() * 1000))
    registered = client.put(f"{path}/occurrence", {"membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "occurrence_key": key, "trigger_at": trigger.isoformat(), "deadline_at": (trigger + timedelta(minutes=5)).isoformat(), "cycle_date": trigger.date().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert registered.status_code == 200, registered.data
    assert registered.data["snooze_count"] == 0
    for index in range(3):
        pressed = trigger + timedelta(minutes=6 * index + 1)
        response = client.post(f"{path}/activity", {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "snoozed", "reason": None, "occurrence_key": key, "occurred_at": pressed.isoformat(), "snoozed_until": (pressed + timedelta(minutes=5)).isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
        assert response.status_code == 201, response.data
    occurrence = AlarmOccurrence.objects.get(alarm_id=alarm_payload["id"], identity_id="1" * 64, occurrence_key=key)
    assert occurrence.snooze_count == 3
    dismissed = client.post(f"{path}/activity", {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "dismissed", "reason": None, "occurrence_key": key, "occurred_at": (occurrence.snoozed_until + timedelta(minutes=1)).isoformat(), "snoozed_until": None}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert dismissed.status_code == 201, dismissed.data
    occurrence.refresh_from_db()
    assert occurrence.state == "dismissed"


@pytest.mark.parametrize("shared", [False, True])
def test_manual_snooze_budget_is_per_occurrence_and_follows_shared_answers(client, group, member, alarm_payload, shared):
    group.shared_answers = shared
    group.save(update_fields=["shared_answers"])
    alarm_payload["snooze_limit"] = 1
    created = client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    assert created.data["snooze_limit"] == 1
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    trigger = timezone.now() - timedelta(minutes=1)
    key = str(int(trigger.timestamp() * 1000))
    for identity in Identity.objects.all():
        AlarmOccurrence.objects.create(alarm=alarm, group=group, identity=identity, alarm_revision=1, occurrence_key=key, cycle_date=trigger.date(), trigger_at=trigger, deadline_at=trigger + timedelta(minutes=10))
    path = f"/v1/alarms/{alarm.pk}/activity"
    payload = {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "snoozed", "occurred_at": timezone.now().isoformat(), "occurrence_key": key, "reason": None}
    snoozed = client.post(path, payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert snoozed.status_code == 201, snoozed.data
    replayed = client.post(path, payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert replayed.status_code == 201, replayed.data
    assert replayed.data["id"] == snoozed.data["id"]
    source = AlarmOccurrence.objects.get(alarm=alarm, identity_id="1" * 64, occurrence_key=key)
    other = AlarmOccurrence.objects.get(alarm=alarm, identity_id="2" * 64, occurrence_key=key)
    assert source.snooze_count == 1
    assert other.snooze_count == int(shared)
    denied = client.post(path, {**payload, "id": str(uuid4()), "occurred_at": source.snoozed_until.isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert denied.status_code == 409, denied.data
    assert denied.data["code"] == "snooze_limit_reached"
    membership = str(GroupMembership.objects.get(group=group, identity_id="2" * 64).pk)
    other_response = member.post(path, {**payload, "id": str(uuid4()), "membership_id": membership, "occurred_at": source.snoozed_until.isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert other_response.status_code == (409 if shared else 201), other_response.data
    assert AlarmActivity.objects.filter(alarm=alarm, kind="snoozed").count() == (1 if shared else 2)
    next_trigger = trigger + timedelta(days=1)
    next_key = str(int(next_trigger.timestamp() * 1000))
    AlarmOccurrence.objects.get_or_create(alarm=alarm, identity_id="1" * 64, occurrence_key=next_key, defaults={"group": group, "alarm_revision": 1, "cycle_date": next_trigger.date(), "trigger_at": next_trigger, "deadline_at": next_trigger + timedelta(minutes=10)})
    next_response = client.post(path, {**payload, "id": str(uuid4()), "occurrence_key": next_key, "occurred_at": next_trigger.isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert next_response.status_code == 201, next_response.data


def test_automatic_restart_does_not_spend_or_require_a_manual_snooze(client, group, alarm_payload):
    alarm_payload.update(snooze_limit=1, missed_restart_enabled=True, missed_restart_minutes=10)
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    trigger = timezone.now() - timedelta(minutes=1)
    key = str(int(trigger.timestamp() * 1000))
    occurrence = AlarmOccurrence.objects.create(alarm=alarm, group=group, identity_id="1" * 64, alarm_revision=1, occurrence_key=key, cycle_date=trigger.date(), trigger_at=trigger, deadline_at=trigger + timedelta(minutes=5), snooze_count=1)
    response = client.post(f"/v1/alarms/{alarm.pk}/activity", {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "snoozed", "reason": "timed_out", "occurrence_key": key, "occurred_at": occurrence.deadline_at.isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 201, response.data
    occurrence.refresh_from_db()
    assert occurrence.snooze_count == 1
    assert occurrence.snoozed_until == trigger + timedelta(minutes=15)


@pytest.mark.parametrize("limit", [0, 100, -1, 101])
def test_snooze_limit_range(client, alarm_payload, limit):
    alarm_payload["snooze_limit"] = limit
    response = client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == (201 if 0 <= limit <= 100 else 400), response.data


@pytest.mark.parametrize("limit", [0, 2])
def test_snoozes_count_up_to_the_configured_limit_and_zero_remains_unlimited(client, group, alarm_payload, limit):
    alarm_payload["snooze_limit"] = limit
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    trigger = timezone.now() - timedelta(minutes=1)
    key = str(int(trigger.timestamp() * 1000))
    occurrence = AlarmOccurrence.objects.create(alarm=alarm, group=group, identity_id="1" * 64, alarm_revision=1, occurrence_key=key, cycle_date=trigger.date(), trigger_at=trigger, deadline_at=trigger + timedelta(minutes=10))
    for index in range(3):
        pressed = trigger + timedelta(minutes=5 * index + 1)
        response = client.post(f"/v1/alarms/{alarm.pk}/activity", {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "snoozed", "reason": None, "occurrence_key": key, "occurred_at": pressed.isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
        assert response.status_code == (409 if limit and index >= limit else 201), response.data
    occurrence.refresh_from_db()
    assert occurrence.snooze_count == (limit or 3)
