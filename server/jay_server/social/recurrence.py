from calendar import monthrange
from datetime import UTC, date, datetime, timedelta
from itertools import chain
from typing import Iterator
from zoneinfo import ZoneInfo


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
        while cycle > 0:
            month = _add_months(start.replace(day=1), cycle * months)
            day = (
                date(month.year, month.month, min(start.day, monthrange(month.year, month.month)[1]))
                if alarm["repeat_anchor"] == "DAY_OF_MONTH"
                else _weekday_in_month(month.year, month.month, position, start.weekday())
            )
            if day is not None and day <= from_day:
                break
            cycle -= 1
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
    start = alarm["start_date"]
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
    day = occurrence_on_or_after(alarm, alarm["start_date"])
    for _ in range(count - 1):
        if day is None:
            return None
        day = occurrence_on_or_after(alarm, day + timedelta(days=1))
    return day


def next_alarm_trigger(alarm: dict, time_zone: str, after: datetime) -> datetime | None:
    """The moment the alarm rings next, or None when its repetition has nothing left."""
    zone = ZoneInfo(time_zone)
    local_after = after.astimezone(zone)
    hours, remainder = divmod(alarm["local_time_ms"], 3_600_000)
    minutes = remainder // 60_000
    earliest = local_after.date()
    if (local_after.hour, local_after.minute) >= (hours, minutes):
        earliest += timedelta(days=1)

    day = occurrence_on_or_after(alarm, earliest)
    if day is None:
        return None
    if alarm["end_date"] is not None and day > alarm["end_date"]:
        return None
    if alarm["end_occurrences"] is not None:
        last = _last_occurrence(alarm, alarm["end_occurrences"])
        if last is None or day > last:
            return None
    return datetime(day.year, day.month, day.day, hours, minutes, tzinfo=zone).astimezone(UTC)
