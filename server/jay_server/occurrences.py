import asyncio
import logging
from contextlib import suppress
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

from psycopg import Connection

from jay_server.database import transaction
from jay_server.domain import get_group_push_tokens, record_group_change
from jay_server.push import send_group_sync


logger = logging.getLogger(__name__)


def next_alarm_trigger(alarm: dict, time_zone: str, after: datetime) -> datetime:
    zone = ZoneInfo(time_zone)
    local_after = after.astimezone(zone)
    hours, remainder = divmod(alarm["time"], 3_600_000)
    minutes = remainder // 60_000
    days = set(alarm["days"])
    for offset in range(8):
        date = local_after.date() + timedelta(days=offset)
        clock_day = (date.weekday() + 1) % 7
        if alarm["repeat"] and days and clock_day not in days:
            continue
        candidate = datetime(
            date.year,
            date.month,
            date.day,
            hours,
            minutes,
            tzinfo=zone,
        )
        if candidate > local_after:
            return candidate.astimezone(UTC)
    raise RuntimeError("Unable to calculate the next alarm occurrence")


def schedule_alarm_occurrences(
    connection: Connection,
    alarm_id: UUID,
    device_id: str | None = None,
    after: datetime | None = None,
) -> None:
    alarm = connection.execute(
        "SELECT * FROM shared_alarms WHERE id = %s", (alarm_id,)
    ).fetchone()
    if alarm is None:
        return
    connection.execute(
        """
        UPDATE alarm_occurrences
        SET status = 'canceled', resolved_at = now()
        WHERE alarm_id = %s AND status = 'pending'
          AND (%s::text IS NULL OR device_id = %s)
          AND (alarm_revision != %s OR %s = false OR %s = true)
        """,
        (
            alarm_id,
            device_id,
            device_id,
            alarm["revision"],
            alarm["enabled"],
            alarm["deleted"],
        ),
    )
    if not alarm["enabled"] or alarm["deleted"]:
        return
    members = connection.execute(
        """
        SELECT d.id, d.time_zone
        FROM group_members gm
        JOIN devices d ON d.id = gm.device_id
        WHERE gm.group_id = %s AND d.time_zone IS NOT NULL
          AND (%s::text IS NULL OR d.id = %s)
        """,
        (alarm["group_id"], device_id, device_id),
    ).fetchall()
    for member in members:
        if not alarm["repeat"] and connection.execute(
            """
            SELECT 1 FROM alarm_occurrences
            WHERE alarm_id = %s AND device_id = %s AND alarm_revision = %s
              AND status IN ('dismissed', 'ignored')
            """,
            (alarm_id, member["id"], alarm["revision"]),
        ).fetchone() is not None:
            continue
        trigger_at = next_alarm_trigger(
            alarm,
            member["time_zone"],
            after or datetime.now(UTC),
        )
        occurrence_id = str(int(trigger_at.timestamp() * 1000))
        connection.execute(
            """
            INSERT INTO alarm_occurrences (
                alarm_id, group_id, alarm_revision, device_id, occurrence_id,
                trigger_at, deadline_at
            ) VALUES (%s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (alarm_id, device_id, occurrence_id) DO NOTHING
            """,
            (
                alarm_id,
                alarm["group_id"],
                alarm["revision"],
                member["id"],
                occurrence_id,
                trigger_at,
                trigger_at + timedelta(minutes=10),
            ),
        )


def process_due_alarm_occurrences() -> None:
    push_tokens: set[str] = set()
    with transaction() as connection:
        due = connection.execute(
            """
            SELECT occurrence.*, alarm.label, alarm.time, alarm.repeat,
                alarm.enabled, alarm.deleted, alarm.revision,
                group_settings.notify_ignored,
                gm.device_id IS NOT NULL AS is_member
            FROM alarm_occurrences occurrence
            JOIN shared_alarms alarm ON alarm.id = occurrence.alarm_id
            JOIN groups group_settings ON group_settings.id = occurrence.group_id
            LEFT JOIN group_members gm
              ON gm.group_id = occurrence.group_id
             AND gm.device_id = occurrence.device_id
            WHERE occurrence.status = 'pending' AND occurrence.deadline_at <= now()
            ORDER BY occurrence.deadline_at
            LIMIT 100
            FOR UPDATE OF occurrence SKIP LOCKED
            """
        ).fetchall()
        for occurrence in due:
            if (
                not occurrence["is_member"]
                or not occurrence["enabled"]
                or occurrence["deleted"]
                or occurrence["revision"] != occurrence["alarm_revision"]
            ):
                connection.execute(
                    """
                    UPDATE alarm_occurrences
                    SET status = 'canceled', resolved_at = now()
                    WHERE alarm_id = %s AND device_id = %s AND occurrence_id = %s
                    """,
                    (
                        occurrence["alarm_id"],
                        occurrence["device_id"],
                        occurrence["occurrence_id"],
                    ),
                )
                continue
            activity_id = uuid4()
            connection.execute(
                """
                INSERT INTO alarm_activity (
                    id, alarm_id, group_id, alarm_revision, device_id, kind,
                    occurred_at, occurrence_id, reason
                ) VALUES (%s, %s, %s, %s, %s, 'ignored', %s, %s, 'no_response')
                """,
                (
                    activity_id,
                    occurrence["alarm_id"],
                    occurrence["group_id"],
                    occurrence["alarm_revision"],
                    occurrence["device_id"],
                    occurrence["deadline_at"],
                    occurrence["occurrence_id"],
                ),
            )
            record_group_change(
                connection,
                occurrence["group_id"],
                "outcome",
                str(occurrence["alarm_id"]),
                occurrence["device_id"],
                "ignored",
                occurrence["label"],
                occurrence["time"],
                subject_device_id=occurrence["device_id"],
                details={
                    "activity_id": str(activity_id),
                    "alarm_revision": occurrence["alarm_revision"],
                    "occurrence_id": occurrence["occurrence_id"],
                    "reason": "no_response",
                },
            )
            connection.execute(
                """
                UPDATE alarm_occurrences
                SET status = 'ignored', resolved_at = now()
                WHERE alarm_id = %s AND device_id = %s AND occurrence_id = %s
                """,
                (
                    occurrence["alarm_id"],
                    occurrence["device_id"],
                    occurrence["occurrence_id"],
                ),
            )
            if occurrence["notify_ignored"]:
                push_tokens.update(
                    get_group_push_tokens(
                        connection,
                        occurrence["group_id"],
                        occurrence["device_id"],
                    )
                )
            if occurrence["repeat"]:
                schedule_alarm_occurrences(
                    connection,
                    occurrence["alarm_id"],
                    occurrence["device_id"],
                    occurrence["trigger_at"] + timedelta(milliseconds=1),
                )
    send_group_sync(list(push_tokens))


class AlarmOccurrenceMonitor:
    def __init__(self) -> None:
        self.task: asyncio.Task | None = None

    async def start(self) -> None:
        self.task = asyncio.create_task(self.run())

    async def stop(self) -> None:
        if self.task is not None:
            self.task.cancel()
            with suppress(asyncio.CancelledError):
                await self.task
            self.task = None

    async def run(self) -> None:
        while True:
            try:
                await asyncio.to_thread(process_due_alarm_occurrences)
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Jay alarm-occurrence monitor failed")
            await asyncio.sleep(15)


alarm_occurrence_monitor = AlarmOccurrenceMonitor()
