package com.bnyro.clock.social.data

import android.content.Context
import androidx.work.WorkManager
import com.bnyro.clock.social.domain.AlarmActivityKind

object SocialAlarmEvents {
    fun dismiss(context: Context, alarmId: Long, occurrenceId: String? = null) {
        SocialActivityWorker.enqueue(context, alarmId, AlarmActivityKind.DISMISSED, occurrenceId)
        WorkManager.getInstance(context).cancelUniqueWork("jay_ignored_alarm_$alarmId")
    }

    fun snooze(
        context: Context,
        alarmId: Long,
        snoozeMinutes: Int,
        occurrenceId: String
    ) {
        SocialActivityWorker.enqueue(
            context,
            alarmId,
            AlarmActivityKind.SNOOZED,
            occurrenceId
        )
        SocialIgnoredAlarmWorker.schedule(
            context,
            alarmId,
            System.currentTimeMillis() + snoozeMinutes * 60_000L,
            occurrenceId
        )
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
