import hashlib

from fastapi.testclient import TestClient

from jay_server.database import transaction
from jay_server.main import app


def register_device(client: TestClient, name: str, secret: str) -> dict[str, str]:
    device_id = hashlib.sha256(f"identity:{name}".encode()).hexdigest()
    response = client.post(
        "/v1/devices/register",
        json={"id": device_id, "name": name, "token": secret},
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
        "repeat": True,
        "snooze_enabled": True,
        "snooze_minutes": 10,
        "sound_enabled": True,
        "vibration_pattern": [1000, 1000],
        "vibration_pattern_name": "Default",
    }


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
                "notify_snoozed": True,
                "notify_dismissed": True,
            },
        )
        assert created_group.status_code == 201
        group_id = created_group.json()["id"]

        invitation = client.post(
            f"/v1/groups/{group_id}/invites", headers=leader, json={}
        )
        assert invitation.status_code == 201
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
        assert {item["name"] for item in first_sync.json()["members"]} == {
            "Quiet Mango",
            "Cozy Otter",
        }

        snoozed = client.post(
            f"/v1/alarms/{alarm_id}/activity",
            headers=leader,
            json={
                "alarm_revision": 1,
                "kind": "snoozed",
                "occurred_at": "2026-08-10T05:30:00Z",
            },
        )
        assert snoozed.status_code == 201
        activity_sync = client.get("/v1/sync", headers=member)
        assert activity_sync.json()["activity"][0]["device_name"] == "Quiet Mango"
        assert activity_sync.json()["activity"][0]["kind"] == "snoozed"

        update = alarm_payload(group_id)
        update.pop("group_id")
        update["time"] = 18_000_000
        update["expected_revision"] = 1
        updated = client.put(f"/v1/alarms/{alarm_id}", headers=leader, json=update)
        assert updated.status_code == 200
        assert updated.json()["revision"] == 2

        stale = client.put(f"/v1/alarms/{alarm_id}", headers=member, json=update)
        assert stale.status_code == 409

        latest_sync = client.get("/v1/sync", headers=member)
        assert latest_sync.status_code == 200
        assert latest_sync.json()["alarms"][0]["time"] == 18_000_000
        assert latest_sync.json()["activity"] == []

        deleted = client.request(
            "DELETE",
            f"/v1/alarms/{alarm_id}",
            headers=member,
            json={"expected_revision": 2},
        )
        assert deleted.status_code == 200
        assert deleted.json()["revision"] == 3
        deleted_sync = client.get("/v1/sync", headers=leader)
        assert deleted_sync.json()["alarms"][0]["deleted"] is True


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
