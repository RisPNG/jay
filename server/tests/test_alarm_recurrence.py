from datetime import UTC, date, datetime, timedelta
from zoneinfo import ZoneInfo

from jay_server.social.recurrence import next_alarm_trigger, occurrence_cycle_index, occurrences_on_or_after


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
    local_time_ms: int = 8 * 3_600_000,
    start_with_later_offset: bool = False,
) -> dict:
    return {
        "local_time_ms": local_time_ms,
        "days": days if days is not None else [0, 1, 2, 3, 4, 5, 6],
        "start_date": start,
        "start_with_later_offset": start_with_later_offset,
        "repeat_interval": repeat_interval,
        "repeat_unit": repeat_unit,
        "repeat_anchor": repeat_anchor,
        "repeat_duration": repeat_duration,
        "repeat_duration_unit": repeat_duration_unit,
        "end_date": end_date,
        "end_occurrences": end_occurrences,
    }


def occurrences(alarm: dict, count: int) -> list[date]:
    found: list[date] = []
    moment = datetime.combine(alarm["start_date"], datetime.min.time(), tzinfo=UTC)
    for _ in range(count):
        occurrence = next(occurrences_on_or_after(alarm, moment), None)
        if occurrence is None:
            return found
        found.append(occurrence.date())
        moment = occurrence + timedelta(microseconds=1)
    return found


def triggers(alarm: dict, time_zone: str, after: datetime, count: int) -> list[datetime]:
    found = []
    for _ in range(count):
        trigger = next_alarm_trigger(alarm, time_zone, after)
        if trigger is None:
            return found
        found.append(trigger)
        after = trigger
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

    assert next(occurrences_on_or_after(alarm, datetime(2099, 8, 17, tzinfo=UTC)), None) is None
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


def test_monthly_run_continues_across_month_boundary():
    alarm = recurring_alarm(date(2099, 1, 20), "MONTH", repeat_duration=20)
    assert next(occurrences_on_or_after(alarm, datetime(2099, 2, 1, tzinfo=UTC)), None) == datetime(2099, 2, 1, 8, tzinfo=UTC)
    assert next(occurrences_on_or_after(alarm, datetime(2099, 2, 9, tzinfo=UTC)), None) == datetime(2099, 2, 20, 8, tzinfo=UTC)


def test_yearly_run_continues_before_anniversary():
    alarm = recurring_alarm(date(2099, 12, 20), "YEAR", repeat_duration=2, repeat_duration_unit="MONTH")
    assert next(occurrences_on_or_after(alarm, datetime(2100, 1, 1, tzinfo=UTC)), None) == datetime(2100, 1, 1, 8, tzinfo=UTC)


def test_fifth_weekday_run_continues_through_month_without_anchor():
    alarm = recurring_alarm(date(2026, 1, 29), "MONTH", repeat_anchor="DAY_OF_WEEK", repeat_duration=40)
    assert next(occurrences_on_or_after(alarm, datetime(2026, 2, 20, tzinfo=UTC)), None) == datetime(2026, 2, 20, 8, tzinfo=UTC)
    assert next(occurrences_on_or_after(alarm, datetime(2026, 3, 1, tzinfo=UTC)), None) == datetime(2026, 3, 1, 8, tzinfo=UTC)


def test_hourly_alarm_rings_each_hour_on_the_start_day():
    alarm = recurring_alarm(date(2099, 1, 1), "HOUR")
    assert triggers(alarm, "UTC", datetime(2099, 1, 1, 7, tzinfo=UTC), 3) == [datetime(2099, 1, 1, hour, tzinfo=UTC) for hour in (8, 9, 10)]


def test_hourly_intervals_continue_across_midnight():
    alarm = recurring_alarm(date(2099, 1, 1), "HOUR", repeat_interval=5)
    assert triggers(alarm, "UTC", datetime(2099, 1, 1, 22, tzinfo=UTC), 3) == [datetime(2099, 1, 1, 23, tzinfo=UTC), datetime(2099, 1, 2, 4, tzinfo=UTC), datetime(2099, 1, 2, 9, tzinfo=UTC)]


def test_hourly_interval_can_exceed_one_day_without_daily_extra_rings():
    alarm = recurring_alarm(date(2099, 1, 1), "HOUR", repeat_interval=30, end_occurrences=3)
    assert triggers(alarm, "UTC", datetime(2099, 1, 2, tzinfo=UTC), 3) == [datetime(2099, 1, 2, 14, tzinfo=UTC), datetime(2099, 1, 3, 20, tzinfo=UTC)]


def test_hourly_end_count_counts_rings_within_one_day():
    alarm = recurring_alarm(date(2099, 1, 1), "HOUR", end_occurrences=3)
    assert triggers(alarm, "UTC", datetime(2099, 1, 1, 7, tzinfo=UTC), 5) == [datetime(2099, 1, 1, hour, tzinfo=UTC) for hour in (8, 9, 10)]


def test_hourly_end_date_includes_the_last_local_day():
    alarm = recurring_alarm(date(2099, 1, 1), "HOUR", repeat_interval=2, local_time_ms=23 * 3_600_000, end_date=date(2099, 1, 2))
    assert next_alarm_trigger(alarm, "UTC", datetime(2099, 1, 2, 22, tzinfo=UTC)) == datetime(2099, 1, 2, 23, tzinfo=UTC)
    assert next_alarm_trigger(alarm, "UTC", datetime(2099, 1, 2, 23, tzinfo=UTC)) is None


def test_hourly_run_does_not_inherit_a_calendar_run_duration():
    alarm = recurring_alarm(date(2099, 1, 1), "HOUR", repeat_duration=3, repeat_duration_unit="MONTH")
    assert triggers(alarm, "UTC", datetime(2099, 1, 1, 7, tzinfo=UTC), 3) == [datetime(2099, 1, 1, hour, tzinfo=UTC) for hour in (8, 9, 10)]


def test_hourly_cadence_keeps_elapsed_hours_across_the_spring_gap():
    alarm = recurring_alarm(date(2026, 3, 8), "HOUR", local_time_ms=90 * 60_000)
    assert triggers(alarm, "America/New_York", datetime(2026, 3, 8, 6, tzinfo=UTC), 3) == [datetime(2026, 3, 8, hour, 30, tzinfo=UTC) for hour in (6, 7, 8)]


def test_hourly_cadence_rings_in_both_offsets_of_the_autumn_overlap():
    alarm = recurring_alarm(date(2026, 11, 1), "HOUR", local_time_ms=30 * 60_000)
    assert triggers(alarm, "America/New_York", datetime(2026, 11, 1, 4, tzinfo=UTC), 4) == [datetime(2026, 11, 1, hour, 30, tzinfo=UTC) for hour in (4, 5, 6, 7)]
    zone = ZoneInfo("America/New_York")
    assert next_alarm_trigger(alarm, "America/New_York", datetime(2026, 11, 1, 1, 45, tzinfo=zone, fold=0)) == datetime(2026, 11, 1, 6, 30, tzinfo=UTC)
    assert next_alarm_trigger(alarm, "America/New_York", datetime(2026, 11, 1, 1, 15, tzinfo=zone, fold=1)) == datetime(2026, 11, 1, 6, 30, tzinfo=UTC)


def test_calendar_alarm_keeps_its_wall_time_after_a_gap():
    alarm = recurring_alarm(date(2026, 3, 7), "DAY", local_time_ms=150 * 60_000)
    assert triggers(alarm, "America/New_York", datetime(2026, 3, 7, tzinfo=UTC), 3) == [datetime(2026, 3, 7, 7, 30, tzinfo=UTC), datetime(2026, 3, 8, 7, 30, tzinfo=UTC), datetime(2026, 3, 9, 6, 30, tzinfo=UTC)]


def test_hourly_origin_resolves_a_gap_by_the_transition_length():
    for later_offset in (False, True):
        alarm = recurring_alarm(date(2026, 10, 4), "HOUR", local_time_ms=135 * 60_000, start_with_later_offset=later_offset)
        assert triggers(alarm, "Australia/Lord_Howe", datetime(2026, 10, 3, 15, tzinfo=UTC), 2) == [datetime(2026, 10, 3, 15, 45, tzinfo=UTC), datetime(2026, 10, 3, 16, 45, tzinfo=UTC)]
        assert occurrence_cycle_index(alarm, "Australia/Lord_Howe", datetime(2026, 10, 3, 15, 45, tzinfo=UTC)) == 0


def test_later_offset_origin_preserves_a_rearmed_hourly_phase():
    alarm = recurring_alarm(date(2026, 11, 1), "HOUR", local_time_ms=90 * 60_000, start_with_later_offset=True, end_occurrences=2)
    assert triggers(alarm, "America/New_York", datetime(2026, 11, 1, 5, tzinfo=UTC), 3) == [datetime(2026, 11, 1, 6, 30, tzinfo=UTC), datetime(2026, 11, 1, 7, 30, tzinfo=UTC)]
    assert occurrence_cycle_index(alarm, "America/New_York", datetime(2026, 11, 1, 6, 30, tzinfo=UTC)) == 0


def test_calendar_day_and_week_preserve_the_stored_origin_later_offset():
    for unit in ("DAY", "WEEK"):
        alarm = recurring_alarm(date(2026, 11, 1), unit, local_time_ms=90 * 60_000, start_with_later_offset=True)
        assert next_alarm_trigger(alarm, "America/New_York", datetime(2026, 11, 1, 5, tzinfo=UTC)) == datetime(2026, 11, 1, 6, 30, tzinfo=UTC)
        assert next_alarm_trigger(alarm, "America/New_York", datetime(2027, 11, 7, tzinfo=UTC)) == datetime(2027, 11, 7, 5, 30, tzinfo=UTC)


def test_future_calendar_month_and_year_overlaps_use_the_earlier_offset():
    for unit in ("MONTH", "YEAR"):
        alarm = recurring_alarm(date(2026, 11, 1), unit, repeat_anchor="DAY_OF_WEEK", local_time_ms=90 * 60_000, start_with_later_offset=True)
        assert next_alarm_trigger(alarm, "America/New_York", datetime(2026, 11, 1, 5, tzinfo=UTC)) == datetime(2026, 11, 1, 6, 30, tzinfo=UTC)
        assert next_alarm_trigger(alarm, "America/New_York", datetime(2027, 11, 7, tzinfo=UTC)) == datetime(2027, 11, 7, 5, 30, tzinfo=UTC)


def test_calendar_run_ends_at_the_earlier_offset_of_its_exclusive_boundary():
    alarm = recurring_alarm(date(2020, 11, 1), "YEAR", repeat_interval=7, repeat_duration=6, repeat_duration_unit="YEAR", local_time_ms=90 * 60_000, start_with_later_offset=True)
    assert next_alarm_trigger(alarm, "America/New_York", datetime(2026, 11, 1, tzinfo=UTC)) == datetime(2027, 11, 1, 5, 30, tzinfo=UTC)


def test_hourly_cadence_preserves_later_folds_after_the_origin_date():
    alarm = recurring_alarm(date(2026, 1, 1), "HOUR", local_time_ms=30 * 60_000)
    assert triggers(alarm, "America/New_York", datetime(2026, 11, 1, 5, tzinfo=UTC), 3) == [datetime(2026, 11, 1, hour, 30, tzinfo=UTC) for hour in (5, 6, 7)]


def test_overlapping_calendar_runs_do_not_count_the_same_ring_twice():
    alarm = recurring_alarm(date(2099, 1, 1), "DAY", repeat_interval=2, repeat_duration=3, end_occurrences=4)
    assert triggers(alarm, "UTC", datetime(2099, 1, 1, tzinfo=UTC), 5) == [datetime(2099, 1, day, 8, tzinfo=UTC) for day in (1, 2, 3, 4)]


def test_hourly_cycle_identity_matches_relative_rings_across_member_zones():
    alarm = recurring_alarm(date(2099, 1, 1), "HOUR")
    assert occurrence_cycle_index(alarm, "America/New_York", datetime(2099, 1, 1, 15, tzinfo=UTC)) == 2
    assert occurrence_cycle_index(alarm, "Asia/Tokyo", datetime(2099, 1, 1, 1, tzinfo=UTC)) == 2
    assert occurrence_cycle_index(alarm, "America/New_York", datetime(2099, 1, 1, 16, tzinfo=UTC)) == 3
    assert occurrence_cycle_index(recurring_alarm(date(2099, 1, 1), "DAY"), "UTC", datetime(2099, 1, 1, 8, tzinfo=UTC)) is None


def test_trigger_is_strictly_after_the_supplied_instant():
    alarm = recurring_alarm(date(2099, 1, 1), "HOUR")
    trigger = datetime(2099, 1, 1, 8, tzinfo=UTC)
    assert next_alarm_trigger(alarm, "UTC", trigger - timedelta(microseconds=1)) == trigger
    assert next_alarm_trigger(alarm, "UTC", trigger) == trigger + timedelta(hours=1)
