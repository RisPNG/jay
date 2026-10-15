from datetime import timedelta
from uuid import uuid4

import pytest
from django.utils import timezone

from jay_server.social.models import GroupMembership, SharedTimer, TimerOccurrence


pytestmark = pytest.mark.django_db(transaction=True)


def test_advanced_group_timing_is_available_without_configuration(client):
    assert client.get("/v1/identity/capabilities").data["advanced_group_timing"] is True


@pytest.mark.parametrize("protocol", [None, "2"])
def test_legacy_timer_create_and_edit_keep_existing_behavior(client, group, timer_payload, protocol):
    headers = {"Idempotency-Key": str(uuid4())}
    if protocol is not None:
        headers["X-Jay-Protocol"] = protocol
    created = client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers=headers)
    assert created.status_code == 201, created.data
    assert created.data["timeout_minutes"] == 10
    assert created.data["repeat_enabled"] is False
    timer_id = timer_payload.pop("id")
    timer_payload.update(label="Updated on 1.2.4", saved_at=timezone.now().isoformat())
    edited = client.put(f"/v1/timers/{timer_id}", timer_payload, format="json", headers={**headers, "Idempotency-Key": str(uuid4())})
    assert edited.status_code == 200, edited.data
    assert edited.data["timeout_minutes"] == 10 and edited.data["repeat_enabled"] is False


@pytest.mark.parametrize("timeout", [5, 25])
def test_new_timer_features_are_available_in_mixed_groups(client, group, member, timer_payload, timeout):
    response = client.post(f"/v1/groups/{group.pk}/timers", {
        **timer_payload, "timeout_minutes": timeout, "repeat_enabled": True, "repeat_count": 2,
    }, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "2"})
    assert response.status_code == 201, response.data
    assert response.data["timeout_minutes"] == timeout
    assert response.data["repeat_enabled"] is True and response.data["repeat_count"] == 2
    snapshot = member.get(f"/v1/groups/{group.pk}/sync")
    assert any(item["kind"] == "timer" and item["key"] == timer_payload["id"] for item in snapshot.data["items"])


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
        response = client.post(f"/v1/timers/{timer.pk}/occurrence", answer, format="json", headers={"Idempotency-Key": operation_id, "X-Jay-Protocol": "2"})
        assert response.status_code == 200, response.data
        replay = client.post(f"/v1/timers/{timer.pk}/occurrence", answer, format="json", headers={"Idempotency-Key": operation_id, "X-Jay-Protocol": "2"})
        assert replay.status_code == 200 and replay.data == response.data
        other_membership = GroupMembership.objects.get(group=group, identity_id="2" * 64)
        other_answer = member.post(f"/v1/timers/{timer.pk}/occurrence", {
            "membership_id": str(other_membership.pk), "run_id": str(timer.run_id),
            "repeat_index": 0, "occurred_at": timezone.now().isoformat(),
        }, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "2"})
        assert other_answer.status_code == 200, other_answer.data
        assert TimerOccurrence.objects.filter(timer=timer).count() == 2
    else:
        response = client.delete(f"/v1/timers/{timer.pk}", {"membership_id": timer_payload["membership_id"]}, format="json", headers={"Idempotency-Key": operation_id})
        assert response.status_code == 204
    for key in [operation_id if not modern_answer else str(uuid4()), str(uuid4())]:
        replay = client.delete(f"/v1/timers/{timer.pk}", {"membership_id": timer_payload["membership_id"]}, format="json", headers={"Idempotency-Key": key})
        assert replay.status_code == 204, replay.data
    timer.refresh_from_db()
    assert timer.deleted_at is not None
    for api in [client, member]:
        snapshot = api.get(f"/v1/groups/{group.pk}/sync")
        assert not any(item["kind"] == "timer" for item in snapshot.data["items"])
    delta = member.get(f"/v1/groups/{group.pk}/sync", {"cursor": checkpoint})
    assert any(item["kind"] == "timer" and item["key"] == str(timer.pk) and item["action"] == "delete" for item in delta.data["items"])


@pytest.mark.parametrize("protocol", [None, "2"])
def test_legacy_repeat_dismissal_resolves_only_the_base_copy(client, group, member, timer_payload, protocol):
    group.shared_answers = True
    group.save(update_fields=["shared_answers"])
    created = client.post(f"/v1/groups/{group.pk}/timers", {
        **timer_payload, "repeat_enabled": True, "repeat_count": 5, "repeat_index": 2,
        "expires_at": (timezone.now() - timedelta(minutes=16)).isoformat(),
    }, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "2"})
    assert created.status_code == 201, created.data
    timer = SharedTimer.objects.get(pk=timer_payload["id"])
    headers = {"Idempotency-Key": str(uuid4())}
    if protocol is not None:
        headers["X-Jay-Protocol"] = protocol
    body = {"membership_id": timer_payload["membership_id"]}
    for key in [headers["Idempotency-Key"], headers["Idempotency-Key"], str(uuid4())]:
        dismissed = client.delete(f"/v1/timers/{timer.pk}", body, format="json", headers={**headers, "Idempotency-Key": key})
        assert dismissed.status_code == 204, dismissed.data
    timer.refresh_from_db()
    assert timer.deleted_at is None and timer.repeat_index == 2
    assert set(TimerOccurrence.objects.filter(timer=timer).values_list("repeat_index", flat=True)) == {2}
    assert set(TimerOccurrence.objects.filter(timer=timer).values_list("identity_id", flat=True)) == {"1" * 64, "2" * 64}
    assert any(item["kind"] == "timer" for item in member.get(f"/v1/groups/{group.pk}/sync").data["items"])
    final = client.post(f"/v1/timers/{timer.pk}/occurrence", {
        "membership_id": timer_payload["membership_id"], "run_id": str(timer.run_id),
        "repeat_index": 5, "occurred_at": timezone.now().isoformat(),
    }, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "2"})
    assert final.status_code == 200, final.data
    timer.refresh_from_db()
    assert timer.deleted_at is not None
    assert set(TimerOccurrence.objects.filter(timer=timer).values_list("repeat_index", flat=True)) == {2, 5}
    assert not any(item["kind"] == "timer" for item in member.get(f"/v1/groups/{group.pk}/sync").data["items"])


@pytest.mark.parametrize("protocol", [None, "2"])
def test_legacy_body_cannot_cancel_a_future_repeat_series(client, group, timer_payload, protocol):
    created = client.post(f"/v1/groups/{group.pk}/timers", {
        **timer_payload, "repeat_enabled": True, "repeat_count": 2,
    }, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    headers = {"Idempotency-Key": str(uuid4())}
    if protocol is not None:
        headers["X-Jay-Protocol"] = protocol
    response = client.delete(f"/v1/timers/{timer_payload['id']}", {"membership_id": timer_payload["membership_id"]}, format="json", headers=headers)
    assert response.status_code == 400 and response.data["code"] == "client_upgrade_required"
    assert SharedTimer.objects.get(pk=timer_payload["id"]).deleted_at is None
    assert not TimerOccurrence.objects.exists()


def test_modern_cancellation_binds_to_the_timer_run(client, group, timer_payload):
    created = client.post(f"/v1/groups/{group.pk}/timers", {
        **timer_payload, "repeat_enabled": True, "repeat_count": 2,
    }, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    timer = SharedTimer.objects.get(pk=timer_payload["id"])
    for run_id, status in [(str(uuid4()), 409), (str(timer.run_id), 204)]:
        response = client.delete(f"/v1/timers/{timer.pk}", {
            "membership_id": timer_payload["membership_id"], "run_id": run_id,
        }, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "2"})
        assert response.status_code == status, response.data
        if status == 409:
            assert response.data["code"] == "run_changed"
    timer.refresh_from_db()
    assert timer.deleted_at is not None
    assert not TimerOccurrence.objects.exists()
    response = client.post(f"/v1/timers/{timer.pk}/occurrence", {
        "membership_id": timer_payload["membership_id"], "run_id": str(timer.run_id),
        "repeat_index": 0, "occurred_at": timezone.now().isoformat(),
    }, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 404


def test_individual_timer_answer_does_not_stop_other_members(client, group, member, timer_payload):
    timer_payload["expires_at"] = (timezone.now() - timedelta(seconds=1)).isoformat()
    created = client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    timer = SharedTimer.objects.get(pk=timer_payload["id"])
    response = client.post(f"/v1/timers/{timer.pk}/occurrence", {
        "membership_id": timer_payload["membership_id"], "run_id": str(timer.run_id),
        "repeat_index": 0, "occurred_at": timezone.now().isoformat(),
    }, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "2"})
    assert response.status_code == 200, response.data
    snapshot = member.get(f"/v1/groups/{group.pk}/sync")
    assert any(item["kind"] == "timer" for item in snapshot.data["items"])
    assert not any(item["kind"] == "timer_occurrence" for item in snapshot.data["items"])
    timer.refresh_from_db()
    assert timer.deleted_at is None


def test_shared_answer_is_available_to_members_but_series_cancellation_requires_an_editor(client, group, member, timer_payload):
    group.shared_answers = True
    group.alarm_permission = "leaders"
    group.save(update_fields=["shared_answers", "alarm_permission"])
    timer_payload["expires_at"] = (timezone.now() - timedelta(seconds=1)).isoformat()
    created = client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    timer = SharedTimer.objects.get(pk=timer_payload["id"])
    membership = GroupMembership.objects.get(group=group, identity_id="2" * 64)
    deleted = member.delete(f"/v1/timers/{timer.pk}", {"membership_id": str(membership.pk), "run_id": str(timer.run_id)}, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "2"})
    assert deleted.status_code == 403 and deleted.data["code"] == "editor_required"
    response = member.post(f"/v1/timers/{timer.pk}/occurrence", {
        "membership_id": str(membership.pk), "run_id": str(timer.run_id),
        "repeat_index": 0, "occurred_at": timezone.now().isoformat(),
    }, format="json", headers={"Idempotency-Key": str(uuid4()), "X-Jay-Protocol": "2"})
    assert response.status_code == 200, response.data
    assert set(TimerOccurrence.objects.filter(timer=timer).values_list("identity_id", flat=True)) == {"1" * 64, "2" * 64}
    timer.refresh_from_db()
    assert timer.deleted_at is not None
