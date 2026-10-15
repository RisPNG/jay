from datetime import timedelta
from uuid import uuid4

import pytest
from django.utils import timezone

from jay_server.social.models import GroupMembership, SharedTimer, TimerOccurrence


pytestmark = pytest.mark.django_db(transaction=True)


def test_rollout_capability_follows_server_policy(client, settings):
    assert client.get("/v1/identity/capabilities").data["advanced_group_timing"] is False
    settings.LEGACY_CLIENT_SUPPORT = False
    assert client.get("/v1/identity/capabilities").data["advanced_group_timing"] is True


def test_legacy_timer_create_and_edit_keep_existing_behavior(client, group, timer_payload):
    path = f"/v1/groups/{group.pk}/timers"
    created = client.post(path, timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    assert created.data["timeout_minutes"] == 10
    assert created.data["repeat_enabled"] is False
    timer_id = timer_payload.pop("id")
    timer_payload.update(label="Updated on 1.2.4", saved_at=timezone.now().isoformat())
    edited = client.put(f"/v1/timers/{timer_id}", timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert edited.status_code == 200, edited.data
    assert edited.data["timeout_minutes"] == 10 and edited.data["repeat_enabled"] is False


@pytest.mark.parametrize("field,value", [("repeat_enabled", True), ("repeat_count", 2), ("repeat_index", 1), ("timeout_minutes", 5), ("timeout_minutes", 25)])
def test_group_repeats_wait_until_legacy_support_is_disabled(client, group, timer_payload, field, value):
    response = client.post(f"/v1/groups/{group.pk}/timers", {**timer_payload, field: value}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 400, response.data
    assert response.data["code"] == "client_upgrade_required"
    assert not SharedTimer.objects.exists()


@pytest.mark.parametrize("modern_answer", [False, True])
def test_shared_timer_answers_remove_timer_for_both_versions(client, group, member, timer_payload, modern_answer):
    group.shared_answers = True
    group.save(update_fields=["shared_answers"])
    timer_payload["expires_at"] = (timezone.now() - timedelta(seconds=1)).isoformat()
    created = client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    timer = SharedTimer.objects.get(pk=timer_payload["id"])
    checkpoint = member.get(f"/v1/groups/{group.pk}/sync").data["next_cursor"]
    operation_id = str(uuid4())
    if modern_answer:
        answer = {
            "membership_id": timer_payload["membership_id"], "run_id": str(timer.run_id),
            "repeat_index": 0, "occurred_at": timezone.now().isoformat(),
        }
        response = client.post(f"/v1/timers/{timer.pk}/occurrence", answer, format="json", headers={"Idempotency-Key": operation_id})
        assert response.status_code == 200, response.data
        replay = client.post(f"/v1/timers/{timer.pk}/occurrence", answer, format="json", headers={"Idempotency-Key": operation_id})
        assert replay.status_code == 200 and replay.data == response.data
        other_membership = GroupMembership.objects.get(group=group, identity_id="2" * 64)
        other_answer = member.post(f"/v1/timers/{timer.pk}/occurrence", {
            "membership_id": str(other_membership.pk), "run_id": str(timer.run_id),
            "repeat_index": 0, "occurred_at": timezone.now().isoformat(),
        }, format="json", headers={"Idempotency-Key": str(uuid4())})
        assert other_answer.status_code == 200, other_answer.data
        assert TimerOccurrence.objects.filter(timer=timer).count() == 2
    else:
        response = client.delete(f"/v1/timers/{timer.pk}", {"membership_id": timer_payload["membership_id"]}, format="json", headers={"Idempotency-Key": operation_id})
        assert response.status_code == 204
    timer.refresh_from_db()
    assert timer.deleted_at is not None
    for api in [client, member]:
        snapshot = api.get(f"/v1/groups/{group.pk}/sync")
        assert not any(item["kind"] == "timer" for item in snapshot.data["items"])
    delta = member.get(f"/v1/groups/{group.pk}/sync", {"cursor": checkpoint})
    assert any(item["kind"] == "timer" and item["key"] == str(timer.pk) and item["action"] == "delete" for item in delta.data["items"])


def test_individual_timer_answer_does_not_stop_other_members(client, group, member, timer_payload):
    timer_payload["expires_at"] = (timezone.now() - timedelta(seconds=1)).isoformat()
    created = client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    timer = SharedTimer.objects.get(pk=timer_payload["id"])
    response = client.post(f"/v1/timers/{timer.pk}/occurrence", {
        "membership_id": timer_payload["membership_id"], "run_id": str(timer.run_id),
        "repeat_index": 0, "occurred_at": timezone.now().isoformat(),
    }, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 200, response.data
    snapshot = member.get(f"/v1/groups/{group.pk}/sync")
    assert any(item["kind"] == "timer" for item in snapshot.data["items"])
    assert not any(item["kind"] == "timer_occurrence" for item in snapshot.data["items"])
    timer.refresh_from_db()
    assert timer.deleted_at is None


def test_read_only_member_cannot_cancel_group_timer_through_occurrence_answer(client, group, member, timer_payload):
    group.shared_answers = True
    group.alarm_permission = "leaders"
    group.save(update_fields=["shared_answers", "alarm_permission"])
    timer_payload["expires_at"] = (timezone.now() - timedelta(seconds=1)).isoformat()
    created = client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    timer = SharedTimer.objects.get(pk=timer_payload["id"])
    membership = GroupMembership.objects.get(group=group, identity_id="2" * 64)
    response = member.post(f"/v1/timers/{timer.pk}/occurrence", {
        "membership_id": str(membership.pk), "run_id": str(timer.run_id),
        "repeat_index": 0, "occurred_at": timezone.now().isoformat(),
    }, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 200, response.data
    assert list(TimerOccurrence.objects.filter(timer=timer).values_list("identity_id", flat=True)) == ["2" * 64]
    timer.refresh_from_db()
    assert timer.deleted_at is None
    snapshot = client.get(f"/v1/groups/{group.pk}/sync")
    assert any(item["kind"] == "timer" for item in snapshot.data["items"])
    deleted = member.delete(f"/v1/timers/{timer.pk}", {"membership_id": str(membership.pk)}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert deleted.status_code == 403, deleted.data
    assert deleted.data["code"] == "editor_required"
