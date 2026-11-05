package com.bnyro.clock.util

import android.annotation.SuppressLint
import androidx.core.app.NotificationManagerCompat
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.bnyro.clock.App
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.RepeatAnchor
import com.bnyro.clock.domain.model.Permission
import com.bnyro.clock.domain.model.RepeatUnit
import com.bnyro.clock.domain.model.WeekStart
import com.bnyro.clock.ui.MainActivity
import com.bnyro.clock.util.receivers.AlarmReceiver
import com.bnyro.clock.util.receivers.PreAlarmReceiver
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import kotlin.time.Duration.Companion.milliseconds
//schweiny ass file
object AlarmHelper {
    const val EXTRA_ID = "alarm_id"
    const val EXTRA_TRIGGER_TIME = "alarm_trigger_time"
    private const val DAYS_PER_WEEK = 7
    private const val MONTHS_PER_YEAR = 12
    const val PRE_ALARM_ID_OFFSET = 4000

    fun showAlarmScheduledToast(context: Context, alarm: Alarm) {
        val alarmTime = getAlarmTime(alarm) ?: return
        val millisRemaining = alarmTime - System.currentTimeMillis()
        Toast.makeText(
            context,
            if (millisRemaining <= 0) {
                context.getString(R.string.alarm_starting_now)
            } else {
                context.getString(
                    R.string.alarm_will_play,
                    TimeHelper.durationToFormatted(context, millisRemaining.milliseconds)
                )
            },
            Toast.LENGTH_SHORT
        ).show()
    }
    fun getPreAlarmDelayMillis(): Long {
        val minutes = Preferences.instance.getInt(
            Preferences.upcomingAlarmDuration,
            Preferences.DEFAULT_UPCOMING_ALARM_DURATION
        )
        return minutes * 60 * 1000L
    }

    fun enqueue(
        context: Context,
        alarm: Alarm,
        skipToday: Boolean = false,
        timeZone: ZoneId = ZoneId.systemDefault()
    ) {
        if (!Permission.AlarmPermission.hasPermission(context)) return
        cancel(context, alarm)
        if (!alarm.enabled && alarm.snoozedUntil == null) {
            Log.d("AlarmHelper", "Alarm Is disabled")
            return
        }
        if (alarm.snoozedUntil == null && hasRecurrenceEnded(alarm, timeZone)) {
            Log.d("AlarmHelper", "Alarm has no occurrence left")
            return
        }

        schedule(context, alarm, getAlarmTime(alarm, skipToday, timeZone) ?: return)
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun schedule(context: Context, alarm: Alarm, triggerTime: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val alarmInfo = AlarmManager.AlarmClockInfo(
            triggerTime,
            getOpenAppIntent(context, alarm)
        )

        Log.d("AlarmHelper", "Scheduling alarm time: ${Date(triggerTime)}")
        alarmManager.setAlarmClock(alarmInfo, getPendingIntent(context, alarm, triggerTime))

        val preAlarmDelay = getPreAlarmDelayMillis()
        val preAlarmTime = triggerTime - preAlarmDelay
        val now = System.currentTimeMillis()

        if (alarm.snoozedUntil != null) {
            context.sendBroadcast(
                Intent(context, PreAlarmReceiver::class.java).putExtra(EXTRA_ID, alarm.id)
            )
        } else if (preAlarmTime > now) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                preAlarmTime,
                getPreAlarmPendingIntent(context, alarm)
            )
        } else if (triggerTime > now) {
            val intent = Intent(context.applicationContext, PreAlarmReceiver::class.java).apply {
                putExtra(EXTRA_ID, alarm.id)
            }
            context.sendBroadcast(intent)
        }
    }

    fun cancel(context: Context, alarm: Alarm) {
        NotificationManagerCompat.from(context).cancel(alarm.id.toInt() + PRE_ALARM_ID_OFFSET)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(getPendingIntent(context, alarm))
        alarmManager.cancel(getPreAlarmPendingIntent(context, alarm))
    }

    fun cancel(context: Context, id: Long) {
        NotificationManagerCompat.from(context).cancel(id.toInt() + PRE_ALARM_ID_OFFSET)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val mainIntent = Intent(context.applicationContext,
            AlarmReceiver::class.java).putExtra(EXTRA_ID, id)




        val mainPi = PendingIntent.getBroadcast(context.applicationContext,
            id.toInt(), mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val preIntent = Intent(context.applicationContext,
            PreAlarmReceiver::class.java).putExtra(EXTRA_ID, id)





        val prePi = PendingIntent.getBroadcast(context.applicationContext,
            id.toInt() + PRE_ALARM_ID_OFFSET, preIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        alarmManager.cancel(mainPi)
        alarmManager.cancel(prePi)
    }

    private fun getPendingIntent(context: Context, alarm: Alarm, triggerTime: Long? = null): PendingIntent {
        val intent = Intent(context.applicationContext,
            AlarmReceiver::class.java).putExtra(EXTRA_ID, alarm.id).apply {
                triggerTime?.let { putExtra(EXTRA_TRIGGER_TIME, it) }
            }
        return PendingIntent.getBroadcast(context.applicationContext, alarm.id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }


    private fun getPreAlarmPendingIntent(context: Context, alarm: Alarm): PendingIntent {
        val intent = Intent(context.applicationContext,
            PreAlarmReceiver::class.java).putExtra(EXTRA_ID, alarm.id)
        return PendingIntent.getBroadcast(context.applicationContext,
            alarm.id.toInt() + PRE_ALARM_ID_OFFSET, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }


    private fun getOpenAppIntent(context: Context, alarm: Alarm): PendingIntent {
        val intent = Intent(context.applicationContext,
            MainActivity::class.java).putExtra(EXTRA_ID, alarm.id)
        return PendingIntent.getActivity(context.applicationContext,
            alarm.id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    /**
     * Calculate the epoch time for scheduling an alarm
     */

    fun getAlarmTime(
        alarm: Alarm,
        skipToday: Boolean = false,
        timeZone: ZoneId = ZoneId.systemDefault(),
        clock: Clock = Clock.system(timeZone)
    ): Long? {
        alarm.snoozedUntil?.let { return it }
        return getNextOccurrence(alarm, skipToday, timeZone, clock)?.toInstant()?.toEpochMilli()
    }

    /** @return the next scheduled occurrence after the current minute, excluding an occurrence dismissed in advance. */
    fun getNextOccurrence(
        alarm: Alarm,
        skipToday: Boolean = false,
        timeZone: ZoneId = ZoneId.systemDefault(),
        clock: Clock = Clock.system(timeZone)
    ): ZonedDateTime? {
        val now = ZonedDateTime.now(clock.withZone(timeZone))
        val skipCalendarDay = skipToday && alarm.repeatUnit != RepeatUnit.HOUR
        val earliest = if (skipCalendarDay) {
            now.toLocalDate().plusDays(1).atStartOfDay(timeZone)
        } else {
            now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        }
        val occurrence = occurrenceOnOrAfter(alarm, earliest) ?: return null
        if (skipCalendarDay || occurrence.toInstant().toEpochMilli() != alarm.dismissedAt) return occurrence
        return occurrenceOnOrAfter(alarm, occurrence.plusNanos(1))
    }

    /** @return the next occurrence for a one-time alarm, or the start of the run containing the next occurrence. */
    fun getNextRepetitionStart(
        alarm: Alarm,
        timeZone: ZoneId = ZoneId.systemDefault(),
        clock: Clock = Clock.system(timeZone)
    ): ZonedDateTime? = getNextOccurrence(alarm, timeZone = timeZone, clock = clock)
        ?.let { occurrence ->
            if (alarm.isOneTime) occurrence else runStartsOnOrBefore(alarm, occurrence).first()
        }

    /** @return the final occurrence in the alarm's first run, or null when that run cannot ring. */
    fun getRepetitionLastOccurrence(
        alarm: Alarm,
        timeZone: ZoneId = ZoneId.systemDefault()
    ): ZonedDateTime? {
        val (hours, minutes, _, _) = TimeHelper.millisToTime(alarm.time)
        var origin = LocalDate.ofEpochDay(alarm.startDate).atTime(hours, minutes).atZone(timeZone)
        if (alarm.startWithLaterOffset) origin = origin.withLaterOffsetAtOverlap()
        return occurrencesWithinRun(alarm, origin, origin).lastOrNull()
    }

    /** @return the first occurrence after the alarm's first run, or null when its repetitions cannot ring. */
    fun getFollowingRepetitionOccurrence(
        alarm: Alarm,
        timeZone: ZoneId = ZoneId.systemDefault()
    ): ZonedDateTime? {
        val (hours, minutes, _, _) = TimeHelper.millisToTime(alarm.time)
        var origin = LocalDate.ofEpochDay(alarm.startDate).atTime(hours, minutes).atZone(timeZone)
        if (alarm.startWithLaterOffset) origin = origin.withLaterOffsetAtOverlap()
        if (occurrencesWithinRun(alarm, origin, origin).none()) return null
        return occurrenceOnOrAfter(alarm, runEnd(alarm, origin))
    }

    /** @return whether the next scheduled occurrence lies beyond the alarm's inclusive end date or occurrence count. */
    fun hasRecurrenceEnded(
        alarm: Alarm,
        timeZone: ZoneId = ZoneId.systemDefault(),
        clock: Clock = Clock.system(timeZone)
    ): Boolean {
        val nextOccurrence = getNextOccurrence(alarm, timeZone = timeZone, clock = clock) ?: return false
        alarm.endDate?.let { if (nextOccurrence.toLocalDate() > LocalDate.ofEpochDay(it)) return true }
        alarm.endOccurrences?.let { count ->
            val last = lastOccurrence(alarm, count, timeZone) ?: return false
            return nextOccurrence.isAfter(last)
        }
        return false
    }

    /** @return the first scheduled occurrence at or after [from], independent of snoozing and recurrence end limits. */
    internal fun occurrenceOnOrAfter(alarm: Alarm, from: ZonedDateTime): ZonedDateTime? {
        val runStarts = runStartsOnOrBefore(alarm, from)
        val firstRun = runStarts.first()
        if (occurrencesWithinRun(alarm, firstRun, firstRun).none()) return null
        return runStarts.firstNotNullOf { runStart ->
            occurrencesWithinRun(alarm, runStart, from).firstOrNull()
        }
    }

    /** @return run starts beginning with the most recent run on or before [from], or the original start when [from] precedes it. */
    private fun runStartsOnOrBefore(alarm: Alarm, from: ZonedDateTime): Sequence<ZonedDateTime> {
        val startDate = LocalDate.ofEpochDay(alarm.startDate)
        val (hours, minutes, _, _) = TimeHelper.millisToTime(alarm.time)
        val time = LocalTime.of(hours, minutes)
        var origin = startDate.atTime(time).atZone(from.zone)
        if (alarm.startWithLaterOffset) origin = origin.withLaterOffsetAtOverlap()
        val interval = alarm.repeatInterval.toLong()

        return when (alarm.repeatUnit) {
            RepeatUnit.HOUR -> {
                val elapsed = (ChronoUnit.HOURS.between(origin, from) / interval).coerceAtLeast(0)
                generateSequence(elapsed) { it + 1 }.map { origin.plusHours(it * interval) }
            }

            RepeatUnit.DAY, RepeatUnit.WEEK -> {
                val days = if (alarm.repeatUnit == RepeatUnit.WEEK) interval * DAYS_PER_WEEK else interval
                val elapsed = (ChronoUnit.DAYS.between(startDate, from.toLocalDate()) / days).coerceAtLeast(0)
                generateSequence(elapsed) { it + 1 }.map {
                    val date = startDate.plusDays(it * days)
                    if (date == startDate) origin else date.atTime(time).atZone(from.zone)
                }
            }

            RepeatUnit.MONTH, RepeatUnit.YEAR -> {
                val startMonth = YearMonth.from(startDate)
                val weekOfMonth = (startDate.dayOfMonth - 1) / DAYS_PER_WEEK + 1
                val months = if (alarm.repeatUnit == RepeatUnit.YEAR) interval * MONTHS_PER_YEAR else interval
                val elapsed = (ChronoUnit.MONTHS.between(startMonth, YearMonth.from(from)) / months).coerceAtLeast(0)
                val lastRun = generateSequence(startMonth.plusMonths(elapsed * months)) {
                    it.minusMonths(months).takeIf { previous -> previous >= startMonth }
                }.mapNotNull { month ->
                    val day = when (alarm.repeatAnchor) {
                        RepeatAnchor.DAY_OF_MONTH -> month.atDay(minOf(startDate.dayOfMonth, month.lengthOfMonth()))
                        RepeatAnchor.DAY_OF_WEEK -> month.atDay(1).with(
                            TemporalAdjusters.dayOfWeekInMonth(weekOfMonth, startDate.dayOfWeek)
                        )
                    }
                    day.takeIf { YearMonth.from(it) == month }
                }.firstOrNull { it <= from.toLocalDate() } ?: startDate
                generateSequence(YearMonth.from(lastRun)) { it.plusMonths(months) }.mapNotNull { month ->
                    val day = when (alarm.repeatAnchor) {
                        RepeatAnchor.DAY_OF_MONTH -> month.atDay(minOf(startDate.dayOfMonth, month.lengthOfMonth()))
                        RepeatAnchor.DAY_OF_WEEK -> month.atDay(1).with(
                            TemporalAdjusters.dayOfWeekInMonth(weekOfMonth, startDate.dayOfWeek)
                        )
                    }
                    day.takeIf { YearMonth.from(it) == month }?.let {
                        if (it == startDate) origin else it.atTime(time).atZone(from.zone)
                    }
                }
            }
        }
    }

    /** @return the exclusive end of a run; hourly runs contain only their starting instant. */
    private fun runEnd(alarm: Alarm, runStart: ZonedDateTime): ZonedDateTime {
        if (alarm.repeatUnit == RepeatUnit.HOUR) return runStart.plusNanos(1)
        val duration = alarm.repeatDuration?.toLong()
        val endDate = if (duration == null) {
            if (alarm.repeatUnit == RepeatUnit.WEEK) runStart.toLocalDate().plusWeeks(1) else runStart.toLocalDate().plusDays(1)
        } else {
            when (alarm.repeatDurationUnit) {
                RepeatUnit.HOUR -> return runStart.plusHours(duration)
                RepeatUnit.DAY -> runStart.toLocalDate().plusDays(duration)
                RepeatUnit.WEEK -> runStart.toLocalDate().plusWeeks(duration)
                RepeatUnit.MONTH -> runStart.toLocalDate().plusMonths(duration)
                RepeatUnit.YEAR -> runStart.toLocalDate().plusYears(duration)
            }
        }
        val (hours, minutes, _, _) = TimeHelper.millisToTime(alarm.time)
        return endDate.atTime(hours, minutes).atZone(runStart.zone)
    }

    /** @return the occurrences in a run at or after [from], retaining elapsed-hour cadence and calendar wall times. */
    private fun occurrencesWithinRun(
        alarm: Alarm,
        runStart: ZonedDateTime,
        from: ZonedDateTime
    ): Sequence<ZonedDateTime> {
        if (alarm.repeatUnit == RepeatUnit.HOUR) return sequenceOf(runStart).filter { !it.isBefore(from) }
        val end = runEnd(alarm, runStart)
        val (hours, minutes, _, _) = TimeHelper.millisToTime(alarm.time)
        return generateSequence(maxOf(from.toLocalDate(), runStart.toLocalDate())) { it.plusDays(1) }
            .map { date ->
                if (date == runStart.toLocalDate()) runStart else date.atTime(hours, minutes).atZone(runStart.zone)
            }
            .takeWhile { it.isBefore(end) }
            .filter { !it.isBefore(from) && ringsOn(alarm, it.toLocalDate()) }
    }

    private fun ringsOn(alarm: Alarm, date: LocalDate): Boolean =
        alarm.repeatUnit != RepeatUnit.WEEK || date.dayOfWeek.value % DAYS_PER_WEEK in alarm.days

    /** @return the [count]th scheduled occurrence from the original start, or null when the alarm never rings. */
    private fun lastOccurrence(alarm: Alarm, count: Int, timeZone: ZoneId): ZonedDateTime? {
        val (hours, minutes, _, _) = TimeHelper.millisToTime(alarm.time)
        var origin = LocalDate.ofEpochDay(alarm.startDate).atTime(hours, minutes).atZone(timeZone)
        if (alarm.startWithLaterOffset) origin = origin.withLaterOffsetAtOverlap()
        var occurrence = occurrenceOnOrAfter(alarm, origin)
        repeat(count - 1) {
            occurrence = occurrence?.let { occurrenceOnOrAfter(alarm, it.plusNanos(1)) }
        }
        return occurrence
    }

    fun snooze(context: Context, oldAlarm: Alarm, ringTime: Long, snoozeMinutes: Int): Long {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = ringTime
        calendar.add(Calendar.MINUTE, snoozeMinutes)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis <= now) {
            calendar.timeInMillis = now
            calendar.add(Calendar.MINUTE, 1)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
        }
        val alarm = oldAlarm.copy(snoozedUntil = calendar.timeInMillis)
        runBlocking {
            (context.applicationContext as App).container.alarmRepository.updateAlarm(alarm)
        }
        enqueue(context, alarm)
        return calendar.timeInMillis
    }
    /**
     * @return the days of the week mapped to an index 0-Sunday, 1-Monday, ..., 6-Saturday.
     * The list order is only for presentation and follows the user's preference or locale without
     * changing an alarm's recurrence.
     */
    fun getDaysOfWeekForDisplay(context: Context): List<Pair<String, Int>> {
        val availableDays = context.resources.getStringArray(R.array.available_days).toList()
        val firstDayIndex = Preferences.instance.getString(Preferences.weekStartKey, null)
            ?.let { WeekStart.valueOf(it).dayOfWeek.value % DAYS_PER_WEEK }
            ?: GregorianCalendar().firstDayOfWeek - 1
        val daysWithIndex = availableDays.mapIndexed { index, s -> s to index }
        return daysWithIndex.subList(firstDayIndex, 7) + daysWithIndex.subList(0, firstDayIndex)
    }
}
