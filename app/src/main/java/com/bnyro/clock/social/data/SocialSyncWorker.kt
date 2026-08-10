package com.bnyro.clock.social.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bnyro.clock.App
import com.bnyro.clock.social.presentation.SocialNotificationHelper
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class SocialSyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val result = (applicationContext as App).container.socialRepository.synchronize()
        result.newActivity.filter { it.deviceId != result.deviceId }.forEach {
            SocialNotificationHelper.notify(applicationContext, it)
        }
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<SocialSyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }
    }
}
