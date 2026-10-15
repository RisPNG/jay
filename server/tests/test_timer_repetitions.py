from datetime import timedelta
from uuid import UUID, uuid4

import pytest
from django.db import connection, transaction
from django.db.migrations.executor import MigrationExecutor
from django.utils import timezone
from django.utils.dateparse import parse_datetime

from jay_server.social.models import GroupMembership, SharedTimer, SyncVersion, TimerOccurrence
from jay_server.social.synchronization import publish_changes
from jay_server.social.worker import maintain_state


pytestmark = pytest.mark.django_db(transaction=True)


@pytest.fixture(autouse=True)
def advanced_group_timing(settings):
    settings.LEGACY_CLIENT_SUPPORT = False


def create_timer(client, group, timer_payload, **settings):
    payload = {**timer_payload, "id": str(uuid4()), **settings}
    response = client.post(f"/v1/groups/{group.pk}/timers", payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 201, response.data
    return SharedTimer.objects.get(pk=response.data["id"]), payload


def answer(client, timer, index=0, identity_id="1" * 64, key=None, run_id=None, occurred_at=None):
    member = GroupMembership.objects.get(group=timer.group, identity_id=identity_id, removed_at=None)
    payload = {
        "membership_id": str(member.pk), "run_id": str(run_id or timer.run_id), "repeat_index": index,
        "occurred_at": (occurred_at or timezone.now()).isoformat(),
    }
    return client.post(f"/v1/timers/{timer.pk}/occurrence", payload, format="json", headers={"Idempotency-Key": key or str(uuid4())})


def test_old_client_timer_payload_receives_independent_defaults(client, group, timer_payload):
    timer, _ = create_timer(client, group, timer_payload)
    assert timer.timeout_minutes == 5
    assert not timer.repeat_enabled
    assert timer.repeat_count == timer.repeat_index == 0
    assert isinstance(timer.run_id, UUID)
    snapshot = client.get(f"/v1/groups/{group.pk}/sync")
    metadata = next(item["data"] for item in snapshot.data["items"] if item["kind"] == "timer")
    assert metadata["run_id"] == str(timer.run_id)
    assert metadata["timeout_minutes"] == 5


@pytest.mark.parametrize("field,value", [
    ("timeout_minutes", 0), ("timeout_minutes", 121), ("timeout_minutes", "10"),
    ("repeat_count", -1), ("repeat_count", 101), ("repeat_count", True),
    ("repeat_enabled", "true"), ("repeat_index", -1), ("repeat_index", "0"),
    ("unknown_timer_option", True),
])
def test_new_timer_fields_keep_strict_validation(client, group, timer_payload, field, value):
    response = client.post(f"/v1/groups/{group.pk}/timers", {**timer_payload, field: value}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 400, response.data
    assert not SharedTimer.objects.exists()


@pytest.mark.parametrize("enabled,count,index", [(False, 0, 1), (True, 2, 3)])
def test_base_index_must_fit_the_remaining_series(client, group, timer_payload, enabled, count, index):
    response = client.post(f"/v1/groups/{group.pk}/timers", {**timer_payload, "repeat_enabled": enabled, "repeat_count": count, "repeat_index": index}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 400, response.data


def test_positive_repeat_count_means_additional_copies(client, group, timer_payload):
    timer, _ = create_timer(client, group, timer_payload, duration_seconds=1, expires_at=(timezone.now() - timedelta(seconds=10)).isoformat(), repeat_enabled=True, repeat_count=2)
    for index in [0, 1, 2]:
        response = answer(client, timer, index)
        assert response.status_code == 200, response.data
        assert response.data["repeat_index"] == index
        occurrence = TimerOccurrence.objects.get(pk=response.data["id"])
        assert occurrence.trigger_at == timer.expires_at + timedelta(seconds=index)
        assert occurrence.deadline_at == occurrence.trigger_at + timedelta(minutes=5)
    assert answer(client, timer, 3).status_code == 400
    assert TimerOccurrence.objects.count() == 3
    timer.refresh_from_db()
    assert timer.deleted_at is None
    assert timer.repeat_count == 2


def test_zero_repeat_count_allows_elapsed_copies_but_not_future_overflow(client, group, timer_payload):
    timer, _ = create_timer(client, group, timer_payload, duration_seconds=1, expires_at=(timezone.now() - timedelta(seconds=300)).isoformat(), repeat_enabled=True)
    assert answer(client, timer, 200).status_code == 200
    assert answer(client, timer, 2147483647).status_code == 400
    assert TimerOccurrence.objects.count() == 1


def test_disabled_repeat_has_only_its_initial_occurrence(client, group, timer_payload):
    timer, _ = create_timer(client, group, timer_payload, duration_seconds=1, expires_at=(timezone.now() - timedelta(seconds=10)).isoformat(), repeat_count=100)
    assert answer(client, timer).status_code == 200
    assert answer(client, timer, 1).status_code == 400


def test_expiry_boundary_preserves_datetime_precision(client, group, timer_payload, monkeypatch):
    expiry = timezone.now().replace(microsecond=500001)
    timer, _ = create_timer(client, group, timer_payload, expires_at=expiry.isoformat())
    monkeypatch.setattr("jay_server.social.api.timers.timezone.now", lambda: expiry - timedelta(microseconds=1))
    assert answer(client, timer).status_code == 400
    monkeypatch.setattr("jay_server.social.api.timers.timezone.now", lambda: expiry)
    response = answer(client, timer)
    assert response.status_code == 200, response.data
    assert TimerOccurrence.objects.get(pk=response.data["id"]).trigger_at == expiry


def test_counting_adjustment_keeps_the_remaining_budget_and_changes_generation(client, group, timer_payload, monkeypatch):
    timer, payload = create_timer(client, group, timer_payload, expires_at=(timezone.now() - timedelta(minutes=14)).isoformat(), repeat_enabled=True, repeat_count=5)
    original_run = timer.run_id
    new_expiry = timezone.now() + timedelta(minutes=2)
    updated = {key: value for key, value in payload.items() if key != "id"}
    updated.update(run_id=str(uuid4()), repeat_index=3, expires_at=new_expiry.isoformat(), saved_at=timezone.now().isoformat())
    response = client.put(f"/v1/timers/{timer.pk}", updated, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 200, response.data
    timer.refresh_from_db()
    assert timer.run_id != original_run
    assert timer.repeat_index == 3
    assert timer.repeat_count == 5
    assert answer(client, timer, 2, run_id=original_run).status_code == 409
    assert answer(client, timer, 2).status_code == 400
    monkeypatch.setattr("jay_server.social.api.timers.timezone.now", lambda: new_expiry)
    assert answer(client, timer, 3).status_code == 200
    assert answer(client, timer, 4).status_code == 400


def test_explicit_reset_replenishes_budget_and_invalidates_previous_answers(client, group, timer_payload):
    timer, payload = create_timer(client, group, timer_payload, duration_seconds=1, expires_at=(timezone.now() - timedelta(seconds=10)).isoformat(), repeat_enabled=True, repeat_count=2, repeat_index=2)
    original_run = timer.run_id
    assert answer(client, timer, 2).status_code == 200
    updated = {key: value for key, value in payload.items() if key != "id"}
    updated.update(run_id=str(uuid4()), repeat_index=0, expires_at=(timezone.now() - timedelta(seconds=1)).isoformat(), saved_at=timezone.now().isoformat())
    response = client.put(f"/v1/timers/{timer.pk}", updated, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 200, response.data
    timer.refresh_from_db()
    assert timer.repeat_index == 0
    assert answer(client, timer, 0).status_code == 200
    assert answer(client, timer, 2, run_id=original_run).status_code == 409
    assert set(TimerOccurrence.objects.values_list("run_id", flat=True)) == {original_run, timer.run_id}


def test_old_client_adjustment_gets_a_new_run_without_new_fields(client, group, timer_payload):
    timer, payload = create_timer(client, group, timer_payload)
    original_run = timer.run_id
    updated = {key: value for key, value in payload.items() if key != "id"}
    updated.update(expires_at=(timer.expires_at + timedelta(seconds=60)).isoformat(), saved_at=timezone.now().isoformat())
    response = client.put(f"/v1/timers/{timer.pk}", updated, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 200, response.data
    timer.refresh_from_db()
    assert timer.run_id != original_run
    assert timer.timeout_minutes == 5
    assert timer.repeat_index == 0


def test_legacy_put_preserves_custom_policy_and_metadata_edit_keeps_its_run(client, group, timer_payload):
    timer, payload = create_timer(client, group, timer_payload, timeout_minutes=25, repeat_enabled=True, repeat_count=5, repeat_index=3)
    original_run = timer.run_id
    legacy = {key: value for key, value in payload.items() if key not in {"id", "timeout_minutes", "repeat_enabled", "repeat_count", "repeat_index"}}
    legacy.update(label="Updated legacy label", saved_at=timezone.now().isoformat())
    edited = client.put(f"/v1/timers/{timer.pk}", legacy, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert edited.status_code == 200, edited.data
    timer.refresh_from_db()
    assert timer.run_id == original_run
    assert timer.timeout_minutes == 25
    assert timer.repeat_enabled and timer.repeat_count == 5 and timer.repeat_index == 3
    legacy.update(expires_at=(timer.expires_at + timedelta(seconds=60)).isoformat(), saved_at=timezone.now().isoformat())
    adjusted = client.put(f"/v1/timers/{timer.pk}", legacy, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert adjusted.status_code == 200, adjusted.data
    timer.refresh_from_db()
    assert timer.run_id != original_run
    assert timer.timeout_minutes == 25
    assert timer.repeat_enabled and timer.repeat_count == 5 and timer.repeat_index == 3


def test_changed_schedule_cannot_reuse_its_current_run_id(client, group, timer_payload):
    timer, payload = create_timer(client, group, timer_payload)
    updated = {key: value for key, value in payload.items() if key != "id"}
    updated.update(run_id=str(timer.run_id), expires_at=(timer.expires_at + timedelta(seconds=1)).isoformat(), saved_at=timezone.now().isoformat())
    response = client.put(f"/v1/timers/{timer.pk}", updated, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 409
    assert response.data["code"] == "run_changed"
    timer.refresh_from_db()
    assert timer.expires_at.isoformat() == payload["expires_at"]


def test_answers_are_independent_and_receipts_and_occurrences_deduplicate(client, group, timer_payload, member):
    timer, _ = create_timer(client, group, timer_payload, expires_at=(timezone.now() - timedelta(seconds=1)).isoformat(), repeat_enabled=True)
    occurred = timezone.now()
    key = str(uuid4())
    response = answer(client, timer, key=key, occurred_at=occurred)
    assert response.status_code == 200, response.data
    group.scope.refresh_from_db()
    revision = group.scope.head_revision
    replay = answer(client, timer, key=key, occurred_at=occurred)
    assert replay.status_code == 200 and replay.json() == response.json()
    duplicate = answer(client, timer)
    assert duplicate.status_code == 200 and duplicate.json() == response.json()
    group.scope.refresh_from_db()
    assert group.scope.head_revision == revision
    assert TimerOccurrence.objects.count() == 1
    other = member.get(f"/v1/groups/{group.pk}/sync")
    assert not any(item["kind"] == "timer_occurrence" for item in other.data["items"])
    assert any(item["kind"] == "timer" for item in other.data["items"])
    assert answer(member, timer, identity_id="2" * 64).status_code == 200
    assert TimerOccurrence.objects.count() == 2


def test_shared_answer_resolves_only_the_same_index_for_current_members(client, group, timer_payload, member):
    group.shared_answers = True
    group.save(update_fields=["shared_answers"])
    timer, _ = create_timer(client, group, timer_payload, duration_seconds=1, expires_at=(timezone.now() - timedelta(seconds=10)).isoformat(), repeat_enabled=True, repeat_count=20)
    response = answer(client, timer, 3)
    assert response.status_code == 200, response.data
    assert set(TimerOccurrence.objects.values_list("identity_id", flat=True)) == {"1" * 64, "2" * 64}
    assert set(TimerOccurrence.objects.values_list("repeat_index", flat=True)) == {3}
    for api, identity_id in [(client, "1" * 64), (member, "2" * 64)]:
        snapshot = api.get(f"/v1/groups/{group.pk}/sync")
        answers = [item["data"] for item in snapshot.data["items"] if item["kind"] == "timer_occurrence"]
        assert len(answers) == 1 and answers[0]["identity_id"] == identity_id
        assert answers[0]["repeat_index"] == 3
        assert any(item["kind"] == "timer" for item in snapshot.data["items"])
    assert answer(member, timer, 4, identity_id="2" * 64).status_code == 200
    assert set(TimerOccurrence.objects.values_list("repeat_index", flat=True)) == {3, 4}
    timer.refresh_from_db()
    assert timer.deleted_at is None and timer.repeat_count == 20


def test_queued_answer_is_bound_to_its_membership_generation(client, group, timer_payload, member):
    timer, _ = create_timer(client, group, timer_payload, expires_at=(timezone.now() - timedelta(seconds=1)).isoformat(), repeat_enabled=True)
    previous = GroupMembership.objects.get(group=group, identity_id="2" * 64, removed_at=None)
    payload = {"membership_id": str(previous.pk), "run_id": str(timer.run_id), "repeat_index": 0, "occurred_at": timezone.now().isoformat()}
    key = str(uuid4())
    original = member.post(f"/v1/timers/{timer.pk}/occurrence", payload, format="json", headers={"Idempotency-Key": key})
    assert original.status_code == 200, original.data
    assert member.delete(f"/v1/groups/{group.pk}/membership", {"membership_id": str(previous.pk)}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 204
    current = GroupMembership.objects.create(group=group, identity=previous.identity)
    replay = member.post(f"/v1/timers/{timer.pk}/occurrence", payload, format="json", headers={"Idempotency-Key": key})
    assert replay.status_code == 403 and replay.data["code"] == "membership_changed"
    assert answer(member, timer, identity_id="2" * 64).status_code == 200
    assert set(TimerOccurrence.objects.values_list("membership_id", flat=True)) == {previous.pk, current.pk}


def test_cancel_deletes_the_series_and_prevents_later_answers(client, group, timer_payload):
    timer, _ = create_timer(client, group, timer_payload, expires_at=(timezone.now() - timedelta(seconds=1)).isoformat(), repeat_enabled=True)
    assert answer(client, timer).status_code == 200
    membership = GroupMembership.objects.get(group=group, identity_id="1" * 64, removed_at=None)
    canceled = client.delete(f"/v1/timers/{timer.pk}", {"membership_id": str(membership.pk)}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert canceled.status_code == 204
    assert answer(client, timer).status_code == 404
    snapshot = client.get(f"/v1/groups/{group.pk}/sync")
    assert not any(item["kind"] == "timer" for item in snapshot.data["items"])


def test_cleanup_uses_final_expiry_remaining_budget_and_long_timeout(client, group, timer_payload):
    now = timezone.now()
    future, _ = create_timer(client, group, timer_payload, expires_at=(now - timedelta(minutes=20)).isoformat(), repeat_enabled=True, repeat_count=10)
    final, _ = create_timer(client, group, timer_payload, expires_at=(now - timedelta(minutes=26)).isoformat(), repeat_enabled=True, repeat_count=5, repeat_index=3)
    long_ring, _ = create_timer(client, group, timer_payload, expires_at=(now - timedelta(minutes=16)).isoformat(), timeout_minutes=20)
    indefinite, _ = create_timer(client, group, timer_payload, expires_at=(now - timedelta(days=1)).isoformat(), repeat_enabled=True)
    maintain_state()
    for timer in [future, final, long_ring, indefinite]:
        timer.refresh_from_db()
    assert final.deleted_at is not None
    assert all(timer.deleted_at is None for timer in [future, long_ring, indefinite])


def test_snapshot_and_storage_bound_resolved_timer_answer_history(client, group, timer_payload, settings):
    timer, _ = create_timer(client, group, timer_payload, duration_seconds=1, expires_at=(timezone.now() - timedelta(days=40)).isoformat(), repeat_enabled=True)
    response = answer(client, timer, 1)
    assert response.status_code == 200, response.data
    occurrence = TimerOccurrence.objects.get(pk=response.data["id"])
    occurrence.resolved_at = timezone.now() - timedelta(days=3)
    occurrence.save(update_fields=["resolved_at"])
    with transaction.atomic():
        publish_changes(group.scope_id, [("timer_occurrence", str(occurrence.pk), "upsert", {**response.data, "resolved_at": occurrence.resolved_at.isoformat()}, occurrence.identity_id)], group=group)
    snapshot = client.get(f"/v1/groups/{group.pk}/sync")
    assert not any(item["kind"] == "timer_occurrence" for item in snapshot.data["items"])
    settings.SYNC_RETENTION_DAYS = 30
    TimerOccurrence.objects.filter(pk=occurrence.pk).update(resolved_at=timezone.now() - timedelta(days=31))
    SyncVersion.objects.filter(kind="timer_occurrence").update(created_at=timezone.now() - timedelta(days=31))
    maintain_state()
    assert not TimerOccurrence.objects.exists()
    assert not SyncVersion.objects.filter(kind="timer_occurrence").exists()
    timer.refresh_from_db()
    assert timer.deleted_at is None


def test_timer_upgrade_preserves_initial_run_lineage_and_later_reset_uses_a_new_run(client, group, timer_payload):
    before = [("social", "0009_alarm_restart")]
    executor = MigrationExecutor(connection)
    after = executor.loader.graph.leaf_nodes()
    executor.migrate(before)
    try:
        apps = executor.loader.project_state(before).apps
        timers = apps.get_model("social", "SharedTimer")
        scope = apps.get_model("social", "SyncScope").objects.get(pk=group.scope_id)
        versions = apps.get_model("social", "SyncVersion")
        legacy = []
        for label, expiry in [("Stopped while counting", timezone.now() + timedelta(minutes=20)), ("Stopped after expiry", timezone.now() - timedelta(minutes=1))]:
            timer = timers.objects.create(group_id=group.pk, label=label, duration_seconds=300, increment_seconds=60, expires_at=expiry, vibrate=True, vibration_pattern=[0, 100], vibration_pattern_name="Default", saved_at=timezone.now() - timedelta(minutes=2))
            scope.head_revision += 1
            scope.save(update_fields=["head_revision"])
            representation = {"id": str(timer.pk), "group_id": str(group.pk), "label": timer.label, "label_color": timer.label_color, "duration_seconds": timer.duration_seconds, "increment_seconds": timer.increment_seconds, "expires_at": timer.expires_at.isoformat(), "vibrate": timer.vibrate, "vibration_pattern": timer.vibration_pattern, "vibration_pattern_name": timer.vibration_pattern_name, "sound_mode": timer.sound_mode, "sound_id": None, "saved_at": timer.saved_at.isoformat(), "save_id": str(timer.save_id)}
            version = versions.objects.create(scope_id=scope.pk, revision=scope.head_revision, ordinal=0, kind="timer", key=str(timer.pk), action="upsert", representation=representation)
            legacy.append((timer, version.pk, representation))
        checkpoint = client.get(f"/v1/groups/{group.pk}/sync").data["next_cursor"]
    finally:
        MigrationExecutor(connection).migrate(after)
    snapshot = client.get(f"/v1/groups/{group.pk}/sync")
    assert snapshot.status_code == 200, snapshot.data
    metadata = {item["key"]: item["data"] for item in snapshot.data["items"] if item["kind"] == "timer"}
    delta = client.get(f"/v1/groups/{group.pk}/sync", {"cursor": checkpoint})
    assert delta.status_code == 200, delta.data
    assert delta.data["mode"] == "delta"
    for old, version_id, representation in legacy:
        upgraded = SharedTimer.objects.get(pk=old.pk)
        assert upgraded.run_id == old.pk
        assert upgraded.expires_at == old.expires_at
        assert upgraded.saved_at == old.saved_at and upgraded.save_id == old.save_id
        assert upgraded.timeout_minutes == 10
        assert not upgraded.repeat_enabled
        assert upgraded.repeat_count == upgraded.repeat_index == 0
        assert metadata[str(old.pk)]["run_id"] == str(old.pk)
        assert parse_datetime(metadata[str(old.pk)]["expires_at"]) == old.expires_at
        assert parse_datetime(metadata[str(old.pk)]["saved_at"]) == old.saved_at
        assert any(item["kind"] == "timer" and item["key"] == str(old.pk) and item["data"]["run_id"] == str(old.pk) for item in delta.data["items"])
        assert SyncVersion.objects.get(pk=version_id).representation == representation
    original = SharedTimer.objects.get(pk=legacy[0][0].pk)
    reset = {key: value for key, value in timer_payload.items() if key != "id"}
    reset.update(expires_at=(timezone.now() + timedelta(minutes=5)).isoformat(), saved_at=timezone.now().isoformat())
    response = client.put(f"/v1/timers/{original.pk}", reset, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 200, response.data
    original.refresh_from_db()
    assert original.run_id != original.pk
    assert answer(client, original, run_id=original.pk).status_code == 409
    created, _ = create_timer(client, group, timer_payload)
    assert created.run_id != created.pk


def test_timer_timeout_default_upgrade_preserves_saved_policies_and_run_progress(client, group, timer_payload):
    before = [("social", "0010_timer_repetitions")]
    executor = MigrationExecutor(connection)
    after = executor.loader.graph.leaf_nodes()
    executor.migrate(before)
    existing = []
    try:
        apps = executor.loader.project_state(before).apps
        timers = apps.get_model("social", "SharedTimer")
        for label, policy in [("Previous default", {}), ("Explicit ten minutes", {"timeout_minutes": 10}), ("Custom timeout", {"timeout_minutes": 25})]:
            timer = timers.objects.create(group_id=group.pk, label=label, duration_seconds=300, increment_seconds=60, expires_at=timezone.now() + timedelta(minutes=20), repeat_enabled=True, repeat_count=5, repeat_index=2, vibrate=True, vibration_pattern=[0, 100], vibration_pattern_name="Default", **policy)
            existing.append(timer)
    finally:
        MigrationExecutor(connection).migrate(after)
    for previous in existing:
        upgraded = SharedTimer.objects.get(pk=previous.pk)
        assert upgraded.timeout_minutes == previous.timeout_minutes
        assert upgraded.run_id == previous.run_id
        assert upgraded.repeat_enabled and upgraded.repeat_count == 5 and upgraded.repeat_index == 2
        assert upgraded.expires_at == previous.expires_at
        assert upgraded.saved_at == previous.saved_at and upgraded.save_id == previous.save_id
    default, _ = create_timer(client, group, timer_payload)
    explicit, _ = create_timer(client, group, timer_payload, timeout_minutes=10)
    assert default.timeout_minutes == 5
    assert explicit.timeout_minutes == 10
