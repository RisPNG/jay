from datetime import timedelta
from uuid import uuid4

import pytest
from django.db import connection
from django.db.migrations.executor import MigrationExecutor
from django.utils import timezone
from django.utils.dateparse import parse_datetime

from jay_server.social.models import AlarmActivity, AlarmOccurrence, DeliveryWork, GroupMembership, SharedAlarm, SyncVersion
from jay_server.social.worker import process_deadlines


pytestmark = pytest.mark.django_db(transaction=True)


@pytest.fixture
def alarm_payload(alarm_payload):
    return {**alarm_payload, "timeout_minutes": 5}


def test_restart_keeps_the_final_occurrence_pending_at_1815_and_1830_without_manual_snooze(client, group, alarm_payload, monkeypatch):
    trigger = timezone.now().replace(hour=18, minute=0, second=0, microsecond=0)
    alarm_payload.update(local_time_ms=18 * 3600000, start_date=trigger.date().isoformat(), end_occurrences=1, snooze_enabled=False, timeout_minutes=5, missed_restart_enabled=True, missed_restart_minutes=10)
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    key = str(int(trigger.timestamp() * 1000))
    occurrence = AlarmOccurrence.objects.create(alarm=alarm, group=group, identity_id="1" * 64, alarm_revision=1, occurrence_key=key, cycle_date=trigger.date(), trigger_at=trigger, deadline_at=trigger + timedelta(minutes=5))
    DeliveryWork.objects.filter(kind="reschedule").delete()
    now = trigger + timedelta(minutes=5)
    monkeypatch.setattr(timezone, "now", lambda: now)

    assert process_deadlines()
    occurrence.refresh_from_db()
    assert occurrence.state == "pending"
    assert occurrence.snoozed_until == trigger + timedelta(minutes=15)
    assert occurrence.deadline_at == trigger + timedelta(minutes=20)
    assert occurrence.trigger_at == trigger
    assert occurrence.occurrence_key == key
    assert occurrence.cycle_date == trigger.date()
    assert occurrence.resolved_at is None

    now = trigger + timedelta(minutes=20)
    assert process_deadlines()
    occurrence.refresh_from_db()
    alarm.refresh_from_db()
    assert occurrence.snoozed_until == trigger + timedelta(minutes=30)
    assert occurrence.snoozed_at == trigger + timedelta(minutes=20)
    assert occurrence.deadline_at == trigger + timedelta(minutes=35)
    assert occurrence.state == "pending"
    assert AlarmActivity.objects.filter(alarm=alarm, kind="snoozed", reason="timed_out").count() == 2
    assert not AlarmActivity.objects.filter(alarm=alarm, kind="ignored").exists()
    assert not alarm.snooze_enabled
    assert alarm.deleted_at is None
    assert alarm.inactive_cycle_streak == 0


@pytest.mark.parametrize("shared", [False, True])
def test_member_local_restart_shares_the_current_ring_only_when_answers_are_shared(client, group, member, alarm_payload, shared, monkeypatch):
    group.shared_answers = shared
    group.save(update_fields=["shared_answers"])
    alice_trigger = timezone.now().replace(hour=18, minute=0, second=0, microsecond=0)
    bob_trigger = alice_trigger - timedelta(hours=8)
    alarm_payload.update(local_time_ms=18 * 3600000, start_date=alice_trigger.date().isoformat(), end_occurrences=1, missed_restart_enabled=True)
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    alice = AlarmOccurrence.objects.create(alarm=alarm, group=group, identity_id="1" * 64, alarm_revision=1, occurrence_key=str(int(alice_trigger.timestamp() * 1000)), cycle_date=alice_trigger.date(), trigger_at=alice_trigger, deadline_at=alice_trigger + timedelta(minutes=5))
    bob = AlarmOccurrence.objects.create(alarm=alarm, group=group, identity_id="2" * 64, alarm_revision=1, occurrence_key=str(int(bob_trigger.timestamp() * 1000)), cycle_date=alice_trigger.date(), trigger_at=bob_trigger, deadline_at=bob_trigger + timedelta(minutes=5))
    DeliveryWork.objects.filter(kind="reschedule").delete()
    now = bob_trigger + timedelta(minutes=5)
    monkeypatch.setattr(timezone, "now", lambda: now)

    assert process_deadlines()
    alice.refresh_from_db()
    bob.refresh_from_db()
    assert bob.snoozed_until == bob_trigger + timedelta(minutes=15)
    assert bob.deadline_at == bob_trigger + timedelta(minutes=20)
    assert bob.snoozed_at == now
    assert alice.snoozed_at == (now if shared else None)
    assert alice.snoozed_until == (bob.snoozed_until if shared else None)
    assert alice.deadline_at == (bob.deadline_at if shared else alice_trigger + timedelta(minutes=5))
    assert alice.trigger_at == alice_trigger
    assert bob.trigger_at == bob_trigger
    assert alice.state == bob.state == "pending"
    assert AlarmActivity.objects.filter(alarm=alarm).count() == 1

    if shared:
        now = bob_trigger + timedelta(minutes=16)
        dismissed = client.post(f"/v1/alarms/{alarm.pk}/activity", {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "dismissed", "occurred_at": now.isoformat(), "occurrence_key": alice.occurrence_key, "reason": None}, format="json", headers={"Idempotency-Key": str(uuid4())})
        assert dismissed.status_code == 201, dismissed.data
        alice.refresh_from_db()
        bob.refresh_from_db()
        assert alice.state == bob.state == "dismissed"
        assert alice.snoozed_until is bob.snoozed_until is None
        assert alice.trigger_at == alice_trigger
    else:
        now = bob_trigger + timedelta(minutes=20)
        assert process_deadlines()
        alice.refresh_from_db()
        bob.refresh_from_db()
        assert bob.snoozed_until == bob_trigger + timedelta(minutes=30)
        assert alice.snoozed_until is None


def test_shared_manual_snooze_can_precede_a_later_zone_original_trigger(client, group, member, alarm_payload):
    group.shared_answers = True
    group.save(update_fields=["shared_answers"])
    alice_trigger = timezone.now().replace(hour=18, minute=0, second=0, microsecond=0)
    bob_trigger = alice_trigger - timedelta(hours=8)
    alarm_payload.update(start_date=alice_trigger.date().isoformat(), end_occurrences=1)
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    alice = AlarmOccurrence.objects.create(alarm=alarm, group=group, identity_id="1" * 64, alarm_revision=1, occurrence_key=str(int(alice_trigger.timestamp() * 1000)), cycle_date=alice_trigger.date(), trigger_at=alice_trigger, deadline_at=alice_trigger + timedelta(minutes=5))
    bob = AlarmOccurrence.objects.create(alarm=alarm, group=group, identity_id="2" * 64, alarm_revision=1, occurrence_key=str(int(bob_trigger.timestamp() * 1000)), cycle_date=alice_trigger.date(), trigger_at=bob_trigger, deadline_at=bob_trigger + timedelta(minutes=5))
    membership_id = str(GroupMembership.objects.get(group=group, identity_id="2" * 64).pk)
    snoozed_until = bob_trigger + timedelta(minutes=11)
    response = member.post(f"/v1/alarms/{alarm.pk}/activity", {"id": str(uuid4()), "membership_id": membership_id, "alarm_revision": 1, "kind": "snoozed", "occurred_at": (bob_trigger + timedelta(minutes=1)).isoformat(), "occurrence_key": bob.occurrence_key, "reason": None, "snoozed_until": snoozed_until.isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 201, response.data
    alice.refresh_from_db()
    bob.refresh_from_db()
    assert alice.snoozed_until == bob.snoozed_until == snoozed_until
    assert alice.deadline_at == bob.deadline_at == snoozed_until + timedelta(minutes=5)
    assert alice.deadline_at < alice.trigger_at

    dismissed = client.post(f"/v1/alarms/{alarm.pk}/activity", {"id": str(uuid4()), "membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "dismissed", "occurred_at": (snoozed_until + timedelta(minutes=1)).isoformat(), "occurrence_key": alice.occurrence_key, "reason": None}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert dismissed.status_code == 201, dismissed.data
    alice.refresh_from_db()
    bob.refresh_from_db()
    assert alice.state == bob.state == "dismissed"
    assert alice.snoozed_until is bob.snoozed_until is None
    assert alice.trigger_at == alice_trigger


@pytest.mark.parametrize("shared", [False, True])
def test_timeout_producers_cannot_repeat_a_phase_or_undo_explicit_dismissal(client, group, member, alarm_payload, shared, monkeypatch):
    group.shared_answers = shared
    group.save(update_fields=["shared_answers"])
    trigger = timezone.now().replace(second=0, microsecond=0)
    alarm_payload.update(start_date=trigger.date().isoformat(), end_occurrences=1, missed_restart_enabled=True)
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    key = str(int(trigger.timestamp() * 1000))
    for identity in ["1" * 64, "2" * 64]:
        AlarmOccurrence.objects.create(alarm=alarm, group=group, identity_id=identity, alarm_revision=1, occurrence_key=key, cycle_date=trigger.date(), trigger_at=trigger, deadline_at=trigger + timedelta(minutes=5))
    DeliveryWork.objects.filter(kind="reschedule").delete()
    now = trigger + timedelta(minutes=5)
    monkeypatch.setattr(timezone, "now", lambda: now)
    membership_id = str(GroupMembership.objects.get(group=group, identity_id="2" * 64).pk)
    payload = {"id": str(uuid4()), "membership_id": membership_id, "alarm_revision": 1, "kind": "snoozed", "occurred_at": now.isoformat(), "occurrence_key": key, "reason": "timed_out", "snoozed_until": (trigger + timedelta(minutes=15)).isoformat()}
    path = f"/v1/alarms/{alarm.pk}/activity"

    accepted = member.post(path, payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert accepted.status_code == 201, accepted.data
    replay = member.post(path, payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert replay.status_code == 201, replay.data
    stale = member.post(path, {**payload, "id": str(uuid4())}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert stale.status_code == 409, stale.data
    assert stale.data["code"] == "response_outdated"
    bob = AlarmOccurrence.objects.get(alarm=alarm, identity_id="2" * 64, occurrence_key=key)
    assert bob.snoozed_until == trigger + timedelta(minutes=15)
    assert bob.deadline_at == trigger + timedelta(minutes=20)
    assert process_deadlines() == (not shared)
    assert AlarmActivity.objects.filter(alarm=alarm, kind="snoozed").count() == (1 if shared else 2)

    old_ignored = member.post(path, {**payload, "id": str(uuid4()), "kind": "ignored", "reason": "no_response", "snoozed_until": None}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert old_ignored.status_code == 409, old_ignored.data
    now = trigger + timedelta(minutes=16)
    dismissed = member.post(path, {**payload, "id": str(uuid4()), "kind": "dismissed", "reason": None, "occurred_at": now.isoformat(), "snoozed_until": None}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert dismissed.status_code == 201, dismissed.data
    after_dismissal = member.post(path, {**payload, "id": str(uuid4()), "occurred_at": (trigger + timedelta(minutes=20)).isoformat(), "snoozed_until": (trigger + timedelta(minutes=30)).isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert after_dismissal.status_code == 409, after_dismissal.data
    bob.refresh_from_db()
    assert bob.state == "dismissed"
    assert bob.snoozed_until is None
    assert not AlarmActivity.objects.filter(alarm=alarm, kind="ignored").exists()


def test_snooze_ordering_rejects_old_manual_and_automatic_outcomes_but_accepts_a_later_earlier_target(client, group, alarm_payload):
    trigger = timezone.now().replace(hour=18, minute=0, second=0, microsecond=0)
    alarm_payload.update(start_date=trigger.date().isoformat(), end_occurrences=1, missed_restart_enabled=True)
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    alarm = SharedAlarm.objects.get(pk=alarm_payload["id"])
    key = str(int(trigger.timestamp() * 1000))
    occurrence = AlarmOccurrence.objects.create(alarm=alarm, group=group, identity_id="1" * 64, alarm_revision=1, occurrence_key=key, cycle_date=trigger.date(), trigger_at=trigger, snoozed_until=trigger + timedelta(minutes=15), snoozed_at=trigger + timedelta(minutes=5), deadline_at=trigger + timedelta(minutes=20))
    path = f"/v1/alarms/{alarm.pk}/activity"
    base = {"membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "kind": "snoozed", "occurrence_key": key}
    advanced = client.post(path, {**base, "id": str(uuid4()), "reason": "timed_out", "occurred_at": (trigger + timedelta(minutes=20)).isoformat(), "snoozed_until": (trigger + timedelta(minutes=30)).isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert advanced.status_code == 201, advanced.data
    for reason, occurred, target in [(None, 10, 25), ("timed_out", 5, 15)]:
        stale = client.post(path, {**base, "id": str(uuid4()), "reason": reason, "occurred_at": (trigger + timedelta(minutes=occurred)).isoformat(), "snoozed_until": (trigger + timedelta(minutes=target)).isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
        assert stale.status_code == 409, stale.data
    later = client.post(path, {**base, "id": str(uuid4()), "reason": None, "occurred_at": (trigger + timedelta(minutes=21)).isoformat(), "snoozed_until": (trigger + timedelta(minutes=26)).isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert later.status_code == 201, later.data
    occurrence.refresh_from_db()
    assert occurrence.snoozed_at == trigger + timedelta(minutes=21)
    assert occurrence.snoozed_until == trigger + timedelta(minutes=26)
    assert occurrence.deadline_at == trigger + timedelta(minutes=31)


def test_legacy_alarm_writes_use_defaults_then_preserve_new_policy_and_history(client, group, alarm_payload):
    alarm_payload.pop("timeout_minutes")
    created = client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    assert created.data["timeout_minutes"] == 10
    assert created.data["missed_restart_enabled"] is False
    assert created.data["missed_restart_minutes"] == 10
    alarm_id = alarm_payload.pop("id")
    alarm_payload.pop("group_id")
    path = f"/v1/alarms/{alarm_id}"
    updated = client.put(path, {**alarm_payload, "saved_at": timezone.now().isoformat(), "timeout_minutes": 12, "missed_restart_enabled": True, "missed_restart_minutes": 7}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert updated.status_code == 200, updated.data
    history = client.get(f"{path}/activity").data["items"][0]["details"]
    assert history["previous_timeout_minutes"] == 10
    assert history["timeout_minutes"] == 12
    assert history["previous_missed_restart_enabled"] is False
    assert history["missed_restart_enabled"] is True
    assert history["previous_missed_restart_minutes"] == 10
    assert history["missed_restart_minutes"] == 7

    old_client = client.put(path, {**alarm_payload, "saved_at": timezone.now().isoformat(), "label": "Old client edit"}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert old_client.status_code == 200, old_client.data
    assert old_client.data["timeout_minutes"] == 12
    assert old_client.data["missed_restart_enabled"] is True
    assert old_client.data["missed_restart_minutes"] == 7


@pytest.mark.parametrize("timeout", [5, 25])
def test_legacy_occurrence_registration_uses_the_alarm_timeout(client, group, alarm_payload, timeout):
    alarm_payload["timeout_minutes"] = timeout
    assert client.post("/v1/alarms", alarm_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    trigger = timezone.now().replace(second=0, microsecond=0)
    response = client.put(f"/v1/alarms/{alarm_payload['id']}/occurrence", {"membership_id": alarm_payload["membership_id"], "alarm_revision": 1, "occurrence_key": str(int(trigger.timestamp() * 1000)), "trigger_at": trigger.isoformat(), "deadline_at": (trigger + timedelta(minutes=10)).isoformat(), "cycle_date": trigger.date().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 200, response.data
    assert parse_datetime(response.data["deadline_at"]) == trigger + timedelta(minutes=timeout)


def test_alarm_restart_migration_preserves_legacy_defaults_and_occurrence_deadlines(client, group):
    before = [("social", "0008_occurrence_snoozed_until")]
    executor = MigrationExecutor(connection)
    after = executor.loader.graph.leaf_nodes()
    executor.migrate(before)
    try:
        apps = executor.loader.project_state(before).apps
        alarm = apps.get_model("social", "SharedAlarm").objects.create(group_id=group.pk, local_time_ms=18 * 3600000, label="Existing", enabled=True, days=list(range(7)), vibrate=True, start_date=timezone.now().date(), repeat_interval=1, repeat_unit="DAY", repeat_anchor="DAY_OF_MONTH", repeat_duration=None, repeat_duration_unit="DAY", end_date=None, end_occurrences=None, snooze_enabled=True, snooze_minutes=10, vibration_pattern=[0, 100], vibration_pattern_name="Default")
        trigger = timezone.now().replace(second=0, microsecond=0)
        occurrences = apps.get_model("social", "AlarmOccurrence")
        for key, state, snoozed_until, deadline in [
            ("initial", "pending", None, trigger + timedelta(minutes=10)),
            ("custom", "pending", None, trigger + timedelta(minutes=18)),
            ("snoozed", "pending", trigger + timedelta(minutes=15), trigger + timedelta(minutes=25)),
            ("resolved", "ignored", None, trigger + timedelta(minutes=10)),
        ]:
            occurrences.objects.create(alarm_id=alarm.pk, group_id=group.pk, identity_id="1" * 64, alarm_revision=1, occurrence_key=key, cycle_date=trigger.date(), trigger_at=trigger, snoozed_until=snoozed_until, deadline_at=deadline, state=state)
        scope = apps.get_model("social", "SyncScope").objects.get(pk=group.scope_id)
        scope.head_revision += 1
        scope.save(update_fields=["head_revision"])
        versions = apps.get_model("social", "SyncVersion")
        old_alarm_version = versions.objects.create(scope_id=group.scope_id, revision=scope.head_revision, ordinal=0, kind="alarm", key=str(alarm.pk), action="upsert", representation={"id": str(alarm.pk)})
        initial_row = occurrences.objects.get(alarm_id=alarm.pk, occurrence_key="initial")
        old_occurrence_version = versions.objects.create(scope_id=group.scope_id, revision=scope.head_revision, ordinal=1, kind="occurrence", key=str(initial_row.pk), action="upsert", recipient_id="1" * 64, representation={"id": str(initial_row.pk), "state": "pending", "deadline_at": initial_row.deadline_at.isoformat()})
        checkpoint = client.get(f"/v1/groups/{group.pk}/sync").data["next_cursor"]
    finally:
        MigrationExecutor(connection).migrate(after)
    upgraded = SharedAlarm.objects.get(pk=alarm.pk)
    assert upgraded.timeout_minutes == 10
    assert upgraded.missed_restart_enabled is False
    assert upgraded.missed_restart_minutes == 10
    initial = AlarmOccurrence.objects.get(alarm=upgraded, occurrence_key="initial")
    custom = AlarmOccurrence.objects.get(alarm=upgraded, occurrence_key="custom")
    snoozed = AlarmOccurrence.objects.get(alarm=upgraded, occurrence_key="snoozed")
    resolved = AlarmOccurrence.objects.get(alarm=upgraded, occurrence_key="resolved")
    assert initial.deadline_at == trigger + timedelta(minutes=10)
    assert custom.deadline_at == trigger + timedelta(minutes=18)
    assert snoozed.deadline_at == trigger + timedelta(minutes=25)
    assert snoozed.snoozed_until == trigger + timedelta(minutes=15)
    assert initial.trigger_at == snoozed.trigger_at == trigger
    assert initial.cycle_date == snoozed.cycle_date == trigger.date()
    assert resolved.deadline_at == trigger + timedelta(minutes=10)

    snapshot = client.get(f"/v1/groups/{group.pk}/sync")
    assert snapshot.status_code == 200, snapshot.data
    snapshot_alarm = next(item["data"] for item in snapshot.data["items"] if item["kind"] == "alarm" and item["key"] == str(alarm.pk))
    snapshot_occurrence = next(item["data"] for item in snapshot.data["items"] if item["kind"] == "occurrence" and item["key"] == str(initial.pk))
    assert snapshot_alarm["timeout_minutes"] == 10
    assert snapshot_alarm["missed_restart_enabled"] is False
    assert parse_datetime(snapshot_occurrence["deadline_at"]) == trigger + timedelta(minutes=10)
    assert snapshot_occurrence["snoozed_at"] is None
    delta = client.get(f"/v1/groups/{group.pk}/sync", {"cursor": checkpoint})
    assert delta.status_code == 200, delta.data
    assert delta.data["mode"] == "delta"
    assert any(item["kind"] == "alarm" and item["data"]["timeout_minutes"] == 10 for item in delta.data["items"])
    assert any(item["kind"] == "occurrence" and parse_datetime(item["data"]["deadline_at"]) == trigger + timedelta(minutes=10) for item in delta.data["items"])
    assert "timeout_minutes" not in SyncVersion.objects.get(pk=old_alarm_version.pk).representation
    assert parse_datetime(SyncVersion.objects.get(pk=old_occurrence_version.pk).representation["deadline_at"]) == trigger + timedelta(minutes=10)
