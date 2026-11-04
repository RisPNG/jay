from calendar import monthrange
from datetime import UTC, date, datetime, timedelta, tzinfo
from itertools import chain, islice
from typing import Iterator
from zoneinfo import ZoneInfo


DAYS_PER_WEEK = 7
MONTHS_PER_YEAR = 12


def _alarm_origin(alarm: dict, zone: tzinfo) -> datetime:
    hours, remainder = divmod(alarm["local_time_ms"], 3_600_000)
    start_date = alarm["start_date"]
    start = datetime(start_date.year, start_date.month, start_date.day, hours, remainder // 60_000, tzinfo=zone)
    if alarm.get("start_with_later_offset", False):
        later = start.replace(fold=1)
        if later.astimezone(UTC).astimezone(zone).replace(tzinfo=None) == start.replace(tzinfo=None):
            return later
    return start


def _add_months(moment: datetime, months: int) -> datetime:
    month_index = moment.month - 1 + months
    year = moment.year + month_index // 12
    month = month_index % 12 + 1
    return moment.replace(year=year, month=month, day=min(moment.day, monthrange(year, month)[1]), fold=0)


def _weekday_in_month(year: int, month: int, position: int, weekday: int) -> date | None:
    first = date(year, month, 1)
    day = 1 + (weekday - first.weekday()) % 7 + (position - 1) * 7
    return date(year, month, day) if day <= monthrange(year, month)[1] else None


def _run_starts(alarm: dict, start: datetime, from_time: datetime) -> Iterator[datetime]:
    interval = alarm["repeat_interval"]
    if alarm["repeat_unit"] == "HOUR":
        step = timedelta(hours=interval)
        origin = start.astimezone(UTC)
        elapsed = (from_time.astimezone(UTC) - origin) // step
        run_start = origin + elapsed * step
        while True:
            yield run_start.astimezone(start.tzinfo)
            run_start += step
    elif alarm["repeat_unit"] == "DAY":
        elapsed = (from_time.date() - start.date()).days // interval
        run_start = start if elapsed == 0 else start + timedelta(days=elapsed * interval)
        while True:
            yield run_start
            run_start += timedelta(days=interval)
    elif alarm["repeat_unit"] == "WEEK":
        elapsed = ((from_time.date() - start.date()).days // DAYS_PER_WEEK) // interval
        run_start = start if elapsed == 0 else start + timedelta(weeks=elapsed * interval)
        while True:
            yield run_start
            run_start += timedelta(weeks=interval)
    else:
        months = interval * MONTHS_PER_YEAR if alarm["repeat_unit"] == "YEAR" else interval
        position = (start.day - 1) // DAYS_PER_WEEK + 1
        elapsed = ((from_time.year - start.year) * MONTHS_PER_YEAR + from_time.month - start.month) // months
        cycle = elapsed
        while cycle > 0:
            month = _add_months(start.replace(day=1), cycle * months)
            day = (
                date(month.year, month.month, min(start.day, monthrange(month.year, month.month)[1]))
                if alarm["repeat_anchor"] == "DAY_OF_MONTH"
                else _weekday_in_month(month.year, month.month, position, start.weekday())
            )
            if day is not None and day <= from_time.date():
                break
            cycle -= 1
        while True:
            month = _add_months(start.replace(day=1), cycle * months)
            if alarm["repeat_anchor"] == "DAY_OF_MONTH":
                yield month.replace(day=min(start.day, monthrange(month.year, month.month)[1]), fold=start.fold if cycle == 0 else 0)
            else:
                day = _weekday_in_month(month.year, month.month, position, start.weekday())
                if day is not None:
                    yield month.replace(day=day.day, fold=start.fold if cycle == 0 else 0)
            cycle += 1


def _run_end(alarm: dict, run_start: datetime) -> datetime:
    if alarm["repeat_unit"] == "HOUR":
        return (run_start.astimezone(UTC) + timedelta(hours=1)).astimezone(run_start.tzinfo)
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
    return alarm["repeat_unit"] != "WEEK" or (day.weekday() + 1) % DAYS_PER_WEEK in set(alarm["days"])


def _rings_within_run(alarm: dict, run_start: datetime) -> bool:
    moment = run_start
    end = _run_end(alarm, run_start).astimezone(UTC)
    while moment.astimezone(UTC) < end:
        if _rings_on(alarm, moment.date()):
            return True
        moment += timedelta(days=1)
    return False


def occurrences_on_or_after(alarm: dict, from_time: datetime) -> Iterator[datetime]:
    start = _alarm_origin(alarm, from_time.tzinfo)
    earliest = max(from_time.astimezone(UTC), start.astimezone(UTC))
    runs = _run_starts(alarm, start, earliest.astimezone(from_time.tzinfo))
    first_run = next(runs)
    if not _rings_within_run(alarm, first_run):
        return
    for run_start in chain([first_run], runs):
        day = max(earliest.astimezone(from_time.tzinfo).date(), run_start.date())
        moment = run_start if day == run_start.date() else run_start.replace(year=day.year, month=day.month, day=day.day, fold=0)
        end = _run_end(alarm, run_start).astimezone(UTC)
        while moment.astimezone(UTC) < end:
            instant = moment.astimezone(UTC)
            if instant >= earliest and _rings_on(alarm, moment.date()):
                yield instant
                earliest = instant + timedelta(microseconds=1)
            moment += timedelta(days=1)


def occurrence_cycle_index(alarm: dict, time_zone: str, trigger: datetime) -> int | None:
    if alarm["repeat_unit"] != "HOUR":
        return None
    zone = ZoneInfo(time_zone)
    start = _alarm_origin(alarm, zone)
    return (trigger.astimezone(UTC) - start.astimezone(UTC)) // timedelta(hours=alarm["repeat_interval"])


def next_alarm_trigger(alarm: dict, time_zone: str, after: datetime) -> datetime | None:
    zone = ZoneInfo(time_zone)
    earliest = (after.astimezone(UTC) + timedelta(microseconds=1)).astimezone(zone)
    trigger = next(occurrences_on_or_after(alarm, earliest), None)
    if trigger is None:
        return None
    if alarm["end_date"] is not None and trigger.astimezone(zone).date() > alarm["end_date"]:
        return None
    if alarm["end_occurrences"] is not None:
        first_day = datetime.combine(alarm["start_date"], datetime.min.time(), tzinfo=zone)
        count = alarm["end_occurrences"]
        last = next(islice(occurrences_on_or_after(alarm, first_day), count - 1, count), None)
        if last is None or trigger > last:
            return None
    return trigger
