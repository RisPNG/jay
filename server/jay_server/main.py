import hashlib
import secrets
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

from fastapi import Depends, FastAPI, HTTPException, Query, status
from psycopg.errors import UniqueViolation

from jay_server.auth import authenticated_device
from jay_server.config import settings
from jay_server.database import close_database_pool, open_database_pool, transaction
from jay_server.domain import (
    record_group_change,
    get_group_push_tokens,
    require_alarm_editor,
    require_group_leader,
    require_group_member,
)
from jay_server.schemas import (
    AlarmActivityCreate,
    DeviceRegistration,
    DeviceUpdate,
    GroupCreate,
    GroupUpdate,
    InviteCreate,
    InviteJoin,
    MemberUpdate,
    SharedAlarmCreate,
    SharedAlarmDelete,
    SharedAlarmUpdate,
    PushTokenUpdate,
)
from jay_server.push import send_group_sync


@asynccontextmanager
async def lifespan(_: FastAPI):
    open_database_pool()
    yield
    close_database_pool()


app = FastAPI(title="Jay Server", version="1", lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    with transaction() as connection:
        connection.execute("SELECT 1")
    return {"status": "ok"}


@app.post("/v1/devices/register", status_code=status.HTTP_201_CREATED)
def register_device(registration: DeviceRegistration) -> dict:
    token_hash = hashlib.sha256(registration.token.encode()).digest()
    with transaction() as connection:
        device = connection.execute(
            "SELECT token_hash FROM devices WHERE id = %s",
            (registration.id,),
        ).fetchone()
        if device is not None and not secrets.compare_digest(device["token_hash"], token_hash):
            raise HTTPException(status.HTTP_409_CONFLICT, "Device identity already registered")
        connection.execute(
            """
            INSERT INTO devices (id, name, token_hash)
            VALUES (%s, %s, %s)
            ON CONFLICT (id) DO UPDATE
            SET name = EXCLUDED.name, updated_at = now()
            """,
            (registration.id, registration.name, token_hash),
        )
    return {"id": registration.id, "name": registration.name}


@app.patch("/v1/device")
def update_device(
    update: DeviceUpdate, device: dict = Depends(authenticated_device)
) -> dict:
    with transaction() as connection:
        connection.execute(
            "UPDATE devices SET name = %s, updated_at = now() WHERE id = %s",
            (update.name, device["id"]),
        )
        group_ids = connection.execute(
            "SELECT group_id FROM group_members WHERE device_id = %s", (device["id"],)
        ).fetchall()
        for group in group_ids:
            record_group_change(connection, group["group_id"], "member", device["id"])
        push_tokens = [
            token
            for group in group_ids
            for token in get_group_push_tokens(connection, group["group_id"], device["id"])
        ]
    send_group_sync(push_tokens)
    return {"id": device["id"], "name": update.name}


@app.put("/v1/device/push-token", status_code=status.HTTP_204_NO_CONTENT)
def update_push_token(
    update: PushTokenUpdate, device: dict = Depends(authenticated_device)
) -> None:
    with transaction() as connection:
        connection.execute(
            "UPDATE devices SET push_token = NULL WHERE push_token = %s AND id != %s",
            (update.token, device["id"]),
        )
        connection.execute(
            "UPDATE devices SET push_token = %s, updated_at = now() WHERE id = %s",
            (update.token, device["id"]),
        )


@app.post("/v1/groups", status_code=status.HTTP_201_CREATED)
def create_group(group: GroupCreate, device: dict = Depends(authenticated_device)) -> dict:
    group_id = uuid4()
    with transaction() as connection:
        connection.execute(
            """
            INSERT INTO groups (
                id, name, alarm_permission, notify_snoozed, notify_dismissed, created_by
            ) VALUES (%s, %s, %s, %s, %s, %s)
            """,
            (
                group_id,
                group.name,
                group.alarm_permission.value,
                group.notify_snoozed,
                group.notify_dismissed,
                device["id"],
            ),
        )
        connection.execute(
            "INSERT INTO group_members (group_id, device_id, role) VALUES (%s, %s, 'leader')",
            (group_id, device["id"]),
        )
        record_group_change(connection, group_id, "group", str(group_id))
    return {"id": group_id}


@app.patch("/v1/groups/{group_id}")
def update_group(
    group_id: UUID,
    update: GroupUpdate,
    device: dict = Depends(authenticated_device),
) -> dict:
    with transaction() as connection:
        require_group_leader(connection, group_id, device["id"])
        connection.execute(
            """
            UPDATE groups
            SET name = %s, alarm_permission = %s, notify_snoozed = %s,
                notify_dismissed = %s, updated_at = now()
            WHERE id = %s
            """,
            (
                update.name,
                update.alarm_permission.value,
                update.notify_snoozed,
                update.notify_dismissed,
                group_id,
            ),
        )
        record_group_change(connection, group_id, "group", str(group_id))
        push_tokens = get_group_push_tokens(connection, group_id, device["id"])
    send_group_sync(push_tokens)
    return {"id": group_id}


@app.delete("/v1/groups/{group_id}", status_code=status.HTTP_204_NO_CONTENT)
def leave_group(group_id: UUID, device: dict = Depends(authenticated_device)) -> None:
    with transaction() as connection:
        membership = require_group_member(connection, group_id, device["id"])
        if membership["role"] == "leader":
            leader_count = connection.execute(
                "SELECT count(*) AS count FROM group_members WHERE group_id = %s AND role = 'leader'",
                (group_id,),
            ).fetchone()["count"]
            member_count = connection.execute(
                "SELECT count(*) AS count FROM group_members WHERE group_id = %s",
                (group_id,),
            ).fetchone()["count"]
            if leader_count == 1 and member_count > 1:
                raise HTTPException(
                    status.HTTP_409_CONFLICT,
                    "Promote another leader before leaving the group",
                )
        connection.execute(
            "DELETE FROM group_members WHERE group_id = %s AND device_id = %s",
            (group_id, device["id"]),
        )
        remaining = connection.execute(
            "SELECT count(*) AS count FROM group_members WHERE group_id = %s", (group_id,)
        ).fetchone()["count"]
        if remaining == 0:
            connection.execute("DELETE FROM groups WHERE id = %s", (group_id,))
            push_tokens = []
        else:
            record_group_change(connection, group_id, "membership", device["id"])
            push_tokens = get_group_push_tokens(connection, group_id, device["id"])
    send_group_sync(push_tokens)


@app.post("/v1/groups/{group_id}/invites", status_code=status.HTTP_201_CREATED)
def create_invite(
    group_id: UUID,
    invite: InviteCreate,
    device: dict = Depends(authenticated_device),
) -> dict:
    token = secrets.token_urlsafe(32)
    invite_id = uuid4()
    expires_at = datetime.now(UTC) + timedelta(
        hours=invite.expires_in_hours or settings.invite_lifetime_hours
    )
    with transaction() as connection:
        require_group_member(connection, group_id, device["id"])
        connection.execute(
            """
            INSERT INTO group_invites (id, group_id, token_hash, created_by, expires_at)
            VALUES (%s, %s, %s, %s, %s)
            """,
            (
                invite_id,
                group_id,
                hashlib.sha256(token.encode()).digest(),
                device["id"],
                expires_at,
            ),
        )
    return {
        "id": invite_id,
        "token": token,
        "expires_at": expires_at,
        "url": f"{settings.public_url.rstrip('/')}/join/{token}",
    }


@app.post("/v1/groups/join")
def join_group(join: InviteJoin, device: dict = Depends(authenticated_device)) -> dict:
    with transaction() as connection:
        invite = connection.execute(
            """
            SELECT id, group_id
            FROM group_invites
            WHERE token_hash = %s AND consumed_at IS NULL AND expires_at > now()
            FOR UPDATE
            """,
            (hashlib.sha256(join.token.encode()).digest(),),
        ).fetchone()
        if invite is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Invitation is invalid or expired")
        connection.execute(
            """
            INSERT INTO group_members (group_id, device_id, role)
            VALUES (%s, %s, 'member')
            ON CONFLICT DO NOTHING
            """,
            (invite["group_id"], device["id"]),
        )
        connection.execute(
            "UPDATE group_invites SET consumed_at = now(), consumed_by = %s WHERE id = %s",
            (device["id"], invite["id"]),
        )
        record_group_change(connection, invite["group_id"], "membership", device["id"])
        push_tokens = get_group_push_tokens(connection, invite["group_id"], device["id"])
    send_group_sync(push_tokens)
    return {"group_id": invite["group_id"]}


@app.patch("/v1/groups/{group_id}/members/{member_id}")
def update_member(
    group_id: UUID,
    member_id: str,
    update: MemberUpdate,
    device: dict = Depends(authenticated_device),
) -> dict:
    with transaction() as connection:
        require_group_leader(connection, group_id, device["id"])
        changed = connection.execute(
            """
            UPDATE group_members SET role = %s
            WHERE group_id = %s AND device_id = %s
            RETURNING device_id
            """,
            (update.role.value, group_id, member_id),
        ).fetchone()
        if changed is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Group member not found")
        if update.role.value == "member":
            leader_count = connection.execute(
                "SELECT count(*) AS count FROM group_members WHERE group_id = %s AND role = 'leader'",
                (group_id,),
            ).fetchone()["count"]
            if leader_count == 0:
                raise HTTPException(status.HTTP_409_CONFLICT, "A group must have a leader")
        record_group_change(connection, group_id, "member", member_id)
        push_tokens = get_group_push_tokens(connection, group_id, device["id"])
    send_group_sync(push_tokens)
    return {"device_id": member_id, "role": update.role}


@app.delete("/v1/groups/{group_id}/members/{member_id}", status_code=status.HTTP_204_NO_CONTENT)
def remove_member(
    group_id: UUID,
    member_id: str,
    device: dict = Depends(authenticated_device),
) -> None:
    with transaction() as connection:
        require_group_leader(connection, group_id, device["id"])
        if member_id == device["id"]:
            raise HTTPException(status.HTTP_409_CONFLICT, "Use the leave-group endpoint")
        removed_token = connection.execute(
            "SELECT push_token FROM devices WHERE id = %s", (member_id,)
        ).fetchone()
        removed = connection.execute(
            "DELETE FROM group_members WHERE group_id = %s AND device_id = %s RETURNING device_id",
            (group_id, member_id),
        ).fetchone()
        if removed is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Group member not found")
        record_group_change(connection, group_id, "membership", member_id)
        push_tokens = get_group_push_tokens(connection, group_id, device["id"])
        if removed_token is not None and removed_token["push_token"] is not None:
            push_tokens.append(removed_token["push_token"])
    send_group_sync(push_tokens)


@app.post("/v1/alarms", status_code=status.HTTP_201_CREATED)
def create_shared_alarm(
    alarm: SharedAlarmCreate, device: dict = Depends(authenticated_device)
) -> dict:
    alarm_id = uuid4()
    with transaction() as connection:
        require_alarm_editor(connection, alarm.group_id, device["id"])
        connection.execute(
            """
            INSERT INTO shared_alarms (
                id, group_id, time, label, enabled, days, vibrate, repeat,
                snooze_enabled, snooze_minutes, sound_enabled, vibration_pattern,
                vibration_pattern_name, created_by, updated_by
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                alarm_id,
                alarm.group_id,
                alarm.time,
                alarm.label,
                alarm.enabled,
                alarm.days,
                alarm.vibrate,
                alarm.repeat,
                alarm.snooze_enabled,
                alarm.snooze_minutes,
                alarm.sound_enabled,
                alarm.vibration_pattern,
                alarm.vibration_pattern_name,
                device["id"],
                device["id"],
            ),
        )
        record_group_change(connection, alarm.group_id, "alarm", str(alarm_id))
        push_tokens = get_group_push_tokens(connection, alarm.group_id, device["id"])
    send_group_sync(push_tokens)
    return {"id": alarm_id, "revision": 1}


@app.put("/v1/alarms/{alarm_id}")
def update_shared_alarm(
    alarm_id: UUID,
    update: SharedAlarmUpdate,
    device: dict = Depends(authenticated_device),
) -> dict:
    with transaction() as connection:
        alarm = connection.execute(
            "SELECT group_id FROM shared_alarms WHERE id = %s AND deleted = false",
            (alarm_id,),
        ).fetchone()
        if alarm is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Shared alarm not found")
        require_alarm_editor(connection, alarm["group_id"], device["id"])
        changed = connection.execute(
            """
            UPDATE shared_alarms
            SET revision = revision + 1, time = %s, label = %s, enabled = %s,
                days = %s, vibrate = %s, repeat = %s, snooze_enabled = %s,
                snooze_minutes = %s, sound_enabled = %s, vibration_pattern = %s,
                vibration_pattern_name = %s, updated_by = %s, updated_at = now()
            WHERE id = %s AND revision = %s AND deleted = false
            RETURNING revision
            """,
            (
                update.time,
                update.label,
                update.enabled,
                update.days,
                update.vibrate,
                update.repeat,
                update.snooze_enabled,
                update.snooze_minutes,
                update.sound_enabled,
                update.vibration_pattern,
                update.vibration_pattern_name,
                device["id"],
                alarm_id,
                update.expected_revision,
            ),
        ).fetchone()
        if changed is None:
            raise HTTPException(status.HTTP_409_CONFLICT, "Shared alarm has a newer revision")
        connection.execute("DELETE FROM alarm_activity WHERE alarm_id = %s", (alarm_id,))
        record_group_change(connection, alarm["group_id"], "alarm", str(alarm_id))
        push_tokens = get_group_push_tokens(connection, alarm["group_id"], device["id"])
    send_group_sync(push_tokens)
    return {"id": alarm_id, "revision": changed["revision"]}


@app.delete("/v1/alarms/{alarm_id}")
def delete_shared_alarm(
    alarm_id: UUID,
    deletion: SharedAlarmDelete,
    device: dict = Depends(authenticated_device),
) -> dict:
    with transaction() as connection:
        alarm = connection.execute(
            "SELECT group_id FROM shared_alarms WHERE id = %s AND deleted = false",
            (alarm_id,),
        ).fetchone()
        if alarm is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Shared alarm not found")
        require_alarm_editor(connection, alarm["group_id"], device["id"])
        changed = connection.execute(
            """
            UPDATE shared_alarms
            SET revision = revision + 1, deleted = true, updated_by = %s, updated_at = now()
            WHERE id = %s AND revision = %s AND deleted = false
            RETURNING revision
            """,
            (device["id"], alarm_id, deletion.expected_revision),
        ).fetchone()
        if changed is None:
            raise HTTPException(status.HTTP_409_CONFLICT, "Shared alarm has a newer revision")
        connection.execute("DELETE FROM alarm_activity WHERE alarm_id = %s", (alarm_id,))
        record_group_change(connection, alarm["group_id"], "alarm", str(alarm_id))
        push_tokens = get_group_push_tokens(connection, alarm["group_id"], device["id"])
    send_group_sync(push_tokens)
    return {"id": alarm_id, "revision": changed["revision"]}


@app.post("/v1/alarms/{alarm_id}/activity", status_code=status.HTTP_201_CREATED)
def record_alarm_activity(
    alarm_id: UUID,
    activity: AlarmActivityCreate,
    device: dict = Depends(authenticated_device),
) -> dict:
    activity_id = uuid4()
    with transaction() as connection:
        alarm = connection.execute(
            "SELECT group_id, revision FROM shared_alarms WHERE id = %s AND deleted = false",
            (alarm_id,),
        ).fetchone()
        if alarm is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Shared alarm not found")
        require_group_member(connection, alarm["group_id"], device["id"])
        if alarm["revision"] != activity.alarm_revision:
            raise HTTPException(status.HTTP_409_CONFLICT, "Activity belongs to an old alarm revision")
        connection.execute(
            """
            INSERT INTO alarm_activity (
                id, alarm_id, group_id, alarm_revision, device_id, kind, occurred_at
            ) VALUES (%s, %s, %s, %s, %s, %s, %s)
            """,
            (
                activity_id,
                alarm_id,
                alarm["group_id"],
                activity.alarm_revision,
                device["id"],
                activity.kind.value,
                activity.occurred_at,
            ),
        )
        record_group_change(connection, alarm["group_id"], "activity", str(activity_id))
        group = connection.execute(
            "SELECT notify_snoozed, notify_dismissed FROM groups WHERE id = %s",
            (alarm["group_id"],),
        ).fetchone()
        should_notify = (
            activity.kind.value == "snoozed" and group["notify_snoozed"]
        ) or (
            activity.kind.value == "dismissed" and group["notify_dismissed"]
        )
        push_tokens = get_group_push_tokens(
            connection, alarm["group_id"], device["id"]
        ) if should_notify else []
    send_group_sync(push_tokens)
    return {"id": activity_id}


@app.get("/v1/sync")
def synchronize(
    since: int = Query(default=0, ge=0),
    device: dict = Depends(authenticated_device),
) -> dict:
    with transaction() as connection:
        groups = connection.execute(
            """
            SELECT g.*, gm.role
            FROM groups g
            JOIN group_members gm ON gm.group_id = g.id
            WHERE gm.device_id = %s
            ORDER BY g.created_at
            """,
            (device["id"],),
        ).fetchall()
        group_ids = [group["id"] for group in groups]
        if not group_ids:
            return {"cursor": since, "groups": [], "members": [], "alarms": [], "activity": []}

        cursor = connection.execute(
            "SELECT coalesce(max(sequence), %s) AS cursor FROM changes WHERE group_id = ANY(%s)",
            (since, group_ids),
        ).fetchone()["cursor"]
        members = connection.execute(
            """
            SELECT gm.group_id, gm.device_id, gm.role, gm.joined_at, d.name
            FROM group_members gm
            JOIN devices d ON d.id = gm.device_id
            WHERE gm.group_id = ANY(%s)
            ORDER BY gm.joined_at
            """,
            (group_ids,),
        ).fetchall()
        alarms = connection.execute(
            "SELECT * FROM shared_alarms WHERE group_id = ANY(%s)", (group_ids,)
        ).fetchall()
        activity = connection.execute(
            """
            SELECT a.*, d.name AS device_name
            FROM alarm_activity a
            JOIN devices d ON d.id = a.device_id
            JOIN groups g ON g.id = a.group_id
            WHERE a.group_id = ANY(%s)
              AND ((a.kind = 'snoozed' AND g.notify_snoozed)
                OR (a.kind = 'dismissed' AND g.notify_dismissed))
              AND a.created_at > now() - interval '30 days'
            ORDER BY a.created_at DESC
            """,
            (group_ids,),
        ).fetchall()
        for alarm in alarms:
            if not alarm["deleted"]:
                connection.execute(
                    """
                    INSERT INTO alarm_deliveries (alarm_id, device_id, revision)
                    VALUES (%s, %s, %s)
                    ON CONFLICT (alarm_id, device_id) DO UPDATE
                    SET revision = EXCLUDED.revision, delivered_at = now()
                    """,
                    (alarm["id"], device["id"], alarm["revision"]),
                )
        deliveries = connection.execute(
            """
            SELECT ad.alarm_id, ad.device_id, ad.revision, ad.delivered_at
            FROM alarm_deliveries ad
            JOIN shared_alarms a ON a.id = ad.alarm_id
            WHERE a.group_id = ANY(%s)
            """,
            (group_ids,),
        ).fetchall()

    return {
        "cursor": cursor,
        "groups": groups,
        "members": members,
        "alarms": alarms,
        "activity": activity,
        "deliveries": deliveries,
    }
