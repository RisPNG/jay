package com.bnyro.clock.social.data

import android.content.Context
import androidx.core.os.UserManagerCompat
import androidx.work.ExistingWorkPolicy
import com.bnyro.clock.util.Preferences
import org.json.JSONObject
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bnyro.clock.App
import com.bnyro.clock.social.domain.AlarmActivityKind
import java.time.Instant
import java.util.UUID

class SocialActivityWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val alarmId = inputData.getLong(ALARM_ID, -1L).takeIf { it != -1L }
            ?: return Result.failure()
        val kind = inputData.getString(ACTIVITY_KIND)?.let(AlarmActivityKind::valueOf)
            ?: return Result.failure()
        val occurredAt = inputData.getString(OCCURRED_AT) ?: return Result.failure()
        (applicationContext as App).container.socialRepository.recordActivity(
            alarmId,
            kind,
            inputData.getString(EVENT_ID) ?: return Result.failure(),
            occurredAt,
            inputData.getString(OCCURRENCE_ID),
            inputData.getString(REASON)
        )
        Result.success()
    } catch (error: SocialApiException) {
        if (error.status == 404 || error.status == 409) Result.success() else Result.retry()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val ALARM_ID = "alarm_id"
        private const val ACTIVITY_KIND = "activity_kind"
        private const val OCCURRED_AT = "occurred_at"
        private const val EVENT_ID = "event_id"
        private const val OCCURRENCE_ID = "occurrence_id"
        private const val REASON = "reason"

        fun enqueue(
            context: Context,
            alarmId: Long,
            kind: AlarmActivityKind,
            occurrenceId: String? = null,
            reason: String? = null
        ) {
            val eventId = UUID.randomUUID().toString()
            val event = JSONObject()
                .put(ALARM_ID, alarmId)
                .put(ACTIVITY_KIND, kind.name)
                .put(EVENT_ID, eventId)
                .put(OCCURRED_AT, Instant.now().toString())
                .put(OCCURRENCE_ID, occurrenceId)
                .put(REASON, reason)
            Preferences.instance.edit()
                .putString("jayPendingAlarmActivity:$eventId", event.toString())
                .commit()
            enqueuePending(context)
        }

        @Synchronized
        fun enqueuePending(context: Context) {
            if (!UserManagerCompat.isUserUnlocked(context)) return
            Preferences.instance.all.filterKeys { it.startsWith("jayPendingAlarmActivity:") }
                .forEach { (key, value) ->
                    val event = JSONObject(value as String)
                    if (event.getString(ACTIVITY_KIND) in listOf("DISMISSED", "SNOOZED")) {
                        WorkManager.getInstance(context).cancelUniqueWork(
                            "jay_ignored_alarm_${event.getLong(ALARM_ID)}"
                        )
                    }
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        key,
                        ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<SocialActivityWorker>()
                            .setConstraints(
                                Constraints.Builder()
                                    .setRequiredNetworkType(NetworkType.CONNECTED)
                                    .build()
                            )
                            .setInputData(
                                Data.Builder()
                                    .putLong(ALARM_ID, event.getLong(ALARM_ID))
                                    .apply {
                                        event.keys().forEach { field ->
                                            if (field != ALARM_ID) putString(field, event.getString(field))
                                        }
                                    }
                                    .build()
                            )
                            .build()
                    ).result.get()
                    Preferences.instance.edit().remove(key).commit()
                }
        }
    }
}
