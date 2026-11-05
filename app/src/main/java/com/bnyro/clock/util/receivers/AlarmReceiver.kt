package com.bnyro.clock.util.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.bnyro.clock.App
import com.bnyro.clock.social.data.SocialAlarmSchedule
import com.bnyro.clock.social.data.SocialIgnoredAlarmWorker
import com.bnyro.clock.social.data.SocialPreferences
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.TimeHelper
import com.bnyro.clock.util.services.AlarmService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        Log.e("receiver", "received")
        val id = intent.getLongExtra(AlarmHelper.EXTRA_ID, -1).takeIf { it != -1L } ?: return
        val alarmRepository = (context.applicationContext as App).container.alarmRepository
        val alarm = runBlocking {
            alarmRepository.getAlarmById(id)
        } ?: return

        val now = System.currentTimeMillis()
        var triggerTime = intent.getLongExtra(AlarmHelper.EXTRA_TRIGGER_TIME, alarm.snoozedUntil ?: now)
        val activeOccurrenceId = Preferences.instance.getString("${SocialPreferences.alarmOccurrencePrefix}$id", null)
        var deliveredOccurrenceId: String? = null
        if (activeOccurrenceId != null) {
            val timeZone = SocialAlarmSchedule.timeZone(id)
            val (hours, minutes, _, _) = TimeHelper.millisToTime(alarm.time)
            if (!intent.hasExtra(AlarmHelper.EXTRA_TRIGGER_TIME)) {
                if (alarm.snoozedUntil != null) {
                    if (alarm.snoozedUntil!! > now) return
                } else {
                    triggerTime = Instant.ofEpochMilli(now).atZone(timeZone).toLocalDate()
                        .atTime(hours, minutes).atZone(timeZone).toInstant().toEpochMilli()
                    if (triggerTime > now) return
                }
            }
            deliveredOccurrenceId = intent.getStringExtra(AlarmService.EXTRA_OCCURRENCE_ID)
                ?: if (alarm.snoozedUntil != null) activeOccurrenceId else triggerTime.toString()
            val activeDeadline = Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}$id", -1L)
            val deadlineAt = triggerTime + alarm.timeoutMinutes * 60_000L
            if (deliveredOccurrenceId == triggerTime.toString()) {
                val occurrence = Instant.ofEpochMilli(triggerTime).atZone(timeZone)
                if (!alarm.enabled || alarm.snoozedUntil != null ||
                    AlarmHelper.occurrenceOnOrAfter(alarm, occurrence)?.toInstant() != occurrence.toInstant() ||
                    (deliveredOccurrenceId != activeOccurrenceId && triggerTime <= activeOccurrenceId.toLong()) ||
                    (deliveredOccurrenceId == activeOccurrenceId && activeDeadline != -1L && activeDeadline != deadlineAt)
                ) return
                if (deliveredOccurrenceId != activeOccurrenceId || activeDeadline == -1L) {
                    SocialIgnoredAlarmWorker.schedule(context, alarm, triggerTime, deliveredOccurrenceId)
                }
            } else {
                if (deliveredOccurrenceId != activeOccurrenceId ||
                    (alarm.snoozedUntil != null && alarm.snoozedUntil != triggerTime) ||
                    (activeDeadline != -1L && activeDeadline != deadlineAt)
                ) return
                if (activeDeadline == -1L) {
                    SocialIgnoredAlarmWorker.schedule(context, alarm, triggerTime, deliveredOccurrenceId,
                        snoozedAtMillis = Preferences.instance.getLong("${SocialPreferences.alarmSnoozedAtPrefix}$id", 0L))
                }
            }
        }
        if (alarm.snoozedUntil == null && (deliveredOccurrenceId == null || deliveredOccurrenceId == triggerTime.toString())) {
            alarm.snoozeCount = 0
        }
        alarm.snoozedUntil = null

        // the alarm rang its last occurrence, so it may not be re-enqueued for another one
        if (AlarmHelper.hasRecurrenceEnded(alarm)) {
            alarm.enabled = false
        }
        runBlocking {
            alarmRepository.updateAlarm(alarm)
        }

        val playAlarm = Intent(context, AlarmService::class.java)
        playAlarm.putExtra(AlarmHelper.EXTRA_ID, id)
        playAlarm.putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, triggerTime)
        deliveredOccurrenceId?.let { playAlarm.putExtra(AlarmService.EXTRA_OCCURRENCE_ID, it) }
        ContextCompat.startForegroundService(context, playAlarm)

        AlarmHelper.enqueue(context, alarm)


        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                delay(5_000.milliseconds)
                val fresh = alarmRepository.getAlarmById(id) ?: return@launch
                if (!fresh.enabled || fresh.snoozedUntil != null) return@launch
                val useCase =
                    CreateUpdateDeleteAlarmUseCase(context.applicationContext, alarmRepository)
                fresh.enabled = false
                useCase.updateAlarm(fresh)
                fresh.enabled = true
                useCase.updateAlarm(fresh)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
