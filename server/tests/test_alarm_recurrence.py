from datetime import UTC, date, datetime, timedelta

from jay_server.occurrences import EPOCH, next_alarm_trigger, occurrence_on_or_after


def recurring_alarm(
    start: date,
    repeat_unit: str,
    repeat_interval: int = 1,
    repeat_anchor: str = "DAY_OF_MONTH",
    repeat_duration: int | None = None,
    repeat_duration_unit: str = "DAY",
    days: list[int] | None = None,
    end_date: date | None = None,
    end_occurrences: int | None = None,
) -> dict:
    return {
        "time": 8 * 3_600_000,
        "days": days if days is not None else [0, 1, 2, 3, 4, 5, 6],
        "start_date": (start - EPOCH).days,
        "repeat_interval": repeat_interval,
        "repeat_unit": repeat_unit,
        "repeat_anchor": repeat_anchor,
        "repeat_duration": repeat_duration,
        "repeat_duration_unit": repeat_duration_unit,
        "end_date": (end_date - EPOCH).days if end_date is not None else None,
        "end_occurrences": end_occurrences,
    }


def occurrences(alarm: dict, count: int) -> list[date]:
    found: list[date] = []
    day = EPOCH + timedelta(days=alarm["start_date"])
    for _ in range(count):
        occurrence = occurrence_on_or_after(alarm, day)
        if occurrence is None:
            return found
        found.append(occurrence)
        day = occurrence + timedelta(days=1)
    return found


def test_daily_alarm_skips_the_days_of_its_interval():
    alarm = recurring_alarm(date(2099, 1, 1), "DAY", repeat_interval=3)

    assert occurrences(alarm, 3) == [date(2099, 1, 1), date(2099, 1, 4), date(2099, 1, 7)]


def test_weekly_alarm_rings_on_every_chosen_day_of_its_interval_weeks():
    alarm = recurring_alarm(date(2099, 1, 5), "WEEK", repeat_interval=2, days=[1, 3])

    assert occurrences(alarm, 3) == [date(2099, 1, 5), date(2099, 1, 7), date(2099, 1, 19)]


def test_monthly_alarm_keeps_the_day_of_the_month_of_its_start_date():
    alarm = recurring_alarm(date(2099, 1, 31), "MONTH")

    assert occurrences(alarm, 3) == [date(2099, 1, 31), date(2099, 2, 28), date(2099, 3, 31)]


def test_monthly_alarm_keeps_the_weekday_of_its_start_date():
    alarm = recurring_alarm(date(2099, 1, 19), "MONTH", repeat_anchor="DAY_OF_WEEK")

    assert occurrences(alarm, 3) == [date(2099, 1, 19), date(2099, 2, 16), date(2099, 3, 16)]


def test_yearly_alarm_keeps_the_date_of_its_start_date():
    alarm = recurring_alarm(date(2099, 3, 5), "YEAR")

    assert occurrences(alarm, 2) == [date(2099, 3, 5), date(2100, 3, 5)]


def test_yearly_alarm_keeps_the_weekday_of_its_start_date():
    alarm = recurring_alarm(date(2099, 8, 17), "YEAR", repeat_anchor="DAY_OF_WEEK")

    assert occurrences(alarm, 3) == [date(2099, 8, 17), date(2100, 8, 16), date(2101, 8, 15)]


def test_repetition_keeps_ringing_for_the_days_it_lasts_for():
    alarm = recurring_alarm(date(2099, 8, 5), "DAY", repeat_interval=6, repeat_duration=2)

    assert occurrences(alarm, 6) == [date(2099, 8, day) for day in (5, 6, 11, 12, 17, 18)]


def test_weekly_repetition_only_rings_on_chosen_days_within_its_run():
    alarm = recurring_alarm(date(2099, 8, 17), "WEEK", repeat_duration=2, days=[5, 6])

    assert occurrence_on_or_after(alarm, date(2099, 8, 17)) is None
    assert next_alarm_trigger(alarm, "UTC", datetime(2099, 8, 17, tzinfo=UTC)) is None


def test_a_run_longer_than_a_week_reaches_every_chosen_day():
    alarm = recurring_alarm(
        date(2099, 8, 17),
        "WEEK",
        repeat_duration=1,
        repeat_duration_unit="WEEK",
        days=[5, 6],
    )

    assert occurrences(alarm, 2) == [date(2099, 8, 21), date(2099, 8, 22)]


def test_the_trigger_stops_where_the_repetition_ends():
    start = date(2099, 8, 5)
    after = datetime(2099, 8, 6, 9, tzinfo=UTC)

    counted = recurring_alarm(start, "DAY", end_occurrences=2)
    assert next_alarm_trigger(counted, "UTC", after) is None

    dated = recurring_alarm(start, "DAY", end_date=date(2099, 8, 6))
    assert next_alarm_trigger(dated, "UTC", after) is None

    running = recurring_alarm(start, "DAY", end_occurrences=5)
    assert next_alarm_trigger(running, "UTC", after) == datetime(
        2099, 8, 7, 8, tzinfo=UTC
    )
