package com.bnyro.clock.social.data

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bnyro.clock.App
import com.bnyro.clock.social.presentation.SocialNotificationHelper
import com.bnyro.clock.R
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

class SocialSyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val result = (applicationContext as App).container.socialRepository.synchronize()
        SocialNotificationHelper.notifySocialChanges(applicationContext, result)
        androidx.core.app.NotificationManagerCompat.from(applicationContext).cancel(
            SocialNotificationHelper.SYNC_FAILURE_NOTIFICATION_ID
        )
        Result.success()
    } catch (_: Exception) {
        if (runAttemptCount >= 2) {
            SocialNotificationHelper.notifyDeviceIssue(
                applicationContext,
                SocialNotificationHelper.SYNC_FAILURE_NOTIFICATION_ID,
                applicationContext.getString(R.string.social_sync_failure_title),
                applicationContext.getString(R.string.social_sync_failure_message)
            )
        }
        Result.retry()
    }

    companion object {
        fun enqueue(context: Context, expedited: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<SocialSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
            if (expedited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            WorkManager.getInstance(context).enqueue(
                request.build()
            )
        }
    }
}
