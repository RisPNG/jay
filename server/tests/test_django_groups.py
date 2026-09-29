from datetime import timedelta
from uuid import uuid4

import pytest
from django.utils import timezone

from jay_server.social.models import DeliveryWork, Group, GroupInvitation, GroupMembership, Identity, PushSubscription, SharedTimer, SyncVersion
from jay_server.social.worker import maintain_state, process_group_cleanup, process_identity_retirement


pytestmark = pytest.mark.django_db(transaction=True)


def test_offline_preferences_can_follow_an_offline_group_creation(client, group):
    member = GroupMembership.objects.get(group=group, identity_id="1" * 64)
    saved = group.saved_at + timedelta(seconds=1)
    response = client.put(f"/v1/groups/{group.pk}/notification-settings", {"membership_id": str(member.pk), "saved_at": saved.isoformat(), "notify_membership": False, "notify_administrative": False}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 200, response.data


def test_competing_leader_demotions_leave_one_leader(client, group, member):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier
    from django.db import connections
    from rest_framework.test import APIClient

    GroupMembership.objects.filter(group=group).update(role="leader")
    memberships = {item.identity_id: item.pk for item in GroupMembership.objects.filter(group=group)}
    gate = Barrier(2)
    def demote(identity_id):
        api = APIClient()
        secret = "secret" * 10 if identity_id == "1" * 64 else "another-secret" * 5
        api.credentials(HTTP_AUTHORIZATION="Bearer " + secret, HTTP_X_JAY_IDENTITY_ID=identity_id)
        try:
            gate.wait(timeout=5)
            return api.patch(f"/v1/groups/{group.pk}/members/{identity_id}", {"role": "member", "membership_id": str(memberships[identity_id]), "saved_at": timezone.now().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code
        finally:
            connections.close_all()
    with ThreadPoolExecutor(max_workers=2) as executor:
        statuses = list(executor.map(demote, ["1" * 64, "2" * 64]))
    assert sorted(statuses) == [200, 409]
    assert GroupMembership.objects.filter(group=group, role="leader", removed_at=None).count() == 1


def test_last_leader_demotion_rolls_back(client, group, member):
    membership = GroupMembership.objects.get(group=group, identity_id="1" * 64)
    before = SyncVersion.objects.count()
    response = client.patch(f"/v1/groups/{group.pk}/members/{membership.identity_id}", {"role": "member", "membership_id": str(membership.pk), "saved_at": timezone.now().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 409
    assert response.data["code"] == "last_leader"
    membership.refresh_from_db()
    assert membership.role == "leader"
    assert SyncVersion.objects.count() == before


def test_member_removal_replays_its_own_receipt(client, group, member):
    own = GroupMembership.objects.get(group=group, identity_id="1" * 64)
    key = str(uuid4())
    for _ in range(2):
        response = client.delete(f"/v1/groups/{group.pk}/members/{'2' * 64}", {"membership_id": str(own.pk)}, format="json", headers={"Idempotency-Key": key})
        assert response.status_code == 204, response.data
    assert member.get(f"/v1/groups/{group.pk}/sync").status_code == 404
    snapshot = member.get("/v1/sync")
    assert not any(row["kind"] == "membership" for row in snapshot.data["items"])


def test_old_generation_cannot_delete_after_rejoining(client, group, member):
    old = GroupMembership.objects.get(group=group, identity_id="2" * 64)
    assert member.delete(f"/v1/groups/{group.pk}/membership", {"membership_id": str(old.pk)}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 204
    new = GroupMembership.objects.create(group=group, identity=old.identity)
    assert new.pk != old.pk
    response = member.delete(f"/v1/groups/{group.pk}/membership", {"membership_id": str(old.pk)}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 403
    assert response.data["code"] == "membership_changed"
    assert GroupMembership.objects.get(pk=new.pk).removed_at is None


def test_invitation_can_join_multiple_members_and_rejoin_before_expiry(client, group, member):
    from rest_framework.test import APIClient

    own = GroupMembership.objects.get(group=group, identity_id="1" * 64)
    new_invite = client.post(f"/v1/groups/{group.pk}/invites", {"id": str(uuid4()), "membership_id": str(own.pk)}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert new_invite.status_code == 201, new_invite.data
    token = new_invite.data["token"]
    GroupInvitation.objects.filter(pk=new_invite.data["id"]).update(consumed_at=timezone.now(), consumed_by_id="2" * 64)
    previous = GroupMembership.objects.get(group=group, identity_id="2" * 64, removed_at=None)
    assert member.delete(f"/v1/groups/{group.pk}/membership", {"membership_id": str(previous.pk)}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 204

    key = str(uuid4())
    joined = member.post("/v1/groups/join", {"token": token}, format="json", headers={"Idempotency-Key": key})
    assert joined.status_code == 200, joined.data
    assert joined.data["id"] != str(previous.pk)
    replay = member.post("/v1/groups/join", {"token": token}, format="json", headers={"Idempotency-Key": key})
    assert replay.status_code == 200 and replay.data == joined.data
    already_member = member.post("/v1/groups/join", {"token": token}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert already_member.status_code == 200 and already_member.data == joined.data

    another = APIClient()
    identity_id, secret = "3" * 64, "third-secret" * 5
    registered = another.post("/v1/identities/register", {"id": identity_id, "name": "Charlie", "token": secret, "time_zone": "UTC"}, format="json")
    assert registered.status_code == 201, registered.data
    another.credentials(HTTP_AUTHORIZATION="Bearer " + secret, HTTP_X_JAY_IDENTITY_ID=identity_id)
    second_join = another.post("/v1/groups/join", {"token": token}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert second_join.status_code == 200, second_join.data
    assert second_join.data["id"] != joined.data["id"]
    assert GroupMembership.objects.filter(group=group, removed_at=None).count() == 3

    assert member.delete(f"/v1/groups/{group.pk}/membership", {"membership_id": joined.data["id"]}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 204
    rejoined = member.post("/v1/groups/join", {"token": token}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert rejoined.status_code == 200, rejoined.data
    assert rejoined.data["id"] not in {str(previous.pk), joined.data["id"]}


def test_expired_invitation_rejects_new_joins_but_replays_a_completed_join(client, group, member):
    own = GroupMembership.objects.get(group=group, identity_id="1" * 64)
    invitation = client.post(f"/v1/groups/{group.pk}/invites", {"id": str(uuid4()), "membership_id": str(own.pk)}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert invitation.status_code == 201, invitation.data
    token = invitation.data["token"]
    current = GroupMembership.objects.get(group=group, identity_id="2" * 64, removed_at=None)
    assert member.delete(f"/v1/groups/{group.pk}/membership", {"membership_id": str(current.pk)}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 204
    key = str(uuid4())
    joined = member.post("/v1/groups/join", {"token": token}, format="json", headers={"Idempotency-Key": key})
    assert joined.status_code == 200, joined.data

    GroupInvitation.objects.filter(pk=invitation.data["id"]).update(expires_at=timezone.now() - timedelta(seconds=1))
    replay = member.post("/v1/groups/join", {"token": token}, format="json", headers={"Idempotency-Key": key})
    assert replay.status_code == 200 and replay.data == joined.data
    current = GroupMembership.objects.get(pk=joined.data["id"])
    assert member.delete(f"/v1/groups/{group.pk}/membership", {"membership_id": str(current.pk)}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 204
    expired = member.post("/v1/groups/join", {"token": token}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert expired.status_code == 409 and expired.data["code"] == "invite_unavailable"
    assert not GroupMembership.objects.filter(group=group, identity_id="2" * 64, removed_at=None).exists()


def test_invitation_cannot_join_a_deleted_group(client, group, member):
    own = GroupMembership.objects.get(group=group, identity_id="1" * 64)
    invitation = client.post(f"/v1/groups/{group.pk}/invites", {"id": str(uuid4()), "membership_id": str(own.pk)}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert invitation.status_code == 201, invitation.data
    current = GroupMembership.objects.get(group=group, identity_id="2" * 64, removed_at=None)
    assert member.delete(f"/v1/groups/{group.pk}/membership", {"membership_id": str(current.pk)}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 204
    assert client.delete(f"/v1/groups/{group.pk}", {"membership_id": str(own.pk)}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 204

    joined = member.post("/v1/groups/join", {"token": invitation.data["token"]}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert joined.status_code == 409 and joined.data["code"] == "invite_unavailable"
    assert not GroupMembership.objects.filter(group=group, identity_id="2" * 64, removed_at=None).exists()


def test_any_leader_can_delete_and_cleanup_group(client, group, member):
    own = GroupMembership.objects.get(group=group, identity_id="1" * 64)
    other = GroupMembership.objects.get(group=group, identity_id="2" * 64)
    promoted = client.patch(f"/v1/groups/{group.pk}/members/{other.identity_id}", {"role": "leader", "membership_id": str(own.pk), "saved_at": timezone.now().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert promoted.status_code == 200
    assert member.delete(f"/v1/groups/{group.pk}", {"membership_id": str(other.pk)}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 204
    assert client.get(f"/v1/groups/{group.pk}/sync").status_code == 404
    work = DeliveryWork.objects.get(kind="delete_group")
    for _ in range(3):
        done = process_group_cleanup(work)
    assert done
    assert not GroupMembership.objects.filter(group=group, removed_at=None).exists()


def test_identity_retirement_removes_access_and_push_tokens(client, group):
    for token in ["phone-one", "phone-two"]:
        assert client.put("/v1/identity/push-token", {"token": token}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 204
    assert PushSubscription.objects.count() == 2
    assert client.delete("/v1/identity", headers={"Idempotency-Key": str(uuid4())}).status_code == 204
    assert not PushSubscription.objects.exists()
    assert client.get("/v1/sync").status_code == 401
    work = DeliveryWork.objects.get(kind="retire")
    assert not process_identity_retirement(work)
    assert process_identity_retirement(work)
    group.refresh_from_db()
    assert group.deleted_at is not None


def test_inactivity_and_timer_expiry_are_processed(client, group, timer_payload):
    assert client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    SharedTimer.objects.update(expires_at=timezone.now() - timedelta(minutes=16))
    maintain_state()
    assert SharedTimer.objects.get().deleted_at is not None
    Identity.objects.update(last_seen_at=timezone.now() - timedelta(days=121))
    maintain_state()
    assert Identity.objects.get().retired_at is not None
    assert DeliveryWork.objects.filter(kind="retire").count() == 1


def test_notification_preferences_are_private(client, group, member):
    own = GroupMembership.objects.get(group=group, identity_id="1" * 64)
    response = client.put(f"/v1/groups/{group.pk}/notification-settings", {"membership_id": str(own.pk), "saved_at": timezone.now().isoformat(), "notify_membership": False, "notify_administrative": False}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 200
    other = member.get(f"/v1/groups/{group.pk}/sync")
    assert all("notify_membership" not in row["data"] for row in other.data["items"])
