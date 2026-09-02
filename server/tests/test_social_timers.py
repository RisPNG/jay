import hashlib
from datetime import UTC, datetime
from uuid import uuid4

from fastapi.testclient import TestClient

from jay_server.database import transaction
from jay_server.main import app
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


def make_group(
    client: TestClient, leader: dict[str, str], member: dict[str, str], permission: str
) -> str:
    created = client.post(
        "/v1/groups",
        headers=leader,
        json={"name": "Kitchen crew", "alarm_permission": permission},
    )
    assert created.status_code == 201
    group_id = created.json()["id"]
    invitation = client.post(f"/v1/groups/{group_id}/invites", headers=leader, json={})
    assert invitation.status_code == 201
    joined = client.post(
        "/v1/groups/join",
        headers=member,
        json={"token": invitation.json()["token"]},
    )
    assert joined.status_code == 200
    return group_id


def timer_payload() -> dict:
    return {
        "label": "Pasta",
        "duration_seconds": 600,
        "increment_seconds": 60,
        "vibrate": False,
        "sound_enabled": True,
        "vibration_pattern": [0, 500, 200, 500],
        "vibration_pattern_name": "Heartbeat",
    }


def sync_timers(client: TestClient, headers: dict[str, str]) -> list[dict]:
    response = client.get("/v1/sync", headers=headers)
    assert response.status_code == 200
    return response.json()["timers"]


def test_shared_timer_lifecycle() -> None:
    with TestClient(app) as client:
        with transaction() as connection:
            connection.execute(
                "TRUNCATE changes, alarm_activity, alarm_deliveries, alarm_occurrences, "
                "shared_alarms, shared_timers, group_invites, group_members, groups, "
                "devices CASCADE"
            )

        leader = register_device(client, "Quiet Mango", "leader-secret-that-is-long-enough")
        member = register_device(client, "Cozy Otter", "member-secret-that-is-long-enough")
        group_id = make_group(client, leader, member, "leaders")

        started = client.post(
            f"/v1/groups/{group_id}/timers", headers=leader, json=timer_payload()
        )
        assert started.status_code == 201
        timer_id = started.json()["id"]

        timers = sync_timers(client, member)
        assert len(timers) == 1
        timer = timers[0]
        assert timer["id"] == timer_id
        assert timer["label"] == "Pasta"
        assert timer["duration_seconds"] == 600
        assert timer["increment_seconds"] == 60
        assert timer["started_by"] == leader["X-Jay-Device-ID"]
        assert timer["vibrate"] is False
        assert timer["sound_enabled"] is True
        assert timer["vibration_pattern"] == [0, 500, 200, 500]
        assert timer["vibration_pattern_name"] == "Heartbeat"
        expires_at = datetime.fromisoformat(timer["expires_at"])
        assert 540 < (expires_at - datetime.now(UTC)).total_seconds() <= 600

        adjusted = client.patch(
            f"/v1/timers/{timer_id}", headers=leader, json={"action": "add"}
        )
        assert adjusted.status_code == 200
        adjusted_expires = datetime.fromisoformat(adjusted.json()["expires_at"])
        assert 590 < (adjusted_expires - datetime.now(UTC)).total_seconds() <= 660

        reset = client.patch(
            f"/v1/timers/{timer_id}", headers=leader, json={"action": "reset"}
        )
        assert reset.status_code == 200
        reset_expires = datetime.fromisoformat(reset.json()["expires_at"])
        assert 540 < (reset_expires - datetime.now(UTC)).total_seconds() <= 600

        changes_before = client.get("/v1/sync", headers=leader).json()["changes"]
        timer_changes = [
            change for change in changes_before if change["entity_type"] == "timer"
        ]
        assert timer_changes == []

        cancelled = client.delete(f"/v1/timers/{timer_id}", headers=leader)
        assert cancelled.status_code == 204
        assert sync_timers(client, member) == []


def test_shared_timer_requires_editor() -> None:
    with TestClient(app) as client:
        with transaction() as connection:
            connection.execute(
                "TRUNCATE changes, alarm_activity, alarm_deliveries, alarm_occurrences, "
                "shared_alarms, shared_timers, group_invites, group_members, groups, "
                "devices CASCADE"
            )

        leader = register_device(client, "Quiet Mango", "leader-secret-that-is-long-enough")
        member = register_device(client, "Cozy Otter", "member-secret-that-is-long-enough")

        locked_group = make_group(client, leader, member, "leaders")
        refused = client.post(
            f"/v1/groups/{locked_group}/timers", headers=member, json=timer_payload()
        )
        assert refused.status_code == 403
        started = client.post(
            f"/v1/groups/{locked_group}/timers", headers=leader, json=timer_payload()
        )
        assert started.status_code == 201
        timer_id = started.json()["id"]
        refused_adjust = client.patch(
            f"/v1/timers/{timer_id}", headers=member, json={"action": "add"}
        )
        assert refused_adjust.status_code == 403
        refused_cancel = client.delete(f"/v1/timers/{timer_id}", headers=member)
        assert refused_cancel.status_code == 403

        open_group = make_group(client, leader, member, "everyone")
        allowed = client.post(
            f"/v1/groups/{open_group}/timers", headers=member, json=timer_payload()
        )
        assert allowed.status_code == 201
        member_timer = allowed.json()["id"]
        allowed_adjust = client.patch(
            f"/v1/timers/{member_timer}", headers=member, json={"action": "reset"}
        )
        assert allowed_adjust.status_code == 200
        allowed_cancel = client.delete(f"/v1/timers/{member_timer}", headers=member)
        assert allowed_cancel.status_code == 204


def test_shared_timer_lingering_after_expiry_is_swept() -> None:
    with TestClient(app) as client:
        with transaction() as connection:
            connection.execute(
                "TRUNCATE changes, alarm_activity, alarm_deliveries, alarm_occurrences, "
                "shared_alarms, shared_timers, group_invites, group_members, groups, "
                "devices CASCADE"
            )

        leader = register_device(client, "Quiet Mango", "leader-secret-that-is-long-enough")
        member = register_device(client, "Cozy Otter", "member-secret-that-is-long-enough")
        group_id = make_group(client, leader, member, "leaders")

        started = client.post(
            f"/v1/groups/{group_id}/timers", headers=leader, json=timer_payload()
        )
        assert started.status_code == 201

        stale_id = uuid4()
        with transaction() as connection:
            connection.execute(
                """
                INSERT INTO shared_timers (
                    id, group_id, label, duration_seconds, increment_seconds,
                    expires_at, started_by, sound_enabled, vibrate,
                    vibration_pattern, vibration_pattern_name
                ) VALUES (%s, %s, 'Stale', 60, 60, now() - interval '30 minutes', %s,
                          true, true, ARRAY[0, 1000], 'Default')
                """,
                (stale_id, group_id, leader["X-Jay-Device-ID"]),
            )

        timers = sync_timers(client, member)
        assert [timer["label"] for timer in timers] == ["Pasta"]
