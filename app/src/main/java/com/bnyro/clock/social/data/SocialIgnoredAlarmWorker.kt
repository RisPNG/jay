package com.bnyro.clock.social.data

import android.content.Context
import androidx.core.os.UserManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bnyro.clock.App
import com.bnyro.clock.social.domain.AlarmActivityKind
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.domain.model.Alarm
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.UUID

class SocialIgnoredAlarmWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val alarmId = inputData.getLong(ALARM_ID, -1L).takeIf { it != -1L }
            ?: return Result.failure()
        val occurrenceId = inputData.getString(OCCURRENCE_ID) ?: return Result.failure()
        val deadlineAt = inputData.getLong(DEADLINE_AT, -1L).takeIf { it != -1L }
            ?: return Result.failure()
        if (
            Preferences.instance.getString("${SocialPreferences.alarmOccurrencePrefix}$alarmId", null) != occurrenceId ||
            Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}$alarmId", -1L) != deadlineAt
        ) return Result.success()
        val alarm = (applicationContext as App).container.alarmRepository.getAlarmById(alarmId)
            ?: return Result.success()
        (applicationContext as App).container.socialRepository.recordActivity(
            alarmId,
            if (alarm.missedRestartEnabled) AlarmActivityKind.SNOOZED else AlarmActivityKind.IGNORED,
            inputData.getString(EVENT_ID) ?: return Result.failure(),
            Instant.ofEpochMilli(deadlineAt).toString(),
            occurrenceId,
            if (alarm.missedRestartEnabled) "timed_out" else "no_response",
            if (alarm.missedRestartEnabled) {
                Instant.ofEpochMilli(deadlineAt + alarm.missedRestartMinutes * 60_000L).toString()
            } else null
        )
        if (
            !alarm.missedRestartEnabled && alarm.enabled && !alarm.isOneTime &&
            Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}$alarmId", -1L) == deadlineAt
        ) {
            AlarmHelper.getAlarmTime(alarm)?.let { triggerAt ->
                schedule(
                    applicationContext,
                    alarm,
                    triggerAt,
                    triggerAt.toString()
                )
            }
        }
        Result.success()
    } catch (error: SocialApiException) {
        if (error.status == 404 || error.status == 409) Result.success() else Result.retry()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val ALARM_ID = "alarm_id"
        private const val OCCURRENCE_ID = "occurrence_id"
        private const val DEADLINE_AT = "deadline_at"
        private const val EVENT_ID = "event_id"

        fun schedule(
            context: Context,
            alarm: Alarm,
            triggerAtMillis: Long,
            occurrenceId: String,
            snoozedAtMillis: Long? = null
        ) {
            val deadlineAt = triggerAtMillis + alarm.timeoutMinutes * 60_000L
            Preferences.edit {
                putString("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}", occurrenceId)
                putLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", deadlineAt)
                putLong("${SocialPreferences.alarmSnoozedAtPrefix}${alarm.id}", snoozedAtMillis ?: 0L)
            }
            if (!UserManagerCompat.isUserUnlocked(context)) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                "jay_ignored_alarm_${alarm.id}",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SocialIgnoredAlarmWorker>()
                    .setInitialDelay(
                        (deadlineAt - System.currentTimeMillis()).coerceAtLeast(0L),
                        TimeUnit.MILLISECONDS
                    )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setInputData(
                        Data.Builder()
                            .putLong(ALARM_ID, alarm.id)
                            .putString(EVENT_ID, UUID.randomUUID().toString())
                            .putString(OCCURRENCE_ID, occurrenceId)
                            .putLong(DEADLINE_AT, deadlineAt)
                            .build()
                    )
                    .build()
            )
        }
    }
}
