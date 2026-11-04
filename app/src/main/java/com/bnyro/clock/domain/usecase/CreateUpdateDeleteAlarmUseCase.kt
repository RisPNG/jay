package com.bnyro.clock.domain.usecase

import android.content.Context
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.RepeatUnit
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.util.AlarmHelper
import java.time.Clock
import java.time.ZoneId
import java.time.LocalDate

class CreateUpdateDeleteAlarmUseCase(
    private val context: Context,
    private val alarmRepository: AlarmRepository
) {
    suspend fun createAlarm(alarm: Alarm, timeZone: ZoneId = ZoneId.systemDefault()): Long {
        alarm.snoozedUntil = null
        alarm.snoozeCount = 0
        alarm.dismissedAt = null
        if (AlarmHelper.hasRecurrenceEnded(alarm, timeZone)) alarm.enabled = false
        val newId = alarmRepository.addAlarm(alarm)
        val alarmWithId = alarm.copy(id = newId)
        AlarmHelper.enqueue(context, alarmWithId, timeZone = timeZone)
        return newId
    }

    suspend fun updateAlarm(alarm: Alarm, timeZone: ZoneId = ZoneId.systemDefault()) {
        alarm.snoozedUntil = null
        alarm.snoozeCount = 0
        alarm.dismissedAt = null
        if (AlarmHelper.hasRecurrenceEnded(alarm, timeZone)) alarm.enabled = false
        alarmRepository.updateAlarm(alarm)
        AlarmHelper.enqueue(context, alarm, timeZone = timeZone)
    }

    fun prepareForScheduling(
        alarm: Alarm,
        timeZone: ZoneId = ZoneId.systemDefault(),
        clock: Clock = Clock.system(timeZone)
    ) {
        alarm.snoozedUntil = null
        alarm.snoozeCount = 0
        alarm.dismissedAt = null
        if (!alarm.advanced) alarm.startDate = LocalDate.now(clock.withZone(timeZone)).toEpochDay()
        AlarmHelper.getNextRepetitionStart(alarm, timeZone, clock)?.let { start ->
            alarm.startDate = start.toLocalDate().toEpochDay()
            alarm.startWithLaterOffset = start.offset != start.withEarlierOffsetAtOverlap().offset
            if (alarm.repeatUnit == RepeatUnit.HOUR) {
                alarm.time = start.hour * 3_600_000L + start.minute * 60_000L
            }
        }
        if (AlarmHelper.hasRecurrenceEnded(alarm, timeZone, clock)) alarm.enabled = false
    }

    suspend fun dismissUpcomingAlarm(alarm: Alarm) {
        if (alarm.snoozedUntil == null &&
            alarm.dismissedAt?.let { it > System.currentTimeMillis() } == true
        ) return

        AlarmHelper.cancel(context, alarm)

        if (alarm.snoozedUntil == null) {
            alarm.dismissedAt = AlarmHelper.getAlarmTime(alarm)
        }
        alarm.snoozedUntil = null
        if (AlarmHelper.hasRecurrenceEnded(alarm)) alarm.enabled = false
        alarmRepository.updateAlarm(alarm)
        AlarmHelper.enqueue(context, alarm)
    }

    suspend fun deleteAlarm(alarm: Alarm) {
        alarmRepository.deleteAlarm(alarm)
        AlarmHelper.cancel(context, alarm)
    }
}
