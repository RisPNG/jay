package com.bnyro.clock.social.data

import android.content.Context
import androidx.core.os.UserManagerCompat
import androidx.work.WorkManager
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.social.domain.AlarmActivityKind
import java.time.Instant

object SocialAlarmEvents {
    fun dismiss(context: Context, alarmId: Long, occurrenceId: String? = null) {
        SocialActivityWorker.enqueue(context, alarmId, AlarmActivityKind.DISMISSED, occurrenceId)
        if (Preferences.instance.contains("${SocialPreferences.alarmOccurrencePrefix}$alarmId")) {
            Preferences.edit { putLong("${SocialPreferences.alarmDeadlinePrefix}$alarmId", 0L) }
        }
        if (UserManagerCompat.isUserUnlocked(context)) {
            WorkManager.getInstance(context).cancelUniqueWork("jay_ignored_alarm_$alarmId")
        }
    }

    fun snooze(
        context: Context,
        alarm: Alarm,
        snoozedUntil: Long,
        occurrenceId: String,
        reason: String? = null,
        occurredAtMillis: Long = System.currentTimeMillis()
    ) {
        val shared = Preferences.instance.contains("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}")
        if (shared) {
            Preferences.edit { putLong("${SocialPreferences.alarmSnoozedAtPrefix}${alarm.id}", occurredAtMillis) }
        }
        SocialActivityWorker.enqueue(
            context,
            alarm.id,
            AlarmActivityKind.SNOOZED,
            occurrenceId,
            reason = reason,
            snoozedUntil = Instant.ofEpochMilli(snoozedUntil).toString(),
            occurredAt = Instant.ofEpochMilli(occurredAtMillis).toString()
        )
        if (shared) {
            SocialIgnoredAlarmWorker.schedule(
                context,
                alarm,
                snoozedUntil,
                occurrenceId,
                snoozedAtMillis = occurredAtMillis
            )
        }
    }

    fun ignore(
        context: Context,
        alarmId: Long,
        occurrenceId: String?,
        reason: String
    ) {
        SocialActivityWorker.enqueue(
            context,
            alarmId,
            AlarmActivityKind.IGNORED,
            occurrenceId,
            reason
        )
    }
}
