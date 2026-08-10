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

class PushRegistrationWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val token = inputData.getString(PUSH_TOKEN) ?: return Result.failure()
        (applicationContext as App).container.socialRepository.registerPushToken(token)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val PUSH_TOKEN = "push_token"

        fun enqueue(context: Context, token: String) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setInputData(Data.Builder().putString(PUSH_TOKEN, token).build())
                    .build()
            )
        }
    }
}
