import hashlib
import json
import secrets
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import Depends, FastAPI, HTTPException, Query, status
from psycopg.errors import UniqueViolation
from starlette.responses import StreamingResponse

from jay_server.auth import authenticated_device
from jay_server.config import settings
from jay_server.database import close_database_pool, open_database_pool, transaction
from jay_server.domain import (
    record_group_change,
    get_group_push_tokens,
    remove_device,
    require_alarm_editor,
    require_group_leader,
    require_group_member,
    require_ready_shared_sound,
    require_shared_sound_upload,
    sweep_shared_timers,
)
from jay_server.schemas import (
    AlarmActivityCreate,
    AlarmOccurrenceSchedule,
    DeviceRegistration,
    DeviceUpdate,
    GroupCreate,
    GroupUpdate,
    InviteCreate,
    InviteJoin,
    MemberNotificationUpdate,
    MemberUpdate,
    PlayEntitlementVerification,
    SharedAlarmCreate,
    SharedAlarmDelete,
    SharedAlarmUpdate,
    SharedTimerAction,
    SharedTimerCreate,
    SharedTimerUpdate,
    SharedSoundMode,
    SharedSoundUploadCreate,
    PushTokenUpdate,
)
from jay_server.push import send_group_sync
from jay_server.live import live_changes
from jay_server.play_integrity import verify_play_entitlement
from jay_server.occurrences import (
    alarm_occurrence_monitor,
    evaluate_alarm_cycle,
    schedule_alarm_occurrences,
)
from jay_server.object_storage import (
    create_sound_download,
    create_sound_upload,
    validate_sound_upload,
)


@asynccontextmanager
async def lifespan(_: FastAPI):
    open_database_pool()
    await live_changes.start()
    if settings.alarm_occurrence_monitor_enabled:
        await alarm_occurrence_monitor.start()
    try:
        yield
    finally:
        if settings.alarm_occurrence_monitor_enabled:
            await alarm_occurrence_monitor.stop()
        await live_changes.stop()
        close_database_pool()


app = FastAPI(title="Jay Server", version="1", lifespan=lifespan)

@app.get("/health")
def health() -> dict:
    with transaction() as connection:
        connection.execute("SELECT 1")
    return {"status": "ok"}


@app.get("/v1/events")
def stream_events(device: dict = Depends(authenticated_device)) -> StreamingResponse:
    return StreamingResponse(
        live_changes.events(device["id"]),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.post("/v1/devices/register", status_code=status.HTTP_201_CREATED)
def register_device(registration: DeviceRegistration) -> dict:
    if registration.time_zone is not None:
        try:
            ZoneInfo(registration.time_zone)
        except ZoneInfoNotFoundError:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "Unknown time zone")
    token_hash = hashlib.sha256(registration.token.encode()).digest()
    with transaction() as connection:
        retired = connection.execute(
            "SELECT 1 FROM retired_devices WHERE id = %s", (registration.id,)
        ).fetchone()
        if retired is not None:
            raise HTTPException(
                status.HTTP_409_CONFLICT, "Device identity was removed; generate a new one"
            )
        device = connection.execute(
            "SELECT token_hash, name, time_zone FROM devices WHERE id = %s",
            (registration.id,),
        ).fetchone()
        if device is not None and not secrets.compare_digest(device["token_hash"], token_hash):
            raise HTTPException(status.HTTP_409_CONFLICT, "Device identity already registered")
        connection.execute(
            """
            INSERT INTO devices (id, name, token_hash, time_zone)
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (id) DO UPDATE
            SET name = EXCLUDED.name,
                time_zone = coalesce(EXCLUDED.time_zone, devices.time_zone),
                updated_at = now()
            """,
            (registration.id, registration.name, token_hash, registration.time_zone),
        )
        if (
            device is not None
            and registration.time_zone is not None
            and device["time_zone"] != registration.time_zone
        ):
            for alarm in connection.execute(
                """
                SELECT alarm.id, groups.alarm_time_basis
                FROM shared_alarms alarm
                JOIN group_members gm ON gm.group_id = alarm.group_id
                JOIN groups ON groups.id = alarm.group_id
                WHERE gm.device_id = %s AND alarm.enabled AND NOT alarm.deleted
                """,
                (registration.id,),
            ).fetchall():
                if alarm["alarm_time_basis"] != "member_local":
                    continue
                connection.execute(
                    """
                    UPDATE shared_alarms
                    SET inactive_cycle_streak = 0, last_evaluated_cycle_date = NULL
                    WHERE id = %s
                    """,
                    (alarm["id"],),
                )
                connection.execute(
                    """
                    UPDATE alarm_occurrences
                    SET status = 'canceled', resolved_at = now()
                    WHERE alarm_id = %s AND device_id = %s AND status = 'pending'
                    """,
                    (alarm["id"], registration.id),
                )
                schedule_alarm_occurrences(connection, alarm["id"], registration.id)
    return {"id": registration.id, "name": registration.name}


@app.patch("/v1/device")
def update_device(
    update: DeviceUpdate, device: dict = Depends(authenticated_device)
) -> dict:
    with transaction() as connection:
        previous_name = connection.execute(
            "SELECT name FROM devices WHERE id = %s", (device["id"],)
        ).fetchone()["name"]
        connection.execute(
            "UPDATE devices SET name = %s, updated_at = now() WHERE id = %s",
            (update.name, device["id"]),
        )
        group_ids = connection.execute(
            "SELECT group_id FROM group_members WHERE device_id = %s", (device["id"],)
        ).fetchall()
        if previous_name != update.name:
            for group in group_ids:
                record_group_change(
                    connection,
                    group["group_id"],
                    "membership",
                    device["id"],
                    device["id"],
                    "renamed",
                    update.name,
                    subject_device_id=device["id"],
                    details={"previous_name": previous_name, "name": update.name},
                )
        push_tokens = [
            token
            for group in group_ids
            for token in get_group_push_tokens(
                connection, group["group_id"], device["id"], "membership"
            )
        ]
    send_group_sync(push_tokens)
    return {"id": device["id"], "name": update.name}


@app.put("/v1/device/push-token", status_code=status.HTTP_204_NO_CONTENT)
def update_push_token(
    update: PushTokenUpdate, device: dict = Depends(authenticated_device)
) -> None:
    with transaction() as connection:
        connection.execute(
            """
            INSERT INTO device_push_tokens (token, device_id)
            VALUES (%s, %s)
            ON CONFLICT (token) DO UPDATE
            SET device_id = EXCLUDED.device_id, updated_at = now()
            """,
            (update.token, device["id"]),
        )


@app.post("/v1/device/play-entitlement")
def update_play_entitlement(
    verification: PlayEntitlementVerification,
    device: dict = Depends(authenticated_device),
) -> dict:
    entitled = verify_play_entitlement(verification.integrity_token, device["id"])
    verified_at = datetime.now(UTC)
    expires_at = verified_at + timedelta(hours=settings.play_entitlement_lifetime_hours)
    with transaction() as connection:
        connection.execute(
            """
            UPDATE devices
            SET play_entitlement_verified_at = %s,
                play_entitlement_expires_at = %s,
                updated_at = now()
            WHERE id = %s
            """,
            (verified_at, expires_at if entitled else None, device["id"]),
        )
    return {
        "shared_sound_upload": entitled,
        "expires_at": expires_at if entitled else None,
    }


@app.delete("/v1/device", status_code=status.HTTP_204_NO_CONTENT)
def delete_device(device: dict = Depends(authenticated_device)) -> None:
    with transaction() as connection:
        push_tokens = remove_device(connection, device["id"])
    send_group_sync(push_tokens)


@app.post("/v1/groups/{group_id}/sounds/uploads", status_code=status.HTTP_201_CREATED)
def begin_shared_sound_upload(
    group_id: UUID,
    upload: SharedSoundUploadCreate,
    device: dict = Depends(authenticated_device),
) -> dict:
    sound_id = uuid4()
    object_key = f"sounds/{group_id}/{sound_id}.flac"
    with transaction() as connection:
        require_alarm_editor(connection, group_id, device["id"])
        require_shared_sound_upload(connection, device["id"])
        connection.execute(
            """
            INSERT INTO shared_sounds (
                id, group_id, object_key, uploaded_by, title, sha256,
                byte_length, duration_ms
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                sound_id,
                group_id,
                object_key,
                device["id"],
                upload.title,
                upload.sha256,
                upload.byte_length,
                upload.duration_ms,
            ),
        )
    signed = create_sound_upload(object_key, upload.sha256, upload.byte_length)
    return {"id": sound_id, **signed}


@app.post("/v1/sounds/{sound_id}/complete")
def complete_shared_sound_upload(
    sound_id: UUID,
    device: dict = Depends(authenticated_device),
) -> dict:
    with transaction() as connection:
        sound = connection.execute(
            "SELECT * FROM shared_sounds WHERE id = %s",
            (sound_id,),
        ).fetchone()
        if sound is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Shared sound not found")
        require_alarm_editor(connection, sound["group_id"], device["id"])
        require_shared_sound_upload(connection, device["id"])
        if sound["uploaded_by"] != device["id"]:
            raise HTTPException(status.HTTP_403_FORBIDDEN, "Only the uploader may finish this upload")
    if sound["status"] != "ready":
        validate_sound_upload(
            sound["object_key"],
            sound["sha256"],
            sound["byte_length"],
            sound["duration_ms"],
        )
        with transaction() as connection:
            completed = connection.execute(
                """
                UPDATE shared_sounds
                SET status = 'ready', ready_at = now()
                WHERE id = %s AND status = 'pending'
                RETURNING id
                """,
                (sound_id,),
            ).fetchone()
            if completed is None:
                current = connection.execute(
                    "SELECT status FROM shared_sounds WHERE id = %s",
                    (sound_id,),
                ).fetchone()
                if current is None or current["status"] != "ready":
                    raise HTTPException(
                        status.HTTP_409_CONFLICT,
                        "Shared sound upload is no longer pending",
                    )
    return {"id": sound_id}


@app.get("/v1/sounds/{sound_id}/download")
def get_shared_sound_download(
    sound_id: UUID,
    device: dict = Depends(authenticated_device),
) -> dict:
    with transaction() as connection:
        sound = connection.execute(
            "SELECT * FROM shared_sounds WHERE id = %s AND status = 'ready'",
            (sound_id,),
        ).fetchone()
        if sound is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Shared sound not found")
        require_group_member(connection, sound["group_id"], device["id"])
    return {
        "url": create_sound_download(sound["object_key"]),
        "sha256": sound["sha256"],
        "byte_length": sound["byte_length"],
    }


@app.post("/v1/groups", status_code=status.HTTP_201_CREATED)
def create_group(group: GroupCreate, device: dict = Depends(authenticated_device)) -> dict:
    try:
        ZoneInfo(group.alarm_time_zone)
    except ZoneInfoNotFoundError:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "Unknown time zone")
    group_id = uuid4()
    with transaction() as connection:
        connection.execute(
            """
            INSERT INTO groups (
                id, name, alarm_permission, notify_alarm_changes, notify_snoozed,
                notify_dismissed, notify_ignored, alarm_time_basis,
                alarm_time_zone, shared_answers, created_by
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                group_id,
                group.name,
                group.alarm_permission.value,
                group.notify_alarm_changes,
                group.notify_snoozed,
                group.notify_dismissed,
                group.notify_ignored,
                group.alarm_time_basis.value,
                group.alarm_time_zone,
                group.shared_answers,
                device["id"],
            ),
        )
        connection.execute(
            "INSERT INTO group_members (group_id, device_id, role) VALUES (%s, %s, 'leader')",
            (group_id, device["id"]),
        )
        record_group_change(
            connection,
            group_id,
            "group",
            str(group_id),
            device["id"],
            "created",
            group.name,
        )
    return {"id": group_id}


@app.patch("/v1/groups/{group_id}")
def update_group(
    group_id: UUID,
    update: GroupUpdate,
    device: dict = Depends(authenticated_device),
) -> dict:
    try:
        ZoneInfo(update.alarm_time_zone)
    except ZoneInfoNotFoundError:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "Unknown time zone")
    with transaction() as connection:
        previous = require_group_leader(connection, group_id, device["id"])
        alarm_time_basis = (
            update.alarm_time_basis.value
            if "alarm_time_basis" in update.model_fields_set
            else previous["alarm_time_basis"]
        )
        alarm_time_zone = (
            update.alarm_time_zone
            if "alarm_time_zone" in update.model_fields_set
            else previous["alarm_time_zone"]
        )
        shared_answers = (
            update.shared_answers
            if "shared_answers" in update.model_fields_set
            else previous["shared_answers"]
        )
        connection.execute(
            """
            UPDATE groups
            SET name = %s, alarm_permission = %s, notify_alarm_changes = %s,
                notify_snoozed = %s, notify_dismissed = %s, notify_ignored = %s,
                alarm_time_basis = %s, alarm_time_zone = %s, shared_answers = %s,
                updated_at = now()
            WHERE id = %s
            """,
            (
                update.name,
                update.alarm_permission.value,
                update.notify_alarm_changes,
                update.notify_snoozed,
                update.notify_dismissed,
                update.notify_ignored,
                alarm_time_basis,
                alarm_time_zone,
                shared_answers,
                group_id,
            ),
        )
        timing_changed = (
            previous["alarm_time_basis"] != alarm_time_basis
            or previous["alarm_time_zone"] != alarm_time_zone
        )
        if timing_changed:
            alarms = connection.execute(
                """
                UPDATE shared_alarms
                SET revision = revision + 1, inactive_cycle_streak = 0,
                    last_evaluated_cycle_date = NULL, updated_by = %s,
                    updated_at = now()
                WHERE group_id = %s AND NOT deleted
                RETURNING id
                """,
                (device["id"], group_id),
            ).fetchall()
            for alarm in alarms:
                schedule_alarm_occurrences(connection, alarm["id"])
        record_group_change(
            connection,
            group_id,
            "group",
            str(group_id),
            device["id"],
            "updated",
            update.name,
            details={
                "previous_name": previous["name"],
                "name": update.name,
                "previous_alarm_permission": previous["alarm_permission"],
                "alarm_permission": update.alarm_permission.value,
                "previous_notify_alarm_changes": previous["notify_alarm_changes"],
                "notify_alarm_changes": update.notify_alarm_changes,
                "previous_notify_snoozed": previous["notify_snoozed"],
                "notify_snoozed": update.notify_snoozed,
                "previous_notify_dismissed": previous["notify_dismissed"],
                "notify_dismissed": update.notify_dismissed,
                "previous_notify_ignored": previous["notify_ignored"],
                "notify_ignored": update.notify_ignored,
                "previous_alarm_time_basis": previous["alarm_time_basis"],
                "alarm_time_basis": alarm_time_basis,
                "previous_alarm_time_zone": previous["alarm_time_zone"],
                "alarm_time_zone": alarm_time_zone,
                "previous_shared_answers": previous["shared_answers"],
                "shared_answers": shared_answers,
            },
        )
        push_tokens = get_group_push_tokens(
            connection, group_id, device["id"], "administrative"
        )
    send_group_sync(push_tokens)
    return {"id": group_id}


@app.delete("/v1/groups/{group_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_group(group_id: UUID, device: dict = Depends(authenticated_device)) -> None:
    with transaction() as connection:
        require_group_leader(connection, group_id, device["id"])
        push_tokens = get_group_push_tokens(connection, group_id, device["id"])
        connection.execute("DELETE FROM groups WHERE id = %s", (group_id,))
    send_group_sync(push_tokens)


@app.delete(
    "/v1/groups/{group_id}/membership",
    status_code=status.HTTP_204_NO_CONTENT,
)
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
        connection.execute(
            """
            UPDATE shared_alarms
            SET inactive_cycle_streak = 0, last_evaluated_cycle_date = NULL
            WHERE group_id = %s AND NOT deleted
            """,
            (group_id,),
        )
        connection.execute(
            """
            UPDATE alarm_occurrences
            SET status = 'canceled', resolved_at = now()
            WHERE group_id = %s AND device_id = %s AND status = 'pending'
            """,
            (group_id, device["id"]),
        )
        remaining = connection.execute(
            "SELECT count(*) AS count FROM group_members WHERE group_id = %s", (group_id,)
        ).fetchone()["count"]
        if remaining == 0:
            connection.execute("DELETE FROM groups WHERE id = %s", (group_id,))
            push_tokens = []
        else:
            record_group_change(
                connection,
                group_id,
                "membership",
                device["id"],
                device["id"],
                "left",
                device["name"],
                subject_device_id=device["id"],
            )
            push_tokens = get_group_push_tokens(
                connection, group_id, device["id"], "membership"
            )
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
        record_group_change(
            connection,
            group_id,
            "administrative",
            str(invite_id),
            device["id"],
            "invitation_created",
            details={"expires_at": expires_at.isoformat()},
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
        joined = connection.execute(
            """
            INSERT INTO group_members (group_id, device_id, role)
            VALUES (%s, %s, 'member')
            ON CONFLICT DO NOTHING
            RETURNING device_id
            """,
            (invite["group_id"], device["id"]),
        ).fetchone()
        connection.execute(
            "UPDATE group_invites SET consumed_at = now(), consumed_by = %s WHERE id = %s",
            (device["id"], invite["id"]),
        )
        if joined is None:
            push_tokens = []
        else:
            connection.execute(
                """
                UPDATE shared_alarms
                SET inactive_cycle_streak = 0, last_evaluated_cycle_date = NULL
                WHERE group_id = %s AND NOT deleted
                """,
                (invite["group_id"],),
            )
            record_group_change(
                connection,
                invite["group_id"],
                "membership",
                device["id"],
                device["id"],
                "joined",
                device["name"],
                subject_device_id=device["id"],
            )
            push_tokens = get_group_push_tokens(
                connection, invite["group_id"], device["id"], "membership"
            )
            for alarm in connection.execute(
                """
                SELECT id FROM shared_alarms
                WHERE group_id = %s AND enabled AND NOT deleted
                """,
                (invite["group_id"],),
            ).fetchall():
                schedule_alarm_occurrences(connection, alarm["id"], device["id"])
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
        member = connection.execute(
            """
            SELECT gm.role, d.name
            FROM group_members gm
            JOIN devices d ON d.id = gm.device_id
            WHERE gm.group_id = %s AND gm.device_id = %s
            """,
            (group_id, member_id),
        ).fetchone()
        if member is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Group member not found")
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
        if member["role"] != update.role.value:
            record_group_change(
                connection,
                group_id,
                "administrative",
                member_id,
                device["id"],
                "promoted" if update.role.value == "leader" else "demoted",
                member["name"],
                subject_device_id=member_id,
                details={"role": update.role.value, "previous_role": member["role"]},
            )
        push_tokens = get_group_push_tokens(
            connection, group_id, device["id"], "administrative"
        )
        if member_id != device["id"]:
            push_tokens.extend(
                row["token"]
                for row in connection.execute(
                    "SELECT token FROM device_push_tokens WHERE device_id = %s",
                    (member_id,),
                ).fetchall()
                if row["token"] not in push_tokens
            )
    send_group_sync(push_tokens)
    return {"device_id": member_id, "role": update.role}


@app.patch("/v1/groups/{group_id}/notification-settings")
def update_member_notification_settings(
    group_id: UUID,
    update: MemberNotificationUpdate,
    device: dict = Depends(authenticated_device),
) -> dict:
    with transaction() as connection:
        require_group_member(connection, group_id, device["id"])
        connection.execute(
            """
            UPDATE group_members
            SET notify_membership = %s, notify_administrative = %s
            WHERE group_id = %s AND device_id = %s
            """,
            (
                update.notify_membership,
                update.notify_administrative,
                group_id,
                device["id"],
            ),
        )
    return {
        "notify_membership": update.notify_membership,
        "notify_administrative": update.notify_administrative,
    }


@app.delete("/v1/groups/{group_id}/members/{member_id}", status_code=status.HTTP_204_NO_CONTENT)
def remove_member(
    group_id: UUID,
    member_id: str,
    device: dict = Depends(authenticated_device),
) -> None:
    with transaction() as connection:
        require_group_leader(connection, group_id, device["id"])
        if member_id == device["id"]:
            raise HTTPException(
                status.HTTP_409_CONFLICT,
                "Use Leave group to remove yourself",
            )
        removed_device = connection.execute(
            "SELECT name FROM devices WHERE id = %s", (member_id,)
        ).fetchone()
        removed = connection.execute(
            "DELETE FROM group_members WHERE group_id = %s AND device_id = %s RETURNING device_id",
            (group_id, member_id),
        ).fetchone()
        if removed is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Group member not found")
        record_group_change(
            connection,
            group_id,
            "membership",
            member_id,
            device["id"],
            "removed",
            removed_device["name"],
            subject_device_id=member_id,
            recipient_device_id=member_id,
        )
        connection.execute(
            """
            UPDATE alarm_occurrences
            SET status = 'canceled', resolved_at = now()
            WHERE group_id = %s AND device_id = %s AND status = 'pending'
            """,
            (group_id, member_id),
        )
        connection.execute(
            """
            UPDATE shared_alarms
            SET inactive_cycle_streak = 0, last_evaluated_cycle_date = NULL
            WHERE group_id = %s AND NOT deleted
            """,
            (group_id,),
        )
        push_tokens = get_group_push_tokens(
            connection, group_id, device["id"], "membership"
        )
        push_tokens.extend(
            row["token"]
            for row in connection.execute(
                "SELECT token FROM device_push_tokens WHERE device_id = %s",
                (member_id,),
            ).fetchall()
            if row["token"] not in push_tokens
        )
    send_group_sync(push_tokens)


@app.post("/v1/alarms", status_code=status.HTTP_201_CREATED)
def create_shared_alarm(
    alarm: SharedAlarmCreate, device: dict = Depends(authenticated_device)
) -> dict:
    alarm_id = uuid4()
    with transaction() as connection:
        group = require_alarm_editor(connection, alarm.group_id, device["id"])
        sound_mode = (
            alarm.sound_change.mode.value
            if alarm.sound_change is not None
            else "member_default"
        )
        sound_id = alarm.sound_change.sound_id if alarm.sound_change is not None else None
        if sound_mode == SharedSoundMode.SHARED:
            require_shared_sound_upload(connection, device["id"])
            require_ready_shared_sound(connection, alarm.group_id, sound_id)
        connection.execute(
            """
            INSERT INTO shared_alarms (
                id, group_id, time, label, enabled, days, vibrate,
                start_date, repeat_interval, repeat_unit, repeat_anchor, repeat_duration, repeat_duration_unit, end_date, end_occurrences, advanced,
                snooze_enabled, snooze_minutes, vibration_pattern,
                vibration_pattern_name, sound_mode, sound_id, created_by, updated_by
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
                      %s, %s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                alarm_id,
                alarm.group_id,
                alarm.time,
                alarm.label,
                alarm.enabled,
                alarm.days,
                alarm.vibrate,
                alarm.start_date,
                alarm.repeat_interval,
                alarm.repeat_unit,
                alarm.repeat_anchor,
                alarm.repeat_duration,
                alarm.repeat_duration_unit,
                alarm.end_date,
                alarm.end_occurrences,
                alarm.advanced,
                alarm.snooze_enabled,
                alarm.snooze_minutes,
                alarm.vibration_pattern,
                alarm.vibration_pattern_name,
                sound_mode,
                sound_id,
                device["id"],
                device["id"],
            ),
        )
        record_group_change(
            connection,
            alarm.group_id,
            "alarm",
            str(alarm_id),
            device["id"],
            "created",
            alarm.label,
            alarm.time,
            details={
                "enabled": alarm.enabled,
                "days": alarm.days,
                "vibrate": alarm.vibrate,
                "repeat_unit": alarm.repeat_unit,
                "repeat_interval": alarm.repeat_interval,
                "snooze_enabled": alarm.snooze_enabled,
                "snooze_minutes": alarm.snooze_minutes,
                "sound_mode": sound_mode,
                "vibration_pattern": alarm.vibration_pattern,
                "vibration_pattern_name": alarm.vibration_pattern_name,
            },
        )
        schedule_alarm_occurrences(connection, alarm_id)
        push_tokens = (
            get_group_push_tokens(connection, alarm.group_id, device["id"])
            if group["notify_alarm_changes"]
            else []
        )
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
            "SELECT * FROM shared_alarms WHERE id = %s AND deleted = false",
            (alarm_id,),
        ).fetchone()
        if alarm is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Shared alarm not found")
        group = require_alarm_editor(connection, alarm["group_id"], device["id"])
        if update.sound_change is None:
            sound_mode = alarm["sound_mode"]
            sound_id = alarm["sound_id"]
        else:
            sound_mode = update.sound_change.mode.value
            sound_id = update.sound_change.sound_id
            if sound_mode == SharedSoundMode.SHARED:
                require_shared_sound_upload(connection, device["id"])
                require_ready_shared_sound(connection, alarm["group_id"], sound_id)
        changed = connection.execute(
            """
            UPDATE shared_alarms
            SET revision = revision + 1, time = %s, label = %s, enabled = %s,
                days = %s, vibrate = %s, start_date = %s, repeat_interval = %s,
                repeat_unit = %s, repeat_anchor = %s, repeat_duration = %s,
                repeat_duration_unit = %s, end_date = %s, end_occurrences = %s,
                advanced = %s, snooze_enabled = %s,
                snooze_minutes = %s, sound_mode = %s,
                sound_id = %s, vibration_pattern = %s,
                vibration_pattern_name = %s, inactive_cycle_streak = 0,
                last_evaluated_cycle_date = NULL, updated_by = %s, updated_at = now()
            WHERE id = %s AND revision = %s AND deleted = false
            RETURNING revision
            """,
            (
                update.time,
                update.label,
                update.enabled,
                update.days,
                update.vibrate,
                update.start_date,
                update.repeat_interval,
                update.repeat_unit,
                update.repeat_anchor,
                update.repeat_duration,
                update.repeat_duration_unit,
                update.end_date,
                update.end_occurrences,
                update.advanced,
                update.snooze_enabled,
                update.snooze_minutes,
                sound_mode,
                sound_id,
                update.vibration_pattern,
                update.vibration_pattern_name,
                device["id"],
                alarm_id,
                update.expected_revision,
            ),
        ).fetchone()
        if changed is None:
            raise HTTPException(status.HTTP_409_CONFLICT, "Shared alarm has a newer revision")
        action = "edited"
        if alarm["enabled"] != update.enabled:
            action = "enabled" if update.enabled else "disabled"
        record_group_change(
            connection,
            alarm["group_id"],
            "alarm",
            str(alarm_id),
            device["id"],
            action,
            update.label,
            update.time,
            details={
                "previous_label": alarm["label"],
                "label": update.label,
                "previous_time": alarm["time"],
                "time": update.time,
                "previous_enabled": alarm["enabled"],
                "enabled": update.enabled,
                "previous_days": alarm["days"],
                "days": update.days,
                "previous_vibrate": alarm["vibrate"],
                "vibrate": update.vibrate,
                "previous_repeat_unit": alarm["repeat_unit"],
                "repeat_unit": update.repeat_unit,
                "previous_repeat_interval": alarm["repeat_interval"],
                "repeat_interval": update.repeat_interval,
                "previous_repeat_duration": alarm["repeat_duration"],
                "repeat_duration": update.repeat_duration,
                "previous_end_occurrences": alarm["end_occurrences"],
                "end_occurrences": update.end_occurrences,
                "previous_snooze_enabled": alarm["snooze_enabled"],
                "snooze_enabled": update.snooze_enabled,
                "previous_snooze_minutes": alarm["snooze_minutes"],
                "snooze_minutes": update.snooze_minutes,
                "previous_sound_mode": alarm["sound_mode"],
                "sound_mode": sound_mode,
                "previous_vibration_pattern": alarm["vibration_pattern"],
                "vibration_pattern": update.vibration_pattern,
                "previous_vibration_pattern_name": alarm["vibration_pattern_name"],
                "vibration_pattern_name": update.vibration_pattern_name,
            },
        )
        schedule_alarm_occurrences(connection, alarm_id)
        if alarm["sound_id"] is not None and alarm["sound_id"] != sound_id:
            connection.execute(
                """
                DELETE FROM shared_sounds sound
                WHERE id = %s
                  AND NOT EXISTS (
                    SELECT 1 FROM shared_alarms current_alarm
                    WHERE current_alarm.sound_id = sound.id AND NOT current_alarm.deleted
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM shared_timers timer WHERE timer.sound_id = sound.id
                  )
                """,
                (alarm["sound_id"],),
            )
        push_tokens = (
            get_group_push_tokens(connection, alarm["group_id"], device["id"])
            if group["notify_alarm_changes"]
            else []
        )
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
            "SELECT group_id, label, time, sound_id FROM shared_alarms WHERE id = %s AND deleted = false",
            (alarm_id,),
        ).fetchone()
        if alarm is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Shared alarm not found")
        group = require_alarm_editor(connection, alarm["group_id"], device["id"])
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
        record_group_change(
            connection,
            alarm["group_id"],
            "alarm",
            str(alarm_id),
            device["id"],
            "deleted",
            alarm["label"],
            alarm["time"],
            details={"alarm_revision": changed["revision"]},
        )
        schedule_alarm_occurrences(connection, alarm_id)
        if alarm["sound_id"] is not None:
            connection.execute(
                """
                DELETE FROM shared_sounds sound
                WHERE id = %s
                  AND NOT EXISTS (
                    SELECT 1 FROM shared_alarms current_alarm
                    WHERE current_alarm.sound_id = sound.id AND NOT current_alarm.deleted
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM shared_timers timer WHERE timer.sound_id = sound.id
                  )
                """,
                (alarm["sound_id"],),
            )
        push_tokens = (
            get_group_push_tokens(connection, alarm["group_id"], device["id"])
            if group["notify_alarm_changes"]
            else []
        )
    send_group_sync(push_tokens)
    return {"id": alarm_id, "revision": changed["revision"]}


@app.put("/v1/alarms/{alarm_id}/occurrence")
def register_alarm_occurrence(
    alarm_id: UUID,
    occurrence: AlarmOccurrenceSchedule,
    device: dict = Depends(authenticated_device),
) -> dict:
    if occurrence.deadline_at <= occurrence.trigger_at:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "Occurrence deadline must follow its trigger",
        )
    with transaction() as connection:
        alarm = connection.execute(
            """
            SELECT alarm.group_id, alarm.revision, alarm.enabled, alarm.deleted,
                alarm.end_occurrences, groups.alarm_time_basis,
                groups.alarm_time_zone, devices.time_zone AS device_time_zone
            FROM shared_alarms alarm
            JOIN groups ON groups.id = alarm.group_id
            JOIN devices ON devices.id = %s
            WHERE alarm.id = %s
            """,
            (device["id"], alarm_id),
        ).fetchone()
        if alarm is None or alarm["deleted"]:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Shared alarm not found")
        require_group_member(connection, alarm["group_id"], device["id"])
        if alarm["revision"] != occurrence.alarm_revision or not alarm["enabled"]:
            raise HTTPException(status.HTTP_409_CONFLICT, "Alarm occurrence is no longer current")
        time_zone = (
            alarm["alarm_time_zone"]
            if alarm["alarm_time_basis"] == "group_time_zone"
            else alarm["device_time_zone"]
        )
        if time_zone is None:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "Occurrence cycle date is invalid")
        cycle_date = occurrence.trigger_at.astimezone(ZoneInfo(time_zone)).date()
        if occurrence.cycle_date is not None and cycle_date.isoformat() != occurrence.cycle_date:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "Occurrence cycle date is invalid")
        if alarm["end_occurrences"] == 1 and connection.execute(
            """
            SELECT 1 FROM alarm_occurrences
            WHERE alarm_id = %s AND device_id = %s AND alarm_revision = %s
              AND status IN ('dismissed', 'ignored')
            """,
            (alarm_id, device["id"], occurrence.alarm_revision),
        ).fetchone() is not None:
            return {"occurrence_id": occurrence.occurrence_id}
        connection.execute(
            """
            UPDATE alarm_occurrences
            SET status = 'canceled', resolved_at = now()
            WHERE alarm_id = %s AND device_id = %s AND status = 'pending'
              AND alarm_revision != %s
            """,
            (alarm_id, device["id"], occurrence.alarm_revision),
        )
        connection.execute(
            """
            UPDATE alarm_occurrences
            SET status = 'canceled', resolved_at = now()
            WHERE alarm_id = %s AND device_id = %s AND status = 'pending'
              AND alarm_revision = %s AND occurrence_id != %s
              AND trigger_at BETWEEN %s - interval '2 hours' AND %s + interval '2 hours'
            """,
            (
                alarm_id,
                device["id"],
                occurrence.alarm_revision,
                occurrence.occurrence_id,
                occurrence.trigger_at,
                occurrence.trigger_at,
            ),
        )
        connection.execute(
            """
            INSERT INTO alarm_occurrences (
                alarm_id, group_id, alarm_revision, device_id, occurrence_id,
                trigger_at, deadline_at, cycle_date
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (alarm_id, device_id, occurrence_id) DO UPDATE
            SET alarm_revision = EXCLUDED.alarm_revision,
                trigger_at = EXCLUDED.trigger_at,
                deadline_at = EXCLUDED.deadline_at,
                cycle_date = EXCLUDED.cycle_date,
                status = 'pending',
                resolved_at = NULL
            WHERE alarm_occurrences.status IN ('pending', 'canceled')
            """,
            (
                alarm_id,
                alarm["group_id"],
                occurrence.alarm_revision,
                device["id"],
                occurrence.occurrence_id,
                occurrence.trigger_at,
                occurrence.deadline_at,
                cycle_date,
            ),
        )
    return {"occurrence_id": occurrence.occurrence_id}


@app.post("/v1/alarms/{alarm_id}/activity", status_code=status.HTTP_201_CREATED)
def record_alarm_activity(
    alarm_id: UUID,
    activity: AlarmActivityCreate,
    device: dict = Depends(authenticated_device),
) -> dict:
    activity_id = activity.id
    with transaction() as connection:
        if connection.execute(
            "SELECT 1 FROM alarm_activity WHERE id = %s", (activity_id,)
        ).fetchone() is not None:
            return {"id": activity_id}
        alarm = connection.execute(
            """
            SELECT group_id, revision, label, time, snooze_minutes, end_occurrences
            FROM shared_alarms WHERE id = %s AND deleted = false
            """,
            (alarm_id,),
        ).fetchone()
        if alarm is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Shared alarm not found")
        require_group_member(connection, alarm["group_id"], device["id"])
        if alarm["revision"] != activity.alarm_revision:
            raise HTTPException(status.HTTP_409_CONFLICT, "Activity belongs to an old alarm revision")
        if activity.occurrence_id is None:
            expected_occurrence = connection.execute(
                """
                SELECT occurrence_id
                FROM alarm_occurrences
                WHERE alarm_id = %s AND device_id = %s
                  AND alarm_revision = %s AND trigger_at <= %s
                  AND status IN ('pending', 'ignored')
                ORDER BY trigger_at DESC
                LIMIT 1
                """,
                (alarm_id, device["id"], activity.alarm_revision, activity.occurred_at),
            ).fetchone()
            if expected_occurrence is not None:
                activity = activity.model_copy(
                    update={"occurrence_id": expected_occurrence["occurrence_id"]}
                )
        if activity.occurrence_id is not None:
            connection.execute(
                """
                SELECT status FROM alarm_occurrences
                WHERE alarm_id = %s AND device_id = %s AND occurrence_id = %s
                FOR UPDATE
                """,
                (alarm_id, device["id"], activity.occurrence_id),
            ).fetchone()
        existing_outcome = None
        if activity.occurrence_id is not None:
            existing_outcome = connection.execute(
                """
                SELECT a.id, a.kind, a.occurred_at
                FROM alarm_activity a
                WHERE a.alarm_id = %s
                  AND a.device_id = %s
                  AND a.occurrence_id = %s
                  AND a.kind IN ('dismissed', 'ignored')
                  AND NOT (a.kind = 'ignored' AND a.reason = 'corrected')
                ORDER BY a.created_at DESC
                LIMIT 1
                """,
                (alarm_id, device["id"], activity.occurrence_id),
            ).fetchone()
        if existing_outcome is not None and existing_outcome["kind"] == "dismissed":
            return {"id": existing_outcome["id"]}
        if existing_outcome is not None and activity.kind.value == "ignored":
            return {"id": existing_outcome["id"]}
        if (
            existing_outcome is not None
            and activity.occurred_at >= existing_outcome["occurred_at"]
        ):
            return {"id": existing_outcome["id"]}
        if existing_outcome is not None:
            connection.execute(
                "UPDATE alarm_activity SET reason = 'corrected' WHERE id = %s",
                (existing_outcome["id"],),
            )
            connection.execute(
                """
                UPDATE changes
                SET action = 'corrected',
                    details = coalesce(details, '{}'::jsonb) || %s::jsonb
                WHERE entity_type = 'outcome'
                  AND details->>'activity_id' = %s
                """,
                (
                    json.dumps({"corrected_by": activity.kind.value}),
                    str(existing_outcome["id"]),
                ),
            )
        inserted_activity = connection.execute(
            """
            INSERT INTO alarm_activity (
                id, alarm_id, group_id, alarm_revision, device_id, kind, occurred_at,
                occurrence_id, reason
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (id) DO NOTHING
            RETURNING id
            """,
            (
                activity_id,
                alarm_id,
                alarm["group_id"],
                activity.alarm_revision,
                device["id"],
                activity.kind.value,
                activity.occurred_at,
                activity.occurrence_id,
                activity.reason,
            ),
        ).fetchone()
        if inserted_activity is None:
            return {"id": activity_id}
        record_group_change(
            connection,
            alarm["group_id"],
            "outcome",
            str(alarm_id),
            device["id"],
            activity.kind.value,
            alarm["label"],
            alarm["time"],
            subject_device_id=device["id"],
            details={
                "activity_id": str(activity_id),
                "alarm_revision": activity.alarm_revision,
                "occurrence_id": activity.occurrence_id,
                "reason": activity.reason,
            },
        )
        if activity.occurrence_id is not None:
            resolved_occurrence = connection.execute(
                """
                SELECT trigger_at, cycle_date FROM alarm_occurrences
                WHERE alarm_id = %s AND device_id = %s AND occurrence_id = %s
                """,
                (alarm_id, device["id"], activity.occurrence_id),
            ).fetchone()
            if activity.kind.value == "snoozed":
                connection.execute(
                    """
                    UPDATE alarm_occurrences
                    SET status = 'pending', resolved_at = NULL,
                        deadline_at = %s + ((%s + 10) * interval '1 minute')
                    WHERE alarm_id = %s AND device_id = %s AND occurrence_id = %s
                    """,
                    (
                        activity.occurred_at,
                        alarm["snooze_minutes"],
                        alarm_id,
                        device["id"],
                        activity.occurrence_id,
                    ),
                )
            else:
                connection.execute(
                    """
                    UPDATE alarm_occurrences
                    SET status = %s, resolved_at = now()
                    WHERE alarm_id = %s AND device_id = %s AND occurrence_id = %s
                    """,
                    (
                        activity.kind.value,
                        alarm_id,
                        device["id"],
                        activity.occurrence_id,
                    ),
                )
            if resolved_occurrence is not None:
                schedule_alarm_occurrences(
                    connection,
                    alarm_id,
                    device["id"],
                    resolved_occurrence["trigger_at"] + timedelta(milliseconds=1),
                )
        group = connection.execute(
            """
            SELECT notify_snoozed, notify_dismissed, notify_ignored, shared_answers
            FROM groups WHERE id = %s
            """,
            (alarm["group_id"],),
        ).fetchone()
        if group["shared_answers"]:
            # a group that answers as one carries the outcome to every other member's
            # current occurrence, so one answer silences the ring on all their devices
            for occurrence in connection.execute(
                """
                UPDATE alarm_occurrences
                SET status = %s, resolved_at = now()
                WHERE alarm_id = %s AND device_id != %s
                  AND alarm_revision = %s AND status = 'pending'
                RETURNING device_id, trigger_at
                """,
                (
                    activity.kind.value,
                    alarm_id,
                    device["id"],
                    activity.alarm_revision,
                ),
            ).fetchall():
                schedule_alarm_occurrences(
                    connection,
                    alarm_id,
                    occurrence["device_id"],
                    occurrence["trigger_at"] + timedelta(milliseconds=1),
                )
        should_notify = (
            activity.kind.value == "snoozed" and group["notify_snoozed"]
        ) or (
            activity.kind.value == "dismissed" and group["notify_dismissed"]
        ) or (
            activity.kind.value == "ignored" and group["notify_ignored"]
        )
        push_tokens = (
            get_group_push_tokens(connection, alarm["group_id"], device["id"])
            if group["shared_answers"] or should_notify
            else []
        )
        if activity.occurrence_id is not None and resolved_occurrence is not None:
            push_tokens = list(
                set(push_tokens)
                | set(
                    evaluate_alarm_cycle(
                        connection,
                        alarm_id,
                        activity.alarm_revision,
                        resolved_occurrence["cycle_date"],
                    )
                )
            )
    send_group_sync(push_tokens)
    return {"id": activity_id}


@app.get("/v1/groups/{group_id}/activity")
def get_group_activity(
    group_id: UUID,
    before: int | None = Query(default=None, ge=1),
    limit: int = Query(default=50, ge=1, le=100),
    device: dict = Depends(authenticated_device),
) -> dict:
    with transaction() as connection:
        require_group_member(connection, group_id, device["id"])
        items = connection.execute(
            """
            SELECT c.sequence, c.group_id, c.entity_type, c.entity_id,
                c.action, c.entity_label, c.entity_time,
                c.actor_device_id, coalesce(c.actor_label, actor.name) AS actor_name,
                c.subject_device_id, coalesce(c.subject_label, subject.name) AS subject_name,
                c.details, coalesce(c.group_label, g.name) AS group_name,
                c.created_at AS occurred_at
            FROM changes c
            JOIN groups g ON g.id = c.group_id
            LEFT JOIN devices actor ON actor.id = c.actor_device_id
            LEFT JOIN devices subject ON subject.id = c.subject_device_id
            WHERE c.group_id = %s
              AND (%s::bigint IS NULL OR c.sequence < %s::bigint)
              AND c.action IS NOT NULL
              AND c.entity_type IN ('group', 'membership', 'administrative')
            ORDER BY c.sequence DESC
            LIMIT %s
            """,
            (group_id, before, before, limit + 1),
        ).fetchall()
    return {
        "items": items[:limit],
        "next_before": items[limit - 1]["sequence"] if len(items) > limit else None,
    }


@app.get("/v1/alarms/{alarm_id}/activity")
def get_alarm_activity(
    alarm_id: UUID,
    before: int | None = Query(default=None, ge=1),
    limit: int = Query(default=50, ge=1, le=100),
    device: dict = Depends(authenticated_device),
) -> dict:
    with transaction() as connection:
        alarm = connection.execute(
            "SELECT group_id FROM shared_alarms WHERE id = %s", (alarm_id,)
        ).fetchone()
        if alarm is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Shared alarm not found")
        require_group_member(connection, alarm["group_id"], device["id"])
        items = connection.execute(
            """
            SELECT c.sequence, c.group_id, c.entity_type, c.entity_id,
                c.action, c.entity_label, c.entity_time,
                c.actor_device_id, coalesce(c.actor_label, actor.name) AS actor_name,
                c.subject_device_id, coalesce(c.subject_label, subject.name) AS subject_name,
                c.details, coalesce(c.group_label, g.name) AS group_name,
                c.created_at AS occurred_at
            FROM changes c
            JOIN groups g ON g.id = c.group_id
            LEFT JOIN devices actor ON actor.id = c.actor_device_id
            LEFT JOIN devices subject ON subject.id = c.subject_device_id
            WHERE c.entity_id = %s
              AND c.entity_type IN ('alarm', 'outcome', 'delivery')
              AND (%s::bigint IS NULL OR c.sequence < %s::bigint)
              AND c.action IS NOT NULL
            ORDER BY c.sequence DESC
            LIMIT %s
            """,
            (str(alarm_id), before, before, limit + 1),
        ).fetchall()
    return {
        "items": items[:limit],
        "next_before": items[limit - 1]["sequence"] if len(items) > limit else None,
    }


@app.post("/v1/groups/{group_id}/timers", status_code=status.HTTP_201_CREATED)
def start_shared_timer(
    group_id: UUID,
    timer: SharedTimerCreate,
    device: dict = Depends(authenticated_device),
) -> dict:
    timer_id = uuid4()
    with transaction() as connection:
        require_alarm_editor(connection, group_id, device["id"])
        sweep_shared_timers(connection)
        sound_mode = (
            timer.sound.mode.value
            if timer.sound is not None
            else "member_default"
        )
        sound_id = timer.sound.sound_id if timer.sound is not None else None
        if sound_mode == SharedSoundMode.SHARED:
            require_shared_sound_upload(connection, device["id"])
            require_ready_shared_sound(connection, group_id, sound_id)
        expires_at = datetime.now(UTC) + timedelta(seconds=timer.duration_seconds)
        connection.execute(
            """
            INSERT INTO shared_timers (
                id, group_id, label, duration_seconds, increment_seconds,
                expires_at, started_by, vibrate,
                vibration_pattern, vibration_pattern_name, sound_mode, sound_id
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                timer_id,
                group_id,
                timer.label,
                timer.duration_seconds,
                timer.increment_seconds,
                expires_at,
                device["id"],
                timer.vibrate,
                timer.vibration_pattern,
                timer.vibration_pattern_name,
                sound_mode,
                sound_id,
            ),
        )
        push_tokens = get_group_push_tokens(connection, group_id, device["id"])
    send_group_sync(push_tokens)
    return {"id": timer_id}


@app.patch("/v1/timers/{timer_id}")
def adjust_shared_timer(
    timer_id: UUID,
    update: SharedTimerUpdate,
    device: dict = Depends(authenticated_device),
) -> dict:
    with transaction() as connection:
        timer = connection.execute(
            "SELECT * FROM shared_timers WHERE id = %s", (timer_id,)
        ).fetchone()
        if timer is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Shared timer not found")
        require_alarm_editor(connection, timer["group_id"], device["id"])
        now = datetime.now(UTC)
        if update.action == SharedTimerAction.ADD:
            base = max(timer["expires_at"], now)
            expires_at = base + timedelta(seconds=timer["increment_seconds"])
        else:
            expires_at = now + timedelta(seconds=timer["duration_seconds"])
        changed = connection.execute(
            "UPDATE shared_timers SET expires_at = %s WHERE id = %s RETURNING *",
            (expires_at, timer_id),
        ).fetchone()
        push_tokens = get_group_push_tokens(connection, timer["group_id"], device["id"])
    send_group_sync(push_tokens)
    return {
        "id": changed["id"],
        "group_id": changed["group_id"],
        "label": changed["label"],
        "duration_seconds": changed["duration_seconds"],
        "increment_seconds": changed["increment_seconds"],
        "expires_at": changed["expires_at"],
        "started_by": changed["started_by"],
    }


@app.delete("/v1/timers/{timer_id}", status_code=status.HTTP_204_NO_CONTENT)
def cancel_shared_timer(
    timer_id: UUID, device: dict = Depends(authenticated_device)
) -> None:
    with transaction() as connection:
        timer = connection.execute(
            "SELECT group_id, sound_id FROM shared_timers WHERE id = %s", (timer_id,)
        ).fetchone()
        if timer is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Shared timer not found")
        require_alarm_editor(connection, timer["group_id"], device["id"])
        connection.execute("DELETE FROM shared_timers WHERE id = %s", (timer_id,))
        if timer["sound_id"] is not None:
            connection.execute(
                """
                DELETE FROM shared_sounds sound
                WHERE id = %s
                  AND NOT EXISTS (
                    SELECT 1 FROM shared_alarms alarm
                    WHERE alarm.sound_id = sound.id AND NOT alarm.deleted
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM shared_timers current_timer
                    WHERE current_timer.sound_id = sound.id
                  )
                """,
                (timer["sound_id"],),
            )
        push_tokens = get_group_push_tokens(connection, timer["group_id"], device["id"])
    send_group_sync(push_tokens)


@app.get("/v1/sync")
def synchronize(
    since: int = Query(default=0, ge=0),
    device: dict = Depends(authenticated_device),
) -> dict:
    with transaction() as connection:
        groups = connection.execute(
            """
            SELECT g.*, gm.role, gm.notify_membership, gm.notify_administrative
            FROM groups g
            JOIN group_members gm ON gm.group_id = g.id
            WHERE gm.device_id = %s
            ORDER BY g.created_at
            """,
            (device["id"],),
        ).fetchall()
        group_ids = [group["id"] for group in groups]
        cursor = connection.execute(
            """
            SELECT coalesce(max(sequence), %s) AS cursor
            FROM changes
            WHERE group_id = ANY(%s) OR recipient_device_id = %s
            """,
            (since, group_ids, device["id"]),
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
            """
            SELECT alarm.*, sound.title AS sound_title
            FROM shared_alarms alarm
            LEFT JOIN shared_sounds sound ON sound.id = alarm.sound_id
            WHERE alarm.group_id = ANY(%s)
            """,
            (group_ids,),
        ).fetchall()
        sweep_shared_timers(connection)
        timers = connection.execute(
            """
            SELECT timer.*, sound.title AS sound_title
            FROM shared_timers timer
            LEFT JOIN shared_sounds sound ON sound.id = timer.sound_id
            WHERE timer.group_id = ANY(%s) ORDER BY timer.created_at
            """,
            (group_ids,),
        ).fetchall()
        occurrences = connection.execute(
            """
            SELECT alarm_id, occurrence_id, status
            FROM alarm_occurrences
            WHERE device_id = %s
              AND status != 'canceled'
              AND (status = 'pending' OR resolved_at > now() - interval '2 days')
            """,
            (device["id"],),
        ).fetchall()
        changes = connection.execute(
            """
            SELECT c.sequence, c.group_id, c.entity_type, c.entity_id,
                c.action, c.entity_label, c.entity_time,
                c.actor_device_id, coalesce(c.actor_label, actor.name) AS actor_name,
                c.subject_device_id, coalesce(c.subject_label, subject.name) AS subject_name,
                c.recipient_device_id, c.details,
                coalesce(c.group_label, g.name) AS group_name, c.created_at AS occurred_at
            FROM changes c
            JOIN groups g ON g.id = c.group_id
            LEFT JOIN devices actor ON actor.id = c.actor_device_id
            LEFT JOIN devices subject ON subject.id = c.subject_device_id
            WHERE (c.group_id = ANY(%s) OR c.recipient_device_id = %s)
              AND c.sequence > %s
              AND c.sequence <= %s
              AND c.action IS NOT NULL
            ORDER BY c.sequence
            """,
            (group_ids, device["id"], since, cursor),
        ).fetchall()
        for alarm in alarms:
            if not alarm["deleted"]:
                delivered = connection.execute(
                    """
                    INSERT INTO alarm_deliveries (alarm_id, device_id, revision)
                    VALUES (%s, %s, %s)
                    ON CONFLICT (alarm_id, device_id, revision) DO NOTHING
                    RETURNING delivered_at
                    """,
                    (alarm["id"], device["id"], alarm["revision"]),
                ).fetchone()
                if delivered is not None:
                    record_group_change(
                        connection,
                        alarm["group_id"],
                        "delivery",
                        str(alarm["id"]),
                        device["id"],
                        "delivered",
                        alarm["label"],
                        alarm["time"],
                        subject_device_id=device["id"],
                        details={"alarm_revision": alarm["revision"]},
                    )
    return {
        "cursor": cursor,
        "groups": groups,
        "members": members,
        "alarms": alarms,
        "timers": timers,
        "occurrences": occurrences,
        "changes": changes,
    }
