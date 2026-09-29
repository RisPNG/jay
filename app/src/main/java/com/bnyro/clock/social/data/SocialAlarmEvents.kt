package com.bnyro.clock.social.data

import android.content.Context
import androidx.core.os.UserManagerCompat
import androidx.work.WorkManager
import com.bnyro.clock.social.domain.AlarmActivityKind
import java.time.Instant

object SocialAlarmEvents {
    fun dismiss(context: Context, alarmId: Long, occurrenceId: String? = null) {
        SocialActivityWorker.enqueue(context, alarmId, AlarmActivityKind.DISMISSED, occurrenceId)
        if (UserManagerCompat.isUserUnlocked(context)) {
            WorkManager.getInstance(context).cancelUniqueWork("jay_ignored_alarm_$alarmId")
        }
    }

    fun snooze(
        context: Context,
        alarmId: Long,
        snoozedUntil: Long,
        occurrenceId: String
    ) {
        SocialActivityWorker.enqueue(
            context,
            alarmId,
            AlarmActivityKind.SNOOZED,
            occurrenceId,
            snoozedUntil = Instant.ofEpochMilli(snoozedUntil).toString()
        )
        SocialIgnoredAlarmWorker.schedule(
            context,
            alarmId,
            snoozedUntil,
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
