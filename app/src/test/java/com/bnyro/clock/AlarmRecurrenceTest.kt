package com.bnyro.clock

import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.RepeatAnchor
import com.bnyro.clock.domain.model.RepeatUnit
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.util.AlarmHelper
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmRecurrenceTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun hourlyOccurrencesKeepTheirMinuteAndContinueWithinTheSameDay() {
        val alarm = Alarm(
            time = 8 * 3_600_000L + 15 * 60_000L,
            startDate = LocalDate.of(2026, 1, 1).toEpochDay(),
            repeatUnit = RepeatUnit.HOUR,
            repeatInterval = 3,
            advanced = true
        )
        val from = LocalDate.of(2026, 1, 1).atTime(10, 30).atZone(utc)
        val next = LocalDate.of(2026, 1, 1).atTime(11, 15).atZone(utc)

        assertEquals(next, AlarmHelper.occurrenceOnOrAfter(alarm, from))
        assertEquals(next, AlarmHelper.occurrenceOnOrAfter(alarm, next))
        assertEquals(
            next.plusHours(3),
            AlarmHelper.getNextOccurrence(alarm, timeZone = utc, clock = Clock.fixed(next.toInstant(), utc))
        )
    }

    @Test
    fun hourlyOccurrencesCrossMidnightWithoutResettingTheirPhase() {
        val alarm = Alarm(
            time = 23 * 3_600_000L + 15 * 60_000L,
            startDate = LocalDate.of(2026, 1, 1).toEpochDay(),
            repeatUnit = RepeatUnit.HOUR,
            repeatInterval = 2
        )
        val from = LocalDate.of(2026, 1, 2).atStartOfDay(utc)

        assertEquals(from.withHour(1).withMinute(15), AlarmHelper.occurrenceOnOrAfter(alarm, from))
    }

    @Test
    fun intervalsLongerThanADayDoNotBecomeDailyAlarms() {
        val alarm = Alarm(
            time = 8 * 3_600_000L,
            startDate = LocalDate.of(2026, 1, 1).toEpochDay(),
            repeatUnit = RepeatUnit.HOUR,
            repeatInterval = 25
        )
        val from = LocalDate.of(2026, 1, 2).atTime(8, 30).atZone(utc)
        val next = from.withHour(9).withMinute(0)

        assertEquals(next, AlarmHelper.occurrenceOnOrAfter(alarm, from))
        assertEquals(next.plusHours(25), AlarmHelper.occurrenceOnOrAfter(alarm, next.plusNanos(1)))
    }

    @Test
    fun hourlyCadenceIgnoresRetainedCalendarRunDuration() {
        val alarm = Alarm(
            time = 8 * 3_600_000L,
            startDate = LocalDate.of(2026, 1, 1).toEpochDay(),
            repeatUnit = RepeatUnit.HOUR,
            repeatInterval = 3,
            repeatDuration = 4,
            repeatDurationUnit = RepeatUnit.DAY
        )
        val from = LocalDate.of(2026, 1, 1).atTime(10, 0).atZone(utc)

        assertEquals(from.withHour(11), AlarmHelper.occurrenceOnOrAfter(alarm, from))
        assertEquals(from.withHour(8), AlarmHelper.getRepetitionLastOccurrence(alarm, utc))
        assertEquals(from.withHour(11), AlarmHelper.getFollowingRepetitionOccurrence(alarm, utc))
    }

    @Test
    fun dismissingUpcomingHourlyAlarmSkipsOnlyThatOccurrence() {
        val now = LocalDate.of(2026, 1, 1).atTime(9, 0).atZone(utc)
        val alarm = Alarm(
            time = 8 * 3_600_000L + 15 * 60_000L,
            startDate = now.toLocalDate().toEpochDay(),
            repeatUnit = RepeatUnit.HOUR,
            dismissedAt = now.withMinute(15).toInstant().toEpochMilli()
        )

        assertEquals(
            now.withHour(10).withMinute(15),
            AlarmHelper.getNextOccurrence(alarm, timeZone = utc, clock = Clock.fixed(now.toInstant(), utc))
        )
    }

    @Test
    fun dismissActionDoesNotSkipAnotherHourlyOccurrenceButStillSkipsCalendarToday() {
        val now = LocalDate.of(2026, 1, 1).atTime(9, 0).atZone(utc)
        val alarm = Alarm(time = 10 * 3_600_000L, startDate = now.toLocalDate().toEpochDay(), repeatUnit = RepeatUnit.HOUR)
        val clock = Clock.fixed(now.toInstant(), utc)

        assertEquals(now.withHour(10), AlarmHelper.getNextOccurrence(alarm, skipToday = true, timeZone = utc, clock = clock))
        alarm.repeatUnit = RepeatUnit.DAY
        assertEquals(now.withHour(10), AlarmHelper.getNextOccurrence(alarm, timeZone = utc, clock = clock))
        assertEquals(now.plusDays(1).withHour(10), AlarmHelper.getNextOccurrence(alarm, skipToday = true, timeZone = utc, clock = clock))
    }

    @Test
    fun finiteHourlyOccurrenceCountCanEndWithinOneDay() {
        val start = LocalDate.of(2026, 1, 1).atTime(8, 0).atZone(utc)
        val alarm = Alarm(time = 8 * 3_600_000L, startDate = start.toLocalDate().toEpochDay(), repeatUnit = RepeatUnit.HOUR, endOccurrences = 3)

        assertFalse(AlarmHelper.hasRecurrenceEnded(alarm, utc, Clock.fixed(start.plusMinutes(90).toInstant(), utc)))
        assertTrue(AlarmHelper.hasRecurrenceEnded(alarm, utc, Clock.fixed(start.plusHours(2).toInstant(), utc)))
    }

    @Test
    fun hourlyEndDateIncludesTheLastEveningOccurrence() {
        val start = LocalDate.of(2026, 1, 1).atTime(23, 0).atZone(utc)
        val alarm = Alarm(
            time = 23 * 3_600_000L,
            startDate = start.toLocalDate().toEpochDay(),
            repeatUnit = RepeatUnit.HOUR,
            repeatInterval = 2,
            endDate = start.toLocalDate().plusDays(1).toEpochDay()
        )

        assertFalse(AlarmHelper.hasRecurrenceEnded(alarm, utc, Clock.fixed(start.plusHours(23).toInstant(), utc)))
        assertTrue(AlarmHelper.hasRecurrenceEnded(alarm, utc, Clock.fixed(start.plusHours(24).toInstant(), utc)))
    }

    @Test
    fun snoozedFinalOccurrenceKeepsItsScheduledSnoozeTime() {
        val now = LocalDate.of(2026, 1, 1).atTime(9, 0).atZone(utc)
        val snoozedUntil = now.plusMinutes(15).toInstant().toEpochMilli()
        val alarm = Alarm(
            time = 8 * 3_600_000L,
            startDate = now.toLocalDate().toEpochDay(),
            repeatUnit = RepeatUnit.HOUR,
            endOccurrences = 1,
            snoozedUntil = snoozedUntil
        )

        assertTrue(AlarmHelper.hasRecurrenceEnded(alarm, utc, Clock.fixed(now.toInstant(), utc)))
        assertEquals(snoozedUntil, AlarmHelper.getAlarmTime(alarm, timeZone = utc, clock = Clock.fixed(now.toInstant(), utc)))
    }

    @Test
    fun hourlySpringTransitionKeepsAnElapsedHourBetweenRings() {
        val zone = ZoneId.of("America/New_York")
        val first = LocalDate.of(2026, 3, 8).atTime(1, 30).atZone(zone)
        val alarm = Alarm(time = 3_600_000L + 30 * 60_000L, startDate = first.toLocalDate().toEpochDay(), repeatUnit = RepeatUnit.HOUR)
        val next = requireNotNull(AlarmHelper.occurrenceOnOrAfter(alarm, first.plusMinutes(1)))

        assertEquals(first.toInstant().plusSeconds(3_600), next.toInstant())
        assertEquals(3, next.hour)
        assertEquals(30, next.minute)
    }

    @Test
    fun hourlyAutumnTransitionCanRingAtBothOffsetsOfTheSameLocalTime() {
        val zone = ZoneId.of("America/New_York")
        val start = LocalDate.of(2026, 11, 1).atTime(0, 30).atZone(zone)
        val alarm = Alarm(time = 30 * 60_000L, startDate = start.toLocalDate().toEpochDay(), repeatUnit = RepeatUnit.HOUR)
        val first = requireNotNull(AlarmHelper.occurrenceOnOrAfter(alarm, start.plusMinutes(1)))
        val second = requireNotNull(AlarmHelper.occurrenceOnOrAfter(alarm, first.plusMinutes(1)))

        assertEquals(first.toLocalDateTime(), second.toLocalDateTime())
        assertEquals(first.toInstant().plusSeconds(3_600), second.toInstant())
        assertEquals(first.withLaterOffsetAtOverlap(), second)
    }

    @Test
    fun rearmingAnHourlyAlarmAdvancesItsDateAndTimeWithoutShiftingTheCadence() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val useCase = CreateUpdateDeleteAlarmUseCase(app, app.container.alarmRepository)
        val now = LocalDate.of(2026, 1, 2).atTime(8, 0).atZone(utc)
        val alarm = Alarm(
            time = 8 * 3_600_000L,
            startDate = now.toLocalDate().minusDays(1).toEpochDay(),
            repeatUnit = RepeatUnit.HOUR,
            repeatInterval = 25,
            endOccurrences = 3,
            advanced = true,
            enabled = true
        )
        val clock = Clock.fixed(now.toInstant(), utc)

        useCase.prepareForScheduling(alarm, utc, clock)

        assertEquals(now.toLocalDate().toEpochDay(), alarm.startDate)
        assertEquals(9 * 3_600_000L, alarm.time)
        assertTrue(alarm.enabled)
        assertEquals(now.withHour(9), AlarmHelper.getNextOccurrence(alarm, timeZone = utc, clock = clock))
        assertEquals(now.withHour(9).plusHours(25), AlarmHelper.occurrenceOnOrAfter(alarm, now.withHour(9).plusMinutes(1)))
    }

    @Test
    fun rearmingAtTheLaterAutumnOffsetPersistsTheExactHourlyOrigin() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val useCase = CreateUpdateDeleteAlarmUseCase(app, app.container.alarmRepository)
        val zone = ZoneId.of("America/New_York")
        val now = LocalDate.of(2026, 11, 1).atTime(1, 45).atZone(zone)
        val next = now.withMinute(30).withLaterOffsetAtOverlap()
        val alarm = Alarm(
            time = 30 * 60_000L,
            startDate = now.toLocalDate().toEpochDay(),
            repeatUnit = RepeatUnit.HOUR,
            repeatInterval = 2,
            endOccurrences = 3,
            advanced = true,
            enabled = true
        )
        val clock = Clock.fixed(now.toInstant(), zone)

        useCase.prepareForScheduling(alarm, zone, clock)
        val restored = Json.decodeFromString<Alarm>(Json.encodeToString(alarm))

        assertEquals(3_600_000L + 30 * 60_000L, restored.time)
        assertTrue(restored.startWithLaterOffset)
        assertEquals(next, AlarmHelper.getNextOccurrence(restored, timeZone = zone, clock = clock))
        assertEquals(next.plusHours(2), AlarmHelper.occurrenceOnOrAfter(restored, next.plusMinutes(1)))
    }

    @Test
    fun dailyWallTimeReturnsToItsOriginalHourAfterASpringGap() {
        val zone = ZoneId.of("America/New_York")
        val alarm = Alarm(
            time = 2 * 3_600_000L + 30 * 60_000L,
            startDate = LocalDate.of(2026, 3, 7).toEpochDay(),
            repeatUnit = RepeatUnit.DAY
        )
        val from = LocalDate.of(2026, 3, 8).atStartOfDay(zone)
        val gapDay = requireNotNull(AlarmHelper.occurrenceOnOrAfter(alarm, from))

        assertEquals(from.withHour(3).withMinute(30), gapDay)
        assertEquals(LocalDate.of(2026, 3, 9).atTime(2, 30).atZone(zone), AlarmHelper.occurrenceOnOrAfter(alarm, gapDay.plusMinutes(1)))
    }

    @Test
    fun monthlyDayOfMonthRetainsItsOriginalAnchorAfterAShortMonth() {
        val alarm = Alarm(time = 8 * 3_600_000L, startDate = LocalDate.of(2026, 1, 31).toEpochDay(), repeatUnit = RepeatUnit.MONTH)
        val from = LocalDate.of(2026, 2, 1).atStartOfDay(utc)
        val february = LocalDate.of(2026, 2, 28).atTime(8, 0).atZone(utc)

        assertEquals(february, AlarmHelper.occurrenceOnOrAfter(alarm, from))
        assertEquals(LocalDate.of(2026, 3, 31).atTime(8, 0).atZone(utc), AlarmHelper.occurrenceOnOrAfter(alarm, february.plusMinutes(1)))
    }

    @Test
    fun monthlyFifthWeekdaySkipsMonthsWithoutThatWeekday() {
        val alarm = Alarm(
            time = 8 * 3_600_000L,
            startDate = LocalDate.of(2025, 3, 31).toEpochDay(),
            repeatUnit = RepeatUnit.MONTH,
            repeatAnchor = RepeatAnchor.DAY_OF_WEEK
        )

        assertEquals(
            LocalDate.of(2025, 6, 30).atTime(8, 0).atZone(utc),
            AlarmHelper.occurrenceOnOrAfter(alarm, LocalDate.of(2025, 4, 1).atStartOfDay(utc))
        )
    }

    @Test
    fun weeklyRunsRetainTheirDurationAndSelectedWeekdays() {
        val start = LocalDate.of(2026, 1, 5).atTime(8, 0).atZone(utc)
        val alarm = Alarm(
            time = 8 * 3_600_000L,
            startDate = start.toLocalDate().toEpochDay(),
            repeatUnit = RepeatUnit.WEEK,
            repeatInterval = 2,
            repeatDuration = 3,
            repeatDurationUnit = RepeatUnit.DAY,
            days = listOf(1, 3)
        )

        assertEquals(start.plusDays(2), AlarmHelper.occurrenceOnOrAfter(alarm, start.plusDays(1)))
        assertEquals(start.plusDays(2), AlarmHelper.getRepetitionLastOccurrence(alarm, utc))
        assertEquals(start.plusWeeks(2), AlarmHelper.getFollowingRepetitionOccurrence(alarm, utc))
        assertNull(AlarmHelper.occurrenceOnOrAfter(alarm.copy(days = emptyList()), start))
    }
}
