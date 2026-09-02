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

class SocialTimerWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val timerId = inputData.getString(TIMER_ID) ?: return Result.failure()
        val repository = (applicationContext as App).container.socialRepository
        when (inputData.getString(ACTION)) {
            ACTION_ADJUST -> repository.adjustSharedTimer(
                timerId,
                inputData.getString(ADJUSTMENT) ?: return Result.failure()
            )

            ACTION_CANCEL -> repository.cancelSharedTimer(timerId)

            ACTION_DISMISSED -> repository.suppressSharedTimer(timerId)

            else -> return Result.failure()
        }
        Result.success()
    } catch (error: SocialApiException) {
        if (error.status in 400..499) Result.success() else Result.retry()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val TIMER_ID = "timer_id"
        private const val ACTION = "action"
        private const val ADJUSTMENT = "adjustment"
        private const val ACTION_ADJUST = "adjust"
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_DISMISSED = "dismissed"

        fun adjust(context: Context, timerId: String, action: String) {
            enqueue(context, ACTION_ADJUST, timerId) { putString(ADJUSTMENT, action) }
        }

        fun cancel(context: Context, timerId: String) {
            enqueue(context, ACTION_CANCEL, timerId)
        }

        fun dismissed(context: Context, timerId: String) {
            enqueue(context, ACTION_DISMISSED, timerId, requiresNetwork = false)
        }

        private fun enqueue(
            context: Context,
            action: String,
            timerId: String,
            requiresNetwork: Boolean = true,
            extraData: Data.Builder.() -> Unit = {}
        ) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<SocialTimerWorker>()
                    .setConstraints(
                        if (requiresNetwork) {
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        } else {
                            Constraints.NONE
                        }
                    )
                    .setInputData(
                        Data.Builder()
                            .putString(TIMER_ID, timerId)
                            .putString(ACTION, action)
                            .apply(extraData)
                            .build()
                    )
                    .build()
            )
        }
    }
}
