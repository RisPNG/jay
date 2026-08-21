package com.bnyro.clock.social.data

import android.content.Context
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
import com.bnyro.clock.util.services.AlarmService
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
        (applicationContext as App).container.socialRepository.recordActivity(
            alarmId,
            AlarmActivityKind.IGNORED,
            inputData.getString(EVENT_ID) ?: return Result.failure(),
            Instant.ofEpochMilli(deadlineAt).toString(),
            occurrenceId,
            "no_response"
        )
        val alarm = (applicationContext as App).container.alarmRepository.getAlarmById(alarmId)
        if (alarm?.enabled == true && !alarm.isOneTime) {
            AlarmHelper.getAlarmTime(alarm)?.let { triggerAt ->
                schedule(
                    applicationContext,
                    alarmId,
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
            alarmId: Long,
            triggerAtMillis: Long,
            occurrenceId: String
        ) {
            Preferences.edit { putString("jayAlarmOccurrence:$alarmId", occurrenceId) }
            val deadlineAt = triggerAtMillis + Preferences.instance.getInt(
                Preferences.alarmTimeoutMinutesKey,
                AlarmService.ALARM_TIMEOUT_MINUTES
            ) * 60_000L
            WorkManager.getInstance(context).enqueueUniqueWork(
                "jay_ignored_alarm_$alarmId",
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
                            .putLong(ALARM_ID, alarmId)
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
