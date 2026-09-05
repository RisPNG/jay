import asyncio
import logging
from contextlib import suppress
from calendar import monthrange
from datetime import UTC, date, datetime, timedelta
from itertools import chain
from typing import Iterator
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

from psycopg import Connection

from jay_server.config import settings
from jay_server.database import transaction
from jay_server.domain import (
    get_group_push_tokens,
    record_group_change,
    remove_device,
    sweep_shared_timers,
)
from jay_server.object_storage import delete_sound_object
from jay_server.push import send_group_sync


logger = logging.getLogger(__name__)

EPOCH = date(1970, 1, 1)
DAYS_PER_WEEK = 7
MONTHS_PER_YEAR = 12


def _add_months(day: date, months: int) -> date:
    month_index = day.month - 1 + months
    year = day.year + month_index // 12
    month = month_index % 12 + 1
    return date(year, month, min(day.day, monthrange(year, month)[1]))


def _weekday_in_month(year: int, month: int, position: int, weekday: int) -> date | None:
    first = date(year, month, 1)
    day = 1 + (weekday - first.weekday()) % 7 + (position - 1) * 7
    return date(year, month, day) if day <= monthrange(year, month)[1] else None


def _run_starts(alarm: dict, start: date, from_day: date) -> Iterator[date]:
    """Yield the day each repetition begins on, from the last one starting on or before
    from_day."""
    interval = alarm["repeat_interval"]
    if alarm["repeat_unit"] == "DAY":
        elapsed = (from_day - start).days // interval
        run_start = start + timedelta(days=elapsed * interval)
        while True:
            yield run_start
            run_start += timedelta(days=interval)
    elif alarm["repeat_unit"] == "WEEK":
        elapsed = ((from_day - start).days // DAYS_PER_WEEK) // interval
        run_start = start + timedelta(weeks=elapsed * interval)
        while True:
            yield run_start
            run_start += timedelta(weeks=interval)
    else:
        months = interval * MONTHS_PER_YEAR if alarm["repeat_unit"] == "YEAR" else interval
        position = (start.day - 1) // DAYS_PER_WEEK + 1
        elapsed = (
            (from_day.year - start.year) * MONTHS_PER_YEAR + from_day.month - start.month
        ) // months
        cycle = elapsed
        while True:
            month = _add_months(start.replace(day=1), cycle * months)
            if alarm["repeat_anchor"] == "DAY_OF_MONTH":
                yield date(
                    month.year,
                    month.month,
                    min(start.day, monthrange(month.year, month.month)[1]),
                )
            else:
                day = _weekday_in_month(month.year, month.month, position, start.weekday())
                if day is not None:
                    yield day
            cycle += 1


def _run_end(alarm: dict, run_start: date) -> date:
    """The day after the last one a repetition beginning at run_start can ring on."""
    duration = alarm["repeat_duration"]
    if duration is None:
        if alarm["repeat_unit"] == "WEEK":
            return run_start + timedelta(weeks=1)
        return run_start + timedelta(days=1)
    unit = alarm["repeat_duration_unit"]
    if unit == "DAY":
        return run_start + timedelta(days=duration)
    if unit == "WEEK":
        return run_start + timedelta(weeks=duration)
    if unit == "MONTH":
        return _add_months(run_start, duration)
    return _add_months(run_start, duration * MONTHS_PER_YEAR)


def _rings_on(alarm: dict, day: date) -> bool:
    return alarm["repeat_unit"] != "WEEK" or (day.weekday() + 1) % 7 in set(alarm["days"])


def _rings_within_run(alarm: dict, run_start: date) -> bool:
    day = run_start
    end = _run_end(alarm, run_start)
    while day < end:
        if _rings_on(alarm, day):
            return True
        day += timedelta(days=1)
    return False


def occurrence_on_or_after(alarm: dict, from_day: date) -> date | None:
    """The first day on or after from_day the alarm rings on, or None when its repetition
    never lets it ring. Every repetition starts a run lasting for the duration the alarm
    repeats for, and it rings on each day of that run a weekly repetition also selects."""
    start = EPOCH + timedelta(days=alarm["start_date"])
    from_day = max(from_day, start)
    runs = _run_starts(alarm, start, from_day)
    first_run = next(runs)
    if not _rings_within_run(alarm, first_run):
        return None
    for run_start in chain([first_run], runs):
        day = max(from_day, run_start)
        end = _run_end(alarm, run_start)
        while day < end:
            if _rings_on(alarm, day):
                return day
            day += timedelta(days=1)


def _last_occurrence(alarm: dict, count: int) -> date | None:
    day = occurrence_on_or_after(alarm, EPOCH + timedelta(days=alarm["start_date"]))
    for _ in range(count - 1):
        if day is None:
            return None
        day = occurrence_on_or_after(alarm, day + timedelta(days=1))
    return day


def next_alarm_trigger(alarm: dict, time_zone: str, after: datetime) -> datetime | None:
    """The moment the alarm rings next, or None when its repetition has nothing left."""
    zone = ZoneInfo(time_zone)
    local_after = after.astimezone(zone)
    hours, remainder = divmod(alarm["time"], 3_600_000)
    minutes = remainder // 60_000
    earliest = local_after.date()
    if (local_after.hour, local_after.minute) >= (hours, minutes):
        earliest += timedelta(days=1)

    day = occurrence_on_or_after(alarm, earliest)
    if day is None:
        return None
    if alarm["end_date"] is not None and day > EPOCH + timedelta(days=alarm["end_date"]):
        return None
    if alarm["end_occurrences"] is not None:
        last = _last_occurrence(alarm, alarm["end_occurrences"])
        if last is None or day > last:
            return None
    return datetime(day.year, day.month, day.day, hours, minutes, tzinfo=zone).astimezone(UTC)


def schedule_alarm_occurrences(
    connection: Connection,
    alarm_id: UUID,
    device_id: str | None = None,
    after: datetime | None = None,
) -> None:
    alarm = connection.execute(
        """
        SELECT alarm.*, groups.alarm_time_basis, groups.alarm_time_zone
        FROM shared_alarms alarm
        JOIN groups ON groups.id = alarm.group_id
        WHERE alarm.id = %s
        """,
        (alarm_id,),
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
        if alarm["end_occurrences"] == 1 and connection.execute(
            """
            SELECT 1 FROM alarm_occurrences
            WHERE alarm_id = %s AND device_id = %s AND alarm_revision = %s
              AND status IN ('dismissed', 'ignored')
            """,
            (alarm_id, member["id"], alarm["revision"]),
        ).fetchone() is not None:
            continue
        time_zone = (
            alarm["alarm_time_zone"]
            if alarm["alarm_time_basis"] == "group_time_zone"
            else member["time_zone"]
        )
        trigger_at = next_alarm_trigger(
            alarm,
            time_zone,
            after or datetime.now(UTC),
        )
        if trigger_at is None:
            continue
        occurrence_id = str(int(trigger_at.timestamp() * 1000))
        connection.execute(
            """
            INSERT INTO alarm_occurrences (
                alarm_id, group_id, alarm_revision, device_id, occurrence_id,
                trigger_at, deadline_at, cycle_date
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (alarm_id, device_id, occurrence_id) DO UPDATE
            SET group_id = EXCLUDED.group_id,
                alarm_revision = EXCLUDED.alarm_revision,
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
                alarm["revision"],
                member["id"],
                occurrence_id,
                trigger_at,
                trigger_at + timedelta(minutes=10),
                trigger_at.astimezone(ZoneInfo(time_zone)).date(),
            ),
        )


def evaluate_alarm_cycle(
    connection: Connection,
    alarm_id: UUID,
    alarm_revision: int,
    cycle_date: date,
) -> list[str]:
    alarm = connection.execute(
        """
        SELECT alarm.*, groups.shared_answers
        FROM shared_alarms alarm
        JOIN groups ON groups.id = alarm.group_id
        WHERE alarm.id = %s
        FOR UPDATE OF alarm
        """,
        (alarm_id,),
    ).fetchone()
    if (
        alarm is None
        or alarm["deleted"]
        or not alarm["enabled"]
        or alarm["revision"] != alarm_revision
        or (
            alarm["last_evaluated_cycle_date"] is not None
            and alarm["last_evaluated_cycle_date"] >= cycle_date
        )
    ):
        return []
    expected_members = connection.execute(
        """
        SELECT count(*) AS count
        FROM group_members gm
        JOIN devices d ON d.id = gm.device_id
        WHERE gm.group_id = %s AND d.time_zone IS NOT NULL
        """,
        (alarm["group_id"],),
    ).fetchone()["count"]
    cycle = connection.execute(
        """
        SELECT count(*) AS count,
            count(*) FILTER (WHERE status = 'pending') AS pending
        FROM alarm_occurrences
        WHERE alarm_id = %s AND alarm_revision = %s AND cycle_date = %s
          AND status != 'canceled'
        """,
        (alarm_id, alarm_revision, cycle_date),
    ).fetchone()
    if cycle["count"] < expected_members or cycle["pending"] > 0:
        return []
    if alarm["shared_answers"]:
        # the fan-out resolves every member's occurrence without recording an activity
        # for each of them, so any member answering keeps the shared cycle active
        active = connection.execute(
            """
            SELECT 1 FROM alarm_activity
            WHERE alarm_id = %s AND alarm_revision = %s
              AND kind IN ('snoozed', 'dismissed')
            LIMIT 1
            """,
            (alarm_id, alarm_revision),
        ).fetchone() is not None
    else:
        active = connection.execute(
            """
            SELECT 1
            FROM alarm_activity activity
            JOIN alarm_occurrences occurrence
              ON occurrence.alarm_id = activity.alarm_id
             AND occurrence.device_id = activity.device_id
             AND occurrence.occurrence_id = activity.occurrence_id
            WHERE activity.alarm_id = %s
              AND activity.alarm_revision = %s
              AND occurrence.cycle_date = %s
              AND activity.kind IN ('snoozed', 'dismissed')
            LIMIT 1
            """,
            (alarm_id, alarm_revision, cycle_date),
        ).fetchone() is not None
    streak = 0 if active else alarm["inactive_cycle_streak"] + 1
    if streak < 3:
        connection.execute(
            """
            UPDATE shared_alarms
            SET inactive_cycle_streak = %s, last_evaluated_cycle_date = %s
            WHERE id = %s
            """,
            (streak, cycle_date, alarm_id),
        )
        return []
    connection.execute(
        """
        UPDATE shared_alarms
        SET revision = revision + 1, deleted = true, inactive_cycle_streak = 3,
            last_evaluated_cycle_date = %s, updated_at = now()
        WHERE id = %s
        """,
        (cycle_date, alarm_id),
    )
    connection.execute(
        """
        UPDATE alarm_occurrences
        SET status = 'canceled', resolved_at = now()
        WHERE alarm_id = %s AND status = 'pending'
        """,
        (alarm_id,),
    )
    record_group_change(
        connection,
        alarm["group_id"],
        "alarm",
        str(alarm_id),
        action="deleted",
        entity_label=alarm["label"],
        entity_time=alarm["time"],
        details={"reason": "three_inactive_cycles", "cycle_date": cycle_date.isoformat()},
    )
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
    return get_group_push_tokens(connection, alarm["group_id"], "")


def process_sound_deletions() -> None:
    with transaction() as connection:
        sweep_shared_timers(connection)
        deletions = connection.execute(
            """
            SELECT object_key FROM sound_deletions
            WHERE last_attempt_at IS NULL
               OR last_attempt_at < now() - interval '5 minutes'
            ORDER BY created_at
            LIMIT 20
            """
        ).fetchall()
    for deletion in deletions:
        try:
            delete_sound_object(deletion["object_key"])
        except Exception:
            logger.exception("Jay shared-sound deletion failed")
            with transaction() as connection:
                connection.execute(
                    """
                    UPDATE sound_deletions
                    SET attempts = attempts + 1, last_attempt_at = now()
                    WHERE object_key = %s
                    """,
                    (deletion["object_key"],),
                )
        else:
            with transaction() as connection:
                connection.execute(
                    "DELETE FROM sound_deletions WHERE object_key = %s",
                    (deletion["object_key"],),
                )


def process_due_alarm_occurrences() -> None:
    push_tokens: set[str] = set()
    with transaction() as connection:
        due = connection.execute(
            """
            SELECT occurrence.*, alarm.label, alarm.time,
                alarm.enabled, alarm.deleted, alarm.revision,
                group_settings.notify_ignored, group_settings.shared_answers,
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
            current_status = connection.execute(
                """
                SELECT status FROM alarm_occurrences
                WHERE alarm_id = %s AND device_id = %s AND occurrence_id = %s
                """,
                (
                    occurrence["alarm_id"],
                    occurrence["device_id"],
                    occurrence["occurrence_id"],
                ),
            ).fetchone()["status"]
            if current_status != "pending":
                continue
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
            schedule_alarm_occurrences(
                connection,
                occurrence["alarm_id"],
                occurrence["device_id"],
                occurrence["trigger_at"] + timedelta(milliseconds=1),
            )
            if occurrence["shared_answers"]:
                # the same unified rule as reported outcomes ends the whole group's
                # current occurrences with this unanswered one
                for fanned in connection.execute(
                    """
                    UPDATE alarm_occurrences
                    SET status = 'ignored', resolved_at = now()
                    WHERE alarm_id = %s AND device_id != %s
                      AND alarm_revision = %s AND status = 'pending'
                    RETURNING device_id, trigger_at
                    """,
                    (
                        occurrence["alarm_id"],
                        occurrence["device_id"],
                        occurrence["alarm_revision"],
                    ),
                ).fetchall():
                    schedule_alarm_occurrences(
                        connection,
                        occurrence["alarm_id"],
                        fanned["device_id"],
                        fanned["trigger_at"] + timedelta(milliseconds=1),
                    )
                push_tokens.update(
                    get_group_push_tokens(
                        connection,
                        occurrence["group_id"],
                        occurrence["device_id"],
                    )
                )
            push_tokens.update(
                evaluate_alarm_cycle(
                    connection,
                    occurrence["alarm_id"],
                    occurrence["alarm_revision"],
                    occurrence["cycle_date"],
                )
            )
    send_group_sync(list(push_tokens))


def process_inactive_devices() -> None:
    if settings.device_inactivity_timeout_days <= 0:
        return
    push_tokens: set[str] = set()
    with transaction() as connection:
        devices = connection.execute(
            """
            SELECT id FROM devices
            WHERE last_seen_at < now() - make_interval(days => %s)
            ORDER BY created_at
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """,
            (settings.device_inactivity_timeout_days,),
        ).fetchall()
        for device in devices:
            push_tokens.update(
                remove_device(connection, device["id"], change_reason="inactivity")
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
                await asyncio.to_thread(process_sound_deletions)
                await asyncio.to_thread(process_inactive_devices)
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Jay alarm-occurrence monitor failed")
            await asyncio.sleep(15)


alarm_occurrence_monitor = AlarmOccurrenceMonitor()
