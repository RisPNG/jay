package com.bnyro.clock.social.data

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bnyro.clock.App
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.domain.model.TimerObject
import com.bnyro.clock.util.Preferences

class SocialTimerWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val timerId = inputData.getString(TIMER_ID) ?: return Result.failure()
        val groupId = inputData.getString("group_id") ?: return Result.failure()
        val membershipId = inputData.getString("membership_id") ?: return Result.failure()
        val operationId = inputData.getString("operation_id") ?: return Result.failure()
        val server = Preferences.instance.getString(SocialPreferences.serverUrlKey, SocialRepository.DEFAULT_SERVER_URL) ?: SocialRepository.DEFAULT_SERVER_URL
        if (server != inputData.getString("server") || DeviceIdentityStore.loadOrCreate(applicationContext, server).id != inputData.getString("identity")) return Result.success()
        val repository = (applicationContext as App).container.socialRepository
        when (inputData.getString(ACTION)) {
            ACTION_ADJUST -> repository.adjustSharedTimer(
                timerId, groupId, membershipId,
                inputData.getString("run_id") ?: return Result.failure(),
                inputData.getInt("repeat_index", 0), inputData.getLong(EXPIRES_AT, 0),
                operationId, inputData.getLong("saved_at", 0),
                inputData.getString("source_run_id"), inputData.getInt("adjustment_seconds", 0),
                inputData.getInt("source_repeat_index", 0), inputData.getBoolean("source_finished", false)
            )

            ACTION_CANCEL -> repository.cancelSharedTimer(timerId, groupId, membershipId, operationId, inputData.getString("cancel_run_id"))

            ACTION_DISMISSED -> repository.dismissSharedTimer(
                timerId, groupId, membershipId,
                inputData.getString("run_id") ?: return Result.failure(),
                inputData.getInt("repeat_index", 0), inputData.getLong(EXPIRES_AT, 0),
                inputData.getInt("timeout_minutes", TimerSettings.DEFAULT_TIMEOUT_MINUTES), operationId,
                inputData.getLong("saved_at", 0)
            )

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
        const val ACTION_ADJUST = "adjust"
        const val ACTION_CANCEL = "cancel"
        const val ACTION_DISMISSED = "dismissed"

        fun enqueue(
            context: Context,
            timer: TimerObject,
            action: String,
            expiresAt: Long = timer.expiresAt,
            repeatIndex: Int = timer.repeatIndex,
            adjustmentSeconds: Int = 0
        ) {
            val server = Preferences.instance.getString(SocialPreferences.serverUrlKey, SocialRepository.DEFAULT_SERVER_URL) ?: SocialRepository.DEFAULT_SERVER_URL
            val identityId = DeviceIdentityStore.loadOrCreate(context, server).id
            val request = OneTimeWorkRequestBuilder<SocialTimerWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(TIMER_ID, requireNotNull(timer.sharedTimerId))
                        .putString(ACTION, action)
                        .putString("group_id", requireNotNull(timer.sharedGroupId))
                        .putString("membership_id", requireNotNull(timer.sharedMembershipId))
                        .apply {
                            if (action == ACTION_CANCEL && (context.applicationContext as App).container.socialRepository.canUseAdvancedGroupTiming) {
                                putString("cancel_run_id", requireNotNull(timer.sharedRunId))
                            }
                        }
                        .putString("run_id", if (action == ACTION_ADJUST) java.util.UUID.randomUUID().toString() else requireNotNull(timer.sharedRunId))
                        .putInt("repeat_index", repeatIndex)
                        .putString("source_run_id", timer.sharedRunId)
                        .putInt("source_repeat_index", timer.repeatIndex)
                        .putBoolean("source_finished", timer.currentPosition.value == 0)
                        .putInt("adjustment_seconds", adjustmentSeconds)
                        .putLong(EXPIRES_AT, expiresAt)
                        .putInt("timeout_minutes", timer.timeoutMinutes)
                        .putString("server", server)
                        .putString("identity", identityId)
                        .putString("operation_id", java.util.UUID.randomUUID().toString())
                        .putLong("saved_at", System.currentTimeMillis())
                        .build()
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            WorkManager.getInstance(context).beginUniqueWork(
                "timer:$server:$identityId:${timer.sharedMembershipId}:${timer.sharedTimerId}",
                ExistingWorkPolicy.APPEND_OR_REPLACE, request.build()
            ).enqueue()
        }
    }
}
