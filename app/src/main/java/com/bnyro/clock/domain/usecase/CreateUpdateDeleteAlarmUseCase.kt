package com.bnyro.clock.domain.usecase

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.util.AlarmHelper
import java.time.ZoneId

class CreateUpdateDeleteAlarmUseCase(
    private val context: Context,
    private val alarmRepository: AlarmRepository
) {
    @RequiresApi(Build.VERSION_CODES.M)
    suspend fun createAlarm(alarm: Alarm, timeZone: ZoneId = ZoneId.systemDefault()): Long {
        prepareForScheduling(alarm, timeZone)
        val newId = alarmRepository.addAlarm(alarm)
        val alarmWithId = alarm.copy(id = newId)
        AlarmHelper.enqueue(context, alarmWithId, timeZone = timeZone)
        return newId
    }

    @RequiresApi(Build.VERSION_CODES.M)
    suspend fun updateAlarm(alarm: Alarm, timeZone: ZoneId = ZoneId.systemDefault()) {
        prepareForScheduling(alarm, timeZone)
        alarmRepository.updateAlarm(alarm)
        AlarmHelper.enqueue(context, alarm, timeZone = timeZone)
    }

    fun prepareForScheduling(alarm: Alarm, timeZone: ZoneId = ZoneId.systemDefault()) {
        alarm.dismissedAt = null
        alarm.startDate = AlarmHelper.getNextRepetitionStart(alarm, timeZone)?.toEpochDay()
            ?: alarm.startDate
        if (AlarmHelper.hasRecurrenceEnded(alarm, timeZone)) alarm.enabled = false
    }

    @RequiresApi(Build.VERSION_CODES.M)
    suspend fun dismissUpcomingAlarm(alarm: Alarm) {
        if (alarm.dismissedAt?.let { it > System.currentTimeMillis() } == true) return

        AlarmHelper.cancel(context, alarm)
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(alarm.id.toInt() + AlarmHelper.PRE_ALARM_ID_OFFSET)

        alarm.dismissedAt = AlarmHelper.getAlarmTime(alarm)
        if (AlarmHelper.hasRecurrenceEnded(alarm)) alarm.enabled = false
        alarmRepository.updateAlarm(alarm)
        AlarmHelper.enqueue(context, alarm)
    }

    suspend fun deleteAlarm(alarm: Alarm) {

        alarmRepository.deleteAlarm(alarm)
        AlarmHelper.cancel(context, alarm)
    }
}
