package com.bnyro.clock.social.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bnyro.clock.App
import com.bnyro.clock.social.domain.AlarmActivityKind

class SocialActivityWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val alarmId = inputData.getLong(ALARM_ID, -1L).takeIf { it != -1L }
            ?: return Result.failure()
        val kind = inputData.getString(ACTIVITY_KIND)?.let(AlarmActivityKind::valueOf)
            ?: return Result.failure()
        (applicationContext as App).container.socialRepository.recordActivity(alarmId, kind)
        Result.success()
    } catch (error: SocialApiException) {
        if (error.status == 404 || error.status == 409) Result.success() else Result.retry()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val ALARM_ID = "alarm_id"
        private const val ACTIVITY_KIND = "activity_kind"

        fun enqueue(context: Context, alarmId: Long, kind: AlarmActivityKind) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<SocialActivityWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setInputData(
                        Data.Builder()
                            .putLong(ALARM_ID, alarmId)
                            .putString(ACTIVITY_KIND, kind.name)
                            .build()
                    )
                    .build()
            )
        }
    }
}
