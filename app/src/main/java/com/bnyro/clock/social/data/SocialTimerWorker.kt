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
import com.bnyro.clock.util.Preferences

class SocialTimerWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val timerId = inputData.getString(TIMER_ID) ?: return Result.failure()
        val server = Preferences.instance.getString(SocialPreferences.serverUrlKey, SocialRepository.DEFAULT_SERVER_URL) ?: SocialRepository.DEFAULT_SERVER_URL
        if (server != inputData.getString("server") || DeviceIdentityStore.loadOrCreate(applicationContext, server).id != inputData.getString("identity")) return Result.success()
        val repository = (applicationContext as App).container.socialRepository
        when (inputData.getString(ACTION)) {
            ACTION_ADJUST -> repository.adjustSharedTimer(
                timerId,
                inputData.getLong(EXPIRES_AT, 0),
                inputData.getString("operation_id") ?: return Result.failure(),
                inputData.getLong("saved_at", 0)
            )

            ACTION_CANCEL -> repository.cancelSharedTimer(timerId, inputData.getString("operation_id") ?: return Result.failure())

            ACTION_DISMISSED -> repository.suppressSharedTimer(timerId, inputData.getLong(EXPIRES_AT, 0L))

            else -> return Result.failure()
        }
        Result.success()
    } catch (error: SocialApiException) {
        if (error.status in 400..499) Result.success() else Result.retry()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val EXPIRES_AT = "expires_at"
        private const val TIMER_ID = "timer_id"
        private const val ACTION = "action"
        private const val ACTION_ADJUST = "adjust"
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_DISMISSED = "dismissed"

        fun adjust(context: Context, timerId: String, expiresAt: Long) {
            enqueue(context, ACTION_ADJUST, timerId) { putLong(EXPIRES_AT, expiresAt) }
        }

        fun cancel(context: Context, timerId: String) {
            enqueue(context, ACTION_CANCEL, timerId)
        }

        fun dismissed(context: Context, timerId: String, expiresAt: Long) {
            enqueue(context, ACTION_DISMISSED, timerId, requiresNetwork = false) {
                putLong(EXPIRES_AT, expiresAt)
            }
        }

        private fun enqueue(
            context: Context,
            action: String,
            timerId: String,
            requiresNetwork: Boolean = false,
            extraData: Data.Builder.() -> Unit = {}
        ) {
            val server = Preferences.instance.getString(SocialPreferences.serverUrlKey, SocialRepository.DEFAULT_SERVER_URL) ?: SocialRepository.DEFAULT_SERVER_URL
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
                            .putString("server", server)
                            .putString("identity", DeviceIdentityStore.loadOrCreate(context, server).id)
                            .putString("operation_id", java.util.UUID.randomUUID().toString())
                            .putLong("saved_at", System.currentTimeMillis())
                            .apply(extraData)
                            .build()
                    )
                    .build()
            )
        }
    }
}
