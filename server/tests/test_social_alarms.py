import asyncio
import hashlib
from datetime import UTC, datetime, timedelta
from uuid import uuid4

from fastapi.testclient import TestClient

from jay_server.database import transaction
from jay_server.live import LiveChangeBroker
from jay_server.main import app
from jay_server.occurrences import process_due_alarm_occurrences
from jay_server.config import settings


settings.alarm_occurrence_monitor_enabled = False


def register_device(client: TestClient, name: str, secret: str) -> dict[str, str]:
    device_id = hashlib.sha256(f"identity:{name}".encode()).hexdigest()
    response = client.post(
        "/v1/devices/register",
        json={"id": device_id, "name": name, "token": secret, "time_zone": "UTC"},
    )
    assert response.status_code == 201
    return {
        "Authorization": f"Bearer {secret}",
        "X-Jay-Device-ID": device_id,
    }


def alarm_payload(group_id: str) -> dict:
    return {
        "group_id": group_id,
        "time": 19_800_000,
        "label": "Airport",
        "enabled": True,
        "days": [1, 3, 5],
        "vibrate": True,
        "start_date": 0,
        "repeat_interval": 1,
        "repeat_unit": "WEEK",
        "repeat_anchor": "DAY_OF_MONTH",
        "repeat_duration": None,
        "repeat_duration_unit": "DAY",
        "end_date": None,
        "end_occurrences": None,
        "snooze_enabled": True,
        "snooze_minutes": 10,
        "sound_enabled": True,
        "vibration_pattern": [1000, 1000],
        "vibration_pattern_name": "Default",
    }


def test_live_change_broker_routes_device_events() -> None:
    broker = LiveChangeBroker()
    events = broker.events("recipient")
    with asyncio.Runner() as runner:
        assert runner.run(anext(events)) == "event: sync\ndata: connected\n\n"
        broker.publish({"recipient"})
        assert runner.run(anext(events)) == "event: sync\ndata: changed\n\n"
        runner.run(events.aclose())


def test_group_alarm_lifecycle() -> None:
    with TestClient(app) as client:
        with transaction() as connection:
            connection.execute(
                "TRUNCATE changes, alarm_activity, alarm_deliveries, shared_alarms, "
                "group_invites, group_members, groups, devices CASCADE"
            )

        leader = register_device(client, "Quiet Mango", "leader-secret-that-is-long-enough")
        member = register_device(client, "Cozy Otter", "member-secret-that-is-long-enough")
        push_token = client.put(
            "/v1/device/push-token",
            headers=member,
            json={"token": "test-push-token"},
        )
        assert push_token.status_code == 204

        created_group = client.post(
            "/v1/groups",
            headers=leader,
            json={
                "name": "Airport friends",
                "alarm_permission": "everyone",
            },
        )
        assert created_group.status_code == 201
        group_id = created_group.json()["id"]

        invitation = client.post(
            f"/v1/groups/{group_id}/invites", headers=leader, json={}
        )
        assert invitation.status_code == 201
        expires_at = datetime.fromisoformat(invitation.json()["expires_at"])
        assert (
            timedelta(hours=23, minutes=59)
            < expires_at - datetime.now(UTC)
            <= timedelta(hours=24)
        )
        joined = client.post(
            "/v1/groups/join",
            headers=member,
            json={"token": invitation.json()["token"]},
        )
        assert joined.status_code == 200

        alarm = client.post(
            "/v1/alarms", headers=member, json=alarm_payload(group_id)
        )
        assert alarm.status_code == 201
        alarm_id = alarm.json()["id"]

        first_sync = client.get("/v1/sync", headers=leader)
        assert first_sync.status_code == 200
        assert first_sync.json()["alarms"][0]["label"] == "Airport"
        assert first_sync.json()["groups"][0]["notify_snoozed"] is True
        assert first_sync.json()["groups"][0]["notify_dismissed"] is True
        assert first_sync.json()["groups"][0]["notify_alarm_changes"] is True
        assert first_sync.json()["groups"][0]["notify_ignored"] is True
        alarm_change = next(
            item for item in first_sync.json()["changes"]
            if item["entity_type"] == "alarm" and item["action"] == "created"
        )
        assert alarm_change["entity_time"] == 19_800_000
        assert alarm_change["actor_name"] == "Cozy Otter"
        assert alarm_change["occurred_at"]
        joined_change = next(
            item for item in first_sync.json()["changes"] if item["action"] == "joined"
        )
        assert joined_change["group_id"] == group_id
        assert joined_change["subject_device_id"] == member["X-Jay-Device-ID"]
        assert joined_change["subject_name"] == "Cozy Otter"
        assert joined_change["action"] == "joined"
        assert joined_change["group_name"] == "Airport friends"
        assert joined_change["occurred_at"]
        assert {item["name"] for item in first_sync.json()["members"]} == {
            "Quiet Mango",
            "Cozy Otter",
        }

        snoozed = client.post(
            f"/v1/alarms/{alarm_id}/activity",
            headers=leader,
            json={
                "id": str(uuid4()),
                "alarm_revision": 1,
                "kind": "snoozed",
                "occurred_at": "2026-08-10T05:30:00Z",
            },
        )
        assert snoozed.status_code == 201
        activity_sync = client.get("/v1/sync", headers=member)
        outcome = next(
            item for item in activity_sync.json()["changes"] if item["action"] == "snoozed"
        )
        assert outcome["actor_name"] == "Quiet Mango"

        update = alarm_payload(group_id)
        update.pop("group_id")
        update["time"] = 18_000_000
        update["expected_revision"] = 1
        updated = client.put(f"/v1/alarms/{alarm_id}", headers=leader, json=update)
        assert updated.status_code == 200
        assert updated.json()["revision"] == 2

        stale = client.put(f"/v1/alarms/{alarm_id}", headers=member, json=update)
        assert stale.status_code == 409

        latest_sync = client.get(
            f"/v1/sync?since={first_sync.json()['cursor']}", headers=member
        )
        assert latest_sync.status_code == 200
        assert latest_sync.json()["alarms"][0]["time"] == 18_000_000
        edited_change = next(
            item for item in latest_sync.json()["changes"] if item["action"] == "edited"
        )
        assert edited_change["entity_time"] == 18_000_000
        assert edited_change["actor_name"] == "Quiet Mango"
        assert edited_change["occurred_at"]

        disabled_update = update | {"enabled": False, "expected_revision": 2}
        disabled = client.put(
            f"/v1/alarms/{alarm_id}", headers=member, json=disabled_update
        )
        assert disabled.status_code == 200
        assert disabled.json()["revision"] == 3
        disabled_sync = client.get(
            f"/v1/sync?since={latest_sync.json()['cursor']}", headers=leader
        )
        assert any(item["action"] == "disabled" for item in disabled_sync.json()["changes"])

        enabled_update = update | {"enabled": True, "expected_revision": 3}
        enabled = client.put(
            f"/v1/alarms/{alarm_id}", headers=leader, json=enabled_update
        )
        assert enabled.status_code == 200
        assert enabled.json()["revision"] == 4
        enabled_sync = client.get(
            f"/v1/sync?since={disabled_sync.json()['cursor']}", headers=member
        )
        assert any(item["action"] == "enabled" for item in enabled_sync.json()["changes"])

        deleted = client.request(
            "DELETE",
            f"/v1/alarms/{alarm_id}",
            headers=member,
            json={"expected_revision": 4},
        )
        assert deleted.status_code == 200
        assert deleted.json()["revision"] == 5
        deleted_sync = client.get(
            f"/v1/sync?since={enabled_sync.json()['cursor']}", headers=leader
        )
        assert deleted_sync.json()["alarms"][0]["deleted"] is True
        deleted_change = next(
            item for item in deleted_sync.json()["changes"] if item["action"] == "deleted"
        )
        assert deleted_change["entity_time"] == 18_000_000

        left = client.delete(f"/v1/groups/{group_id}/membership", headers=member)
        assert left.status_code == 204
        left_sync = client.get(
            f"/v1/sync?since={deleted_sync.json()['cursor']}", headers=leader
        )
        left_change = next(
            item for item in left_sync.json()["changes"] if item["action"] == "left"
        )
        assert left_change["group_id"] == group_id
        assert left_change["subject_device_id"] == member["X-Jay-Device-ID"]
        assert left_change["subject_name"] == "Cozy Otter"
        assert left_change["group_name"] == "Airport friends"
        assert left_change["occurred_at"]

        group_activity = client.get(f"/v1/groups/{group_id}/activity", headers=leader)
        assert group_activity.status_code == 200
        assert {item["action"] for item in group_activity.json()["items"]} == {
            "created", "joined", "invitation_created", "left"
        }
        assert all(
            item["entity_type"] in {"group", "membership", "administrative"}
            for item in group_activity.json()["items"]
        )
        first_group_activity_page = client.get(
            f"/v1/groups/{group_id}/activity?limit=1", headers=leader
        ).json()
        assert len(first_group_activity_page["items"]) == 1
        assert first_group_activity_page["next_before"] is not None
        second_group_activity_page = client.get(
            f"/v1/groups/{group_id}/activity?limit=1&before="
            f"{first_group_activity_page['next_before']}",
            headers=leader,
        ).json()
        assert second_group_activity_page["items"][0]["sequence"] < (
            first_group_activity_page["items"][0]["sequence"]
        )

        alarm_activity = client.get(f"/v1/alarms/{alarm_id}/activity", headers=leader)
        assert alarm_activity.status_code == 200
        assert {item["action"] for item in alarm_activity.json()["items"]} >= {
            "created", "snoozed", "edited", "disabled", "enabled", "deleted", "delivered"
        }


def test_any_group_leader_can_delete_group() -> None:
    with TestClient(app) as client:
        with transaction() as connection:
            connection.execute(
                "TRUNCATE changes, alarm_activity, alarm_occurrences, alarm_deliveries, shared_alarms, "
                "group_invites, group_members, groups, devices CASCADE"
            )

        creator = register_device(client, "Quiet Cedar", "creator-secret-that-is-long-enough")
        member = register_device(client, "Silver Fern", "member-secret-that-is-long-enough")
        group_id = client.post(
            "/v1/groups",
            headers=creator,
            json={"name": "House", "alarm_permission": "everyone"},
        ).json()["id"]
        invitation = client.post(
            f"/v1/groups/{group_id}/invites", headers=creator, json={}
        ).json()
        client.post(
            "/v1/groups/join",
            headers=member,
            json={"token": invitation["token"]},
        )
        alarm_id = client.post(
            "/v1/alarms", headers=member, json=alarm_payload(group_id)
        ).json()["id"]
        client.get("/v1/sync", headers=creator)

        assert client.delete(f"/v1/groups/{group_id}", headers=member).status_code == 403
        promoted = client.patch(
            f"/v1/groups/{group_id}/members/{member['X-Jay-Device-ID']}",
            headers=creator,
            json={"role": "leader"},
        )
        assert promoted.status_code == 200
        assert client.delete(f"/v1/groups/{group_id}", headers=member).status_code == 204
        assert client.get("/v1/sync", headers=creator).json()["groups"] == []
        with transaction() as connection:
            for table in (
                "groups",
                "group_members",
                "group_invites",
                "shared_alarms",
                "alarm_activity",
                "alarm_occurrences",
                "changes",
            ):
                assert connection.execute(
                    f"SELECT count(*) AS count FROM {table} WHERE "
                    f"{'id' if table == 'groups' else 'group_id'} = %s",
                    (group_id,),
                ).fetchone()["count"] == 0
            assert connection.execute(
                "SELECT count(*) AS count FROM alarm_deliveries WHERE alarm_id = %s",
                (alarm_id,),
            ).fetchone()["count"] == 0
            assert connection.execute(
                "SELECT count(*) AS count FROM devices"
            ).fetchone()["count"] == 2


def test_leader_only_group_rejects_member_alarm_changes() -> None:
    with TestClient(app) as client:
        with transaction() as connection:
            connection.execute(
                "TRUNCATE changes, alarm_activity, alarm_deliveries, shared_alarms, "
                "group_invites, group_members, groups, devices CASCADE"
            )

        leader = register_device(client, "Gentle Lantern", "leader-secret-that-is-long-enough")
        member = register_device(client, "Warm Pear", "member-secret-that-is-long-enough")
        group = client.post(
            "/v1/groups",
            headers=leader,
            json={"name": "Home", "alarm_permission": "leaders"},
        )
        group_id = group.json()["id"]
        invitation = client.post(
            f"/v1/groups/{group_id}/invites", headers=leader, json={}
        )
        client.post(
            "/v1/groups/join",
            headers=member,
            json={"token": invitation.json()["token"]},
        )

        forbidden = client.post(
            "/v1/alarms", headers=member, json=alarm_payload(group_id)
        )
        assert forbidden.status_code == 403

        promoted = client.patch(
            f"/v1/groups/{group_id}/members/{member['X-Jay-Device-ID']}",
            headers=leader,
            json={"role": "leader"},
        )
        assert promoted.status_code == 200
        allowed = client.post(
            "/v1/alarms", headers=member, json=alarm_payload(group_id)
        )
        assert allowed.status_code == 201


def test_member_preferences_roles_removal_and_ignored_correction() -> None:
    with TestClient(app) as client:
        with transaction() as connection:
            connection.execute(
                "TRUNCATE changes, alarm_activity, alarm_deliveries, shared_alarms, "
                "group_invites, group_members, groups, devices CASCADE"
            )

        leader = register_device(client, "Blue Lantern", "leader-secret-that-is-long-enough")
        member = register_device(client, "Silver Otter", "member-secret-that-is-long-enough")
        group = client.post(
            "/v1/groups",
            headers=leader,
            json={"name": "House", "alarm_permission": "everyone"},
        )
        group_id = group.json()["id"]
        invitation = client.post(
            f"/v1/groups/{group_id}/invites", headers=leader, json={}
        ).json()
        client.post(
            "/v1/groups/join",
            headers=member,
            json={"token": invitation["token"]},
        )

        preferences = client.patch(
            f"/v1/groups/{group_id}/notification-settings",
            headers=member,
            json={"notify_membership": False, "notify_administrative": False},
        )
        assert preferences.status_code == 200
        member_sync = client.get("/v1/sync", headers=member).json()
        assert member_sync["groups"][0]["notify_membership"] is False
        assert member_sync["groups"][0]["notify_administrative"] is False

        promoted = client.patch(
            f"/v1/groups/{group_id}/members/{member['X-Jay-Device-ID']}",
            headers=leader,
            json={"role": "leader"},
        )
        assert promoted.status_code == 200
        promoted_change = next(
            item for item in client.get("/v1/sync", headers=member).json()["changes"]
            if item["action"] == "promoted"
        )
        assert promoted_change["subject_device_id"] == member["X-Jay-Device-ID"]

        alarm = client.post(
            "/v1/alarms", headers=leader, json=alarm_payload(group_id)
        ).json()
        occurrence_id = "airport-2026-08-10"
        ignored_id = str(uuid4())
        ignored = client.post(
            f"/v1/alarms/{alarm['id']}/activity",
            headers=member,
            json={
                "id": ignored_id,
                "alarm_revision": 1,
                "kind": "ignored",
                "occurred_at": "2026-08-10T06:00:00Z",
                "occurrence_id": occurrence_id,
                "reason": "no_response",
            },
        )
        assert ignored.status_code == 201
        snoozed = client.post(
            f"/v1/alarms/{alarm['id']}/activity",
            headers=member,
            json={
                "id": str(uuid4()),
                "alarm_revision": 1,
                "kind": "snoozed",
                "occurred_at": "2026-08-10T05:59:00Z",
                "occurrence_id": occurrence_id,
            },
        )
        assert snoozed.status_code == 201
        dismissed = client.post(
            f"/v1/alarms/{alarm['id']}/activity",
            headers=member,
            json={
                "id": str(uuid4()),
                "alarm_revision": 1,
                "kind": "dismissed",
                "occurred_at": "2026-08-10T06:05:00Z",
                "occurrence_id": occurrence_id,
            },
        )
        assert dismissed.status_code == 201
        alarm_history = client.get(
            f"/v1/alarms/{alarm['id']}/activity", headers=leader
        ).json()["items"]
        assert {item["action"] for item in alarm_history} >= {
            "corrected", "snoozed", "dismissed"
        }

        removed = client.delete(
            f"/v1/groups/{group_id}/members/{member['X-Jay-Device-ID']}",
            headers=leader,
        )
        assert removed.status_code == 204
        removed_sync = client.get("/v1/sync", headers=member)
        assert removed_sync.status_code == 200
        assert removed_sync.json()["groups"] == []
        removed_change = next(
            item for item in removed_sync.json()["changes"] if item["action"] == "removed"
        )
        assert removed_change["recipient_device_id"] == member["X-Jay-Device-ID"]
        assert client.get(f"/v1/groups/{group_id}/activity", headers=member).status_code == 404


def test_server_records_ignored_when_a_member_never_reports() -> None:
    with TestClient(app) as client:
        with transaction() as connection:
            connection.execute(
                "TRUNCATE changes, alarm_activity, alarm_occurrences, alarm_deliveries, "
                "shared_alarms, group_invites, group_members, groups, devices CASCADE"
            )

        leader = register_device(client, "Amber Finch", "leader-secret-that-is-long-enough")
        member = register_device(client, "Still Harbor", "member-secret-that-is-long-enough")
        group_id = client.post(
            "/v1/groups",
            headers=leader,
            json={"name": "Morning", "alarm_permission": "everyone"},
        ).json()["id"]
        invitation = client.post(
            f"/v1/groups/{group_id}/invites", headers=leader, json={}
        ).json()
        client.post(
            "/v1/groups/join",
            headers=member,
            json={"token": invitation["token"]},
        )
        alarm = client.post(
            "/v1/alarms", headers=leader, json=alarm_payload(group_id)
        ).json()
        with transaction() as connection:
            expected = connection.execute(
                """
                UPDATE alarm_occurrences
                SET trigger_at = now() - interval '11 minutes',
                    deadline_at = now() - interval '1 minute'
                WHERE alarm_id = %s AND device_id = %s
                RETURNING occurrence_id
                """,
                (alarm["id"], member["X-Jay-Device-ID"]),
            ).fetchone()
        assert expected is not None

        process_due_alarm_occurrences()

        ignored = next(
            change
            for change in client.get("/v1/sync", headers=leader).json()["changes"]
            if change["action"] == "ignored"
            and change["subject_device_id"] == member["X-Jay-Device-ID"]
        )
        assert ignored["details"]["reason"] == "no_response"
        assert ignored["details"]["occurrence_id"] == expected["occurrence_id"]


def test_play_entitlement_is_bound_to_the_authenticated_device(monkeypatch) -> None:
    with TestClient(app) as client:
        with transaction() as connection:
            connection.execute(
                "TRUNCATE changes, alarm_activity, alarm_deliveries, shared_alarms, "
                "group_invites, group_members, groups, devices CASCADE"
            )
        device = register_device(
            client, "Bright Finch", "play-device-secret-that-is-long-enough"
        )
        monkeypatch.setattr(
            "jay_server.main.verify_play_entitlement",
            lambda integrity_token, device_id: integrity_token == "licensed-token"
            and device_id == device["X-Jay-Device-ID"],
        )

        entitled = client.post(
            "/v1/device/play-entitlement",
            headers=device,
            json={"integrity_token": "licensed-token"},
        )
        assert entitled.status_code == 200
        assert entitled.json()["shared_sound_upload"] is True
        assert entitled.json()["expires_at"] is not None
        with transaction() as connection:
            stored = connection.execute(
                "SELECT play_entitlement_expires_at FROM devices WHERE id = %s",
                (device["X-Jay-Device-ID"],),
            ).fetchone()
        assert stored["play_entitlement_expires_at"] is not None

        unlicensed = client.post(
            "/v1/device/play-entitlement",
            headers=device,
            json={"integrity_token": "unlicensed-token"},
        )
        assert unlicensed.status_code == 200
        assert unlicensed.json() == {
            "shared_sound_upload": False,
            "expires_at": None,
        }
        with transaction() as connection:
            stored = connection.execute(
                "SELECT play_entitlement_expires_at FROM devices WHERE id = %s",
                (device["X-Jay-Device-ID"],),
            ).fetchone()
        assert stored["play_entitlement_expires_at"] is None
