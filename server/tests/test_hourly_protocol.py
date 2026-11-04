import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from django.forms.models import model_to_dict
from django.utils import timezone

from jay_server.social.alarms import schedule_occurrences
from jay_server.social.models import AlarmOccurrence, DeliveryWork, GroupMembership, Identity, SharedAlarm
from jay_server.social.recurrence import next_alarm_trigger


pytestmark = pytest.mark.django_db(transaction=True)


def test_later_start_offset_is_accepted_only_for_hourly_alarms(client, group, alarm_payload):
    rejected = client.post("/v1/alarms", {**alarm_payload, "start_with_later_offset": True}, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "3"})
    assert rejected.status_code == 400, rejected.data
    assert not SharedAlarm.objects.filter(pk=alarm_payload["id"]).exists()

    calendar = client.post("/v1/alarms", {**alarm_payload, "start_with_later_offset": False}, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "3"})
    assert calendar.status_code == 201, calendar.data
    assert calendar.data["start_with_later_offset"] is False

    hourly = client.post("/v1/alarms", {**alarm_payload, "id": str(uuid4()), "repeat_unit": "HOUR", "advanced": True, "start_with_later_offset": True}, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "3"})
    assert hourly.status_code == 201, hourly.data
    assert hourly.data["start_with_later_offset"] is True


def test_hourly_alarm_sync_stays_safe_for_a_legacy_device_of_an_updated_profile(client, group, alarm_payload):
    payload = {**alarm_payload, "repeat_unit": "HOUR", "advanced": True}
    created = client.post("/v1/alarms", payload, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "3"})
    assert created.status_code == 201, created.data
    assert created.data["repeat_unit"] == "HOUR"
    assert Identity.objects.get(pk="1" * 64).client_protocol == 3

    modern = client.get(f"/v1/groups/{group.pk}/sync", headers={"X-Jay-Protocol": "3"})
    assert modern.status_code == 200
    assert any(item["kind"] == "alarm" and item["data"].get("repeat_unit") == "HOUR" for item in modern.data["items"])
    legacy = client.get(f"/v1/groups/{group.pk}/sync", headers={"X-Jay-Protocol": "2"})
    assert legacy.status_code == 200
    assert '"HOUR"' not in json.dumps(legacy.json())
    assert any(item["kind"] == "alarm" and item["key"] == payload["id"] and item["action"] == "delete" for item in legacy.data["items"])


def test_calendar_to_hourly_delta_removes_the_legacy_alarm_and_upgrade_resnapshots(client, group, alarm_payload):
    created = client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "2"})
    assert created.status_code == 201, created.data
    legacy = client.get(f"/v1/groups/{group.pk}/sync", headers={"X-Jay-Protocol": "2"})
    assert legacy.status_code == 200
    update = {key: value for key, value in alarm_payload.items() if key not in {"id", "group_id"}}
    update.update(repeat_unit="HOUR", advanced=True, saved_at=(timezone.now() - timedelta(seconds=1)).isoformat())
    changed = client.put(f"/v1/alarms/{created.data['id']}", update, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "3"})
    assert changed.status_code == 200, changed.data

    delta = client.get(f"/v1/groups/{group.pk}/sync", {"cursor": legacy.data["next_cursor"]}, headers={"X-Jay-Protocol": "2"})
    assert delta.status_code == 200
    assert delta.data["mode"] == "delta"
    assert '"HOUR"' not in json.dumps(delta.json())
    assert any(item["kind"] == "alarm" and item["key"] == created.data["id"] and item["action"] == "delete" for item in delta.data["items"])

    upgraded = client.get(f"/v1/groups/{group.pk}/sync", {"cursor": delta.data["next_cursor"]}, headers={"X-Jay-Protocol": "3"})
    assert upgraded.status_code == 200
    assert upgraded.data["mode"] == "snapshot"
    assert any(item["kind"] == "alarm" and item["data"].get("repeat_unit") == "HOUR" for item in upgraded.data["items"])
    downgraded = client.get(f"/v1/groups/{group.pk}/sync", {"cursor": upgraded.data["next_cursor"]}, headers={"X-Jay-Protocol": "2"})
    assert downgraded.status_code == 200
    assert downgraded.data["mode"] == "snapshot"
    assert '"HOUR"' not in json.dumps(downgraded.json())


def test_legacy_members_keep_calendar_alarms_and_do_not_receive_hourly_occurrences(client, member, group, alarm_payload):
    normal = client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "2"})
    assert normal.status_code == 201, normal.data
    hourly = {**alarm_payload, "id": str(uuid4()), "repeat_unit": "HOUR", "advanced": True}
    created = client.post("/v1/alarms", hourly, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "3"})
    assert created.status_code == 201, created.data
    alarm = SharedAlarm.objects.get(pk=created.data["id"])
    schedule_occurrences(alarm, ["1" * 64, "2" * 64])
    assert AlarmOccurrence.objects.filter(alarm=alarm, identity_id="1" * 64).exists()
    assert not AlarmOccurrence.objects.filter(alarm=alarm, identity_id="2" * 64).exists()

    legacy = member.get(f"/v1/groups/{group.pk}/sync", headers={"X-Jay-Protocol": "2"})
    assert legacy.status_code == 200
    assert '"HOUR"' not in json.dumps(legacy.json())
    assert any(item["kind"] == "alarm" and item["key"] == normal.data["id"] and item["action"] == "upsert" for item in legacy.data["items"])


def test_member_upgrade_enables_existing_hourly_schedules_once(client, member, group, alarm_payload):
    created = client.post("/v1/alarms", {**alarm_payload, "repeat_unit": "HOUR", "advanced": True}, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "3"})
    assert created.status_code == 201, created.data
    alarm = SharedAlarm.objects.get(pk=created.data["id"])
    schedule_occurrences(alarm, ["2" * 64])
    assert not AlarmOccurrence.objects.filter(alarm=alarm, identity_id="2" * 64).exists()

    capabilities = member.get("/v1/identity/capabilities", headers={"X-Jay-Protocol": "3"})
    assert capabilities.status_code == 200
    assert capabilities.data["hourly_alarm_recurrence"] is True
    assert Identity.objects.get(pk="2" * 64).client_protocol == 3
    assert DeliveryWork.objects.filter(deduplication_key=f"reschedule:protocol:{'2' * 64}:3").count() == 1
    member.get("/v1/identity/capabilities", headers={"X-Jay-Protocol": "3"})
    member.get("/v1/identity/capabilities", headers={"X-Jay-Protocol": "2"})
    assert DeliveryWork.objects.filter(deduplication_key=f"reschedule:protocol:{'2' * 64}:3").count() == 1
    assert Identity.objects.get(pk="2" * 64).client_protocol == 3
    schedule_occurrences(alarm, ["2" * 64])
    assert AlarmOccurrence.objects.filter(alarm=alarm, identity_id="2" * 64).exists()


def test_legacy_writes_cannot_receive_hourly_conflict_or_receipt_payloads(client, group, alarm_payload):
    created = client.post("/v1/alarms", {**alarm_payload, "repeat_unit": "HOUR", "advanced": True}, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "3"})
    assert created.status_code == 201, created.data
    update = {key: value for key, value in alarm_payload.items() if key not in {"id", "group_id"}}
    changed = client.put(f"/v1/alarms/{created.data['id']}", update, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "2"})
    assert changed.status_code == 403
    assert changed.data["code"] == "client_upgrade_required"
    assert '"HOUR"' not in json.dumps(changed.json())
    activity = client.get(f"/v1/groups/{group.pk}/activity", headers={"X-Jay-Protocol": "2"})
    assert activity.status_code == 200
    assert '"HOUR"' not in json.dumps(activity.json())
    assert any(item["entity_type"] == "group" for item in activity.data["items"])
    rejected = client.post("/v1/alarms", {**alarm_payload, "id": str(uuid4()), "repeat_unit": "HOUR"}, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "2"})
    assert rejected.status_code == 403
    assert rejected.data["code"] == "client_upgrade_required"


def test_hourly_occurrence_registration_rejects_an_unanchored_trigger(client, group, alarm_payload):
    payload = {**alarm_payload, "repeat_unit": "HOUR", "advanced": True, "start_date": "2026-01-01"}
    created = client.post("/v1/alarms", payload, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "3"})
    assert created.status_code == 201, created.data
    alarm = SharedAlarm.objects.get(pk=created.data["id"])
    trigger = next_alarm_trigger(model_to_dict(alarm), "UTC", datetime(2026, 1, 1, 8, 0, tzinfo=UTC))
    invalid = trigger + timedelta(minutes=17)
    command = {
        "membership_id": payload["membership_id"], "alarm_revision": alarm.revision,
        "occurrence_key": str(int(invalid.timestamp() * 1000)), "trigger_at": invalid.isoformat(),
        "deadline_at": (invalid + timedelta(minutes=5)).isoformat(), "cycle_date": invalid.date().isoformat(),
    }
    rejected = client.put(f"/v1/alarms/{alarm.pk}/occurrence", command, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "3"})
    assert rejected.status_code == 400, rejected.data
    command.update(occurrence_key=str(int(trigger.timestamp() * 1000)), trigger_at=trigger.isoformat(), deadline_at=(trigger + timedelta(minutes=5)).isoformat(), cycle_date=trigger.date().isoformat())
    accepted = client.put(f"/v1/alarms/{alarm.pk}/occurrence", command, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "3"})
    assert accepted.status_code == 200, accepted.data
    assert AlarmOccurrence.objects.get(pk=accepted.data["id"]).cycle_index == 1
