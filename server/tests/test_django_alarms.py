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


@pytest.fixture(autouse=True)
def modern_alarm_timing(settings):
    settings.LEGACY_CLIENT_SUPPORT = False


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
@pytest.mark.parametrize("legacy", [False, True])
def test_snooze_postpones_other_members_only_when_shared(client, group, member, alarm_payload, shared, legacy):
    group.shared_answers = shared
    group.save(update_fields=["shared_answers"])
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    trigger = timezone.now() - timedelta(minutes=1)
    key = str(int(trigger.timestamp() * 1000))
    for identity in Identity.objects.all():
        AlarmOccurrence.objects.create(alarm=alarm, group=group, identity=identity, alarm_revision=1, occurrence_key=key, cycle_date=trigger.date(), trigger_at=trigger, deadline_at=trigger + timedelta(minutes=10))
    occurred_at = timezone.now().replace(second=37, microsecond=456000)
    snoozed_until = (trigger + timedelta(minutes=alarm.snooze_minutes)).replace(second=0, microsecond=0) if legacy else occurred_at + timedelta(minutes=7)
    response_data = {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "snoozed", "occurred_at": occurred_at.isoformat(), "occurrence_key": key, "reason": None}
    if not legacy:
        response_data["snoozed_until"] = snoozed_until.isoformat()
    response = client.post(f"/v1/alarms/{alarm.pk}/activity", response_data, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 201, response.data
    source = AlarmOccurrence.objects.get(alarm=alarm, identity_id="1" * 64, occurrence_key=key)
    other = AlarmOccurrence.objects.get(alarm=alarm, identity_id="2" * 64, occurrence_key=key)
    assert source.state == other.state == "pending"
    assert source.snoozed_until == snoozed_until
    if legacy:
        assert source.snoozed_until.second == source.snoozed_until.microsecond == 0
    assert source.deadline_at == snoozed_until + timedelta(minutes=alarm.timeout_minutes)
    assert other.snoozed_until == (snoozed_until if shared else None)
    assert other.deadline_at == (source.deadline_at if shared else trigger + timedelta(minutes=10))

    stale_schedule = client.put(f"/v1/alarms/{alarm.pk}/occurrence", {"membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "occurrence_key": key, "trigger_at": trigger.isoformat(), "deadline_at": (trigger + timedelta(minutes=10)).isoformat(), "cycle_date": trigger.date().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert stale_schedule.status_code == 200, stale_schedule.data
    source.refresh_from_db()
    assert source.snoozed_until == snoozed_until
    assert source.deadline_at == snoozed_until + timedelta(minutes=alarm.timeout_minutes)

    if shared:
        stale_ignore = member.post(f"/v1/alarms/{alarm.pk}/activity", {"id": str(uuid4()), "membership_id": str(GroupMembership.objects.get(group=group, identity_id="2" * 64).pk), "alarm_revision": 1, "kind": "ignored", "occurred_at": (trigger + timedelta(minutes=alarm.timeout_minutes)).isoformat(), "occurrence_key": key, "reason": "no_response"}, format="json", headers={"Idempotency-Key": str(uuid4())})
        assert stale_ignore.status_code == 409, stale_ignore.data
        assert stale_ignore.data["code"] == "response_outdated"
        other.refresh_from_db()
        assert other.state == "pending"
        assert other.snoozed_until == snoozed_until

    dismissed = client.post(f"/v1/alarms/{alarm.pk}/activity", {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "dismissed", "occurred_at": snoozed_until.isoformat(), "occurrence_key": key, "reason": None}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert dismissed.status_code == 201, dismissed.data
    source.refresh_from_db()
    other.refresh_from_db()
    assert source.state == "dismissed"
    assert source.snoozed_until is None
    assert other.state == ("dismissed" if shared else "pending")
    assert other.snoozed_until is None


def test_alarm_history_describes_edits_enabled_state_and_delivery(client, group, alarm_payload):
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm_id = alarm_payload.pop("id")
    alarm_payload.pop("group_id")
    created_history = client.get(f"/v1/alarms/{alarm_id}/activity").data["items"][0]
    assert created_history["details"]["days"] == alarm_payload["days"]
    assert created_history["details"]["enabled"] == alarm_payload["enabled"]
    original_label = alarm_payload["label"]
    for enabled, action in [(alarm_payload["enabled"], "edited"), (False, "disabled"), (True, "enabled")]:
        response = client.put(f"/v1/alarms/{alarm_id}", {**alarm_payload, "label": "Morning", "enabled": enabled, "saved_at": timezone.now().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
        assert response.status_code == 200, response.data
        history = client.get(f"/v1/alarms/{alarm_id}/activity").data["items"][0]
        assert history["action"] == action
        assert history["details"]["previous_label"] == original_label
        assert history["details"]["label"] == "Morning"
        assert history["details"]["time"] == response.data["local_time_ms"]
        assert history["details"]["enabled"] == enabled
        original_label = "Morning"
    assert client.post(f"/v1/alarms/{alarm_id}/deliveries", {"revision": 4}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 204
    history = client.get(f"/v1/alarms/{alarm_id}/activity").data["items"][0]
    assert history["action"] == "delivered"
    assert history["subject_id"] == history["actor_id"]
    assert history["subject_label"] == history["actor_label"]


@pytest.mark.parametrize("shared", [False, True])
@pytest.mark.parametrize("ring_offset,pressed_offset,next_offset", [
    (0, timedelta(minutes=1, seconds=40), timedelta(minutes=5)),
    (0, timedelta(minutes=6, seconds=17), timedelta(minutes=7)),
    (0, timedelta(minutes=5), timedelta(minutes=6)),
    (5, timedelta(minutes=6, seconds=40), timedelta(minutes=10)),
])
def test_legacy_snooze_uses_current_ring_and_next_minute_when_elapsed(client, group, member, alarm_payload, shared, ring_offset, pressed_offset, next_offset):
    group.shared_answers = shared
    group.save(update_fields=["shared_answers"])
    trigger = timezone.now().replace(hour=9, minute=0, second=0, microsecond=0)
    alarm_payload.update(local_time_ms=9 * 3600000, start_date=trigger.date().isoformat(), end_occurrences=1, timeout_minutes=12)
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    key = str(int(trigger.timestamp() * 1000))
    ring_time = trigger + timedelta(minutes=ring_offset)
    for identity in Identity.objects.all():
        AlarmOccurrence.objects.create(alarm=alarm, group=group, identity=identity, alarm_revision=1, occurrence_key=key, cycle_date=trigger.date(), trigger_at=trigger, deadline_at=ring_time + timedelta(minutes=alarm.timeout_minutes), snoozed_until=ring_time if ring_offset else None, snoozed_at=trigger + timedelta(minutes=1, seconds=40) if ring_offset else None)
    original = AlarmOccurrence.objects.get(alarm=alarm, identity_id="1" * 64, occurrence_key=key)
    occurred_at = trigger + pressed_offset
    response = client.post(f"/v1/alarms/{alarm.pk}/activity", {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "snoozed", "occurred_at": occurred_at.isoformat(), "occurrence_key": key, "reason": None}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 201, response.data
    source = AlarmOccurrence.objects.get(pk=original.pk)
    other = AlarmOccurrence.objects.get(alarm=alarm, identity_id="2" * 64, occurrence_key=key)
    expected = trigger + next_offset
    assert source.state == "pending" and source.resolved_at is None
    assert source.snoozed_until == expected
    assert source.snoozed_at == occurred_at
    assert source.deadline_at == expected + timedelta(minutes=alarm.timeout_minutes)
    assert source.trigger_at == trigger and source.occurrence_key == key and source.cycle_date == trigger.date()
    assert AlarmOccurrence.objects.filter(alarm=alarm, identity_id="1" * 64).count() == 1
    assert other.snoozed_until == (expected if shared else ring_time if ring_offset else None)
    assert other.deadline_at == (source.deadline_at if shared else ring_time + timedelta(minutes=alarm.timeout_minutes))
    assert other.trigger_at == trigger and other.occurrence_key == key and other.cycle_date == trigger.date()


def test_legacy_support_schedules_ten_minutes_and_accepts_the_client_deadline(client, group, alarm_payload, settings):
    settings.LEGACY_CLIENT_SUPPORT = True
    created = client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    alarm = SharedAlarm.objects.select_related("group").get(pk=alarm_payload["id"])
    assert alarm.timeout_minutes == 10
    with transaction.atomic():
        schedule_occurrences(alarm, ["1" * 64])
    occurrence = AlarmOccurrence.objects.get(alarm=alarm, identity_id="1" * 64, state="pending")
    assert occurrence.deadline_at == occurrence.trigger_at + timedelta(minutes=10)
    deadline = occurrence.trigger_at + timedelta(minutes=18)
    registered = client.put(f"/v1/alarms/{alarm.pk}/occurrence", {
        "membership_id": alarm_payload["membership_id"], "alarm_revision": 1,
        "occurrence_key": occurrence.occurrence_key, "trigger_at": occurrence.trigger_at.isoformat(),
        "deadline_at": deadline.isoformat(), "cycle_date": occurrence.cycle_date.isoformat(),
    }, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert registered.status_code == 200, registered.data
    occurrence.refresh_from_db()
    alarm.refresh_from_db()
    assert occurrence.deadline_at == deadline
    assert alarm.timeout_minutes == 10


@pytest.mark.parametrize("editing", [False, True])
@pytest.mark.parametrize("policy", [{"missed_restart_enabled": True}, {"timeout_minutes": 25}, {"timeout_minutes": 5}])
def test_legacy_support_defers_advanced_shared_alarm_timing(client, group, alarm_payload, settings, editing, policy):
    if editing:
        assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
        alarm_id = alarm_payload.pop("id")
        alarm_payload.pop("group_id")
    settings.LEGACY_CLIENT_SUPPORT = True
    payload = {**alarm_payload, **policy, "saved_at": timezone.now().isoformat()}
    if editing:
        response = client.put(f"/v1/alarms/{alarm_id}", payload, format="json", headers={"Idempotency-Key": str(uuid4())})
        unchanged = SharedAlarm.objects.get(pk=alarm_id)
        assert unchanged.missed_restart_enabled is False
        assert unchanged.timeout_minutes == 5
    else:
        response = client.post("/v1/alarms", payload, format="json", headers={"Idempotency-Key": str(uuid4())})
        assert not SharedAlarm.objects.filter(pk=alarm_payload["id"]).exists()
    assert response.status_code == 400, response.data
    assert response.data["code"] == "client_upgrade_required"


def test_legacy_support_preserves_stored_policy_but_times_out_without_restarting(client, group, alarm_payload, settings):
    alarm_payload.update(timeout_minutes=25, missed_restart_enabled=True, missed_restart_minutes=7, end_occurrences=1)
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm_id = alarm_payload.pop("id")
    alarm_payload.pop("group_id")
    for field in ["timeout_minutes", "missed_restart_enabled", "missed_restart_minutes"]:
        alarm_payload.pop(field)
    settings.LEGACY_CLIENT_SUPPORT = True
    edited = client.put(f"/v1/alarms/{alarm_id}", {**alarm_payload, "label": "Legacy edit", "saved_at": timezone.now().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert edited.status_code == 200, edited.data
    alarm = SharedAlarm.objects.get(pk=alarm_id)
    assert alarm.timeout_minutes == 25
    assert alarm.missed_restart_enabled is True
    assert alarm.missed_restart_minutes == 7
    trigger = timezone.now() - timedelta(minutes=11)
    occurrence = AlarmOccurrence.objects.create(alarm=alarm, group=group, identity_id="1" * 64, alarm_revision=alarm.revision, occurrence_key=str(int(trigger.timestamp() * 1000)), cycle_date=trigger.date(), trigger_at=trigger, deadline_at=trigger + timedelta(minutes=10))
    DeliveryWork.objects.filter(kind="reschedule").delete()
    assert process_deadlines()
    occurrence.refresh_from_db()
    alarm.refresh_from_db()
    assert occurrence.state == "ignored"
    assert occurrence.snoozed_until is None
    assert not AlarmActivity.objects.filter(alarm=alarm, kind="snoozed").exists()
    assert alarm.timeout_minutes == 25
    assert alarm.missed_restart_enabled is True


@pytest.mark.parametrize("shared", [False, True])
@pytest.mark.parametrize("explicit", [False, True])
def test_legacy_support_snoozes_from_the_press_and_preserves_shared_answers(client, group, member, alarm_payload, settings, shared, explicit):
    group.shared_answers = shared
    group.save(update_fields=["shared_answers"])
    alarm_payload.update(timeout_minutes=25, end_occurrences=1)
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    settings.LEGACY_CLIENT_SUPPORT = True
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    trigger = timezone.now() - timedelta(minutes=2)
    key = str(int(trigger.timestamp() * 1000))
    for identity in Identity.objects.all():
        AlarmOccurrence.objects.create(alarm=alarm, group=group, identity=identity, alarm_revision=1, occurrence_key=key, cycle_date=trigger.date(), trigger_at=trigger, deadline_at=trigger + timedelta(minutes=10))
    occurred_at = timezone.now().replace(second=37, microsecond=456000)
    snoozed_until = occurred_at + timedelta(minutes=7) if explicit else (occurred_at + timedelta(minutes=alarm.snooze_minutes)).replace(second=0, microsecond=0)
    payload = {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "snoozed", "occurred_at": occurred_at.isoformat(), "occurrence_key": key, "reason": None}
    if explicit:
        payload["snoozed_until"] = snoozed_until.isoformat()
    snoozed = client.post(f"/v1/alarms/{alarm.pk}/activity", payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert snoozed.status_code == 201, snoozed.data
    source = AlarmOccurrence.objects.get(alarm=alarm, identity_id="1" * 64, occurrence_key=key)
    other = AlarmOccurrence.objects.get(alarm=alarm, identity_id="2" * 64, occurrence_key=key)
    assert source.snoozed_until == snoozed_until
    assert source.deadline_at == snoozed_until + timedelta(minutes=10)
    assert other.snoozed_until == (snoozed_until if shared else None)
    assert other.deadline_at == (source.deadline_at if shared else trigger + timedelta(minutes=10))
    stale_schedule = client.put(f"/v1/alarms/{alarm.pk}/occurrence", {"membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "occurrence_key": key, "trigger_at": trigger.isoformat(), "deadline_at": (trigger + timedelta(minutes=18)).isoformat(), "cycle_date": trigger.date().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert stale_schedule.status_code == 200, stale_schedule.data
    source.refresh_from_db()
    assert source.snoozed_until == snoozed_until
    assert source.deadline_at == snoozed_until + timedelta(minutes=10)
    stale_ignore = client.post(f"/v1/alarms/{alarm.pk}/activity", {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "ignored", "occurred_at": (trigger + timedelta(minutes=10)).isoformat(), "occurrence_key": key, "reason": "no_response"}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert stale_ignore.status_code == 409, stale_ignore.data
    assert stale_ignore.data["code"] == "response_outdated"
    assert not AlarmActivity.objects.filter(alarm=alarm, kind="ignored").exists()
    dismissed = client.post(f"/v1/alarms/{alarm.pk}/activity", {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "dismissed", "occurred_at": snoozed_until.isoformat(), "occurrence_key": key, "reason": None}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert dismissed.status_code == 201, dismissed.data
    source.refresh_from_db()
    other.refresh_from_db()
    assert source.state == "dismissed" and source.snoozed_until is None
    assert other.state == ("dismissed" if shared else "pending")
    assert other.snoozed_until is None


@pytest.mark.parametrize("processing_delay", [timedelta(0), timedelta(seconds=20)])
def test_explicit_snooze_preserves_a_response_just_before_its_target_when_processing_is_delayed(client, group, alarm_payload, monkeypatch, processing_delay):
    trigger = timezone.now().replace(hour=9, minute=0, second=0, microsecond=0)
    alarm_payload.update(local_time_ms=9 * 3600000, start_date=trigger.date().isoformat(), end_occurrences=1, timeout_minutes=5)
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    key = str(int(trigger.timestamp() * 1000))
    target = trigger + timedelta(minutes=5)
    original = AlarmOccurrence.objects.create(alarm=alarm, group=group, identity_id="1" * 64, alarm_revision=1, occurrence_key=key, cycle_date=trigger.date(), trigger_at=trigger, deadline_at=target)
    occurred_at = target - timedelta(milliseconds=1)
    monkeypatch.setattr(timezone, "now", lambda: target + processing_delay)
    response = client.post(f"/v1/alarms/{alarm.pk}/activity", {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "snoozed", "occurred_at": occurred_at.isoformat(), "occurrence_key": key, "snoozed_until": target.isoformat(), "reason": None}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 201, response.data
    activity = AlarmActivity.objects.get(pk=response.data["id"])
    assert activity.occurred_at == occurred_at
    source = AlarmOccurrence.objects.get(pk=original.pk)
    assert source.state == "pending" and source.resolved_at is None
    assert source.snoozed_until == target and source.snoozed_at == occurred_at
    assert source.deadline_at == trigger + timedelta(minutes=10)
    assert source.trigger_at == trigger and source.occurrence_key == key and source.cycle_date == trigger.date()
    assert AlarmOccurrence.objects.filter(alarm=alarm, identity_id="1" * 64).count() == 1
