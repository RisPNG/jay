package com.bnyro.clock.social.data

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bnyro.clock.App
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SocialSyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as App).container.socialRepository.synchronize()
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private val schedulingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val schedulingMutex = Mutex()

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
            val manager = WorkManager.getInstance(context)
            schedulingScope.launch {
                schedulingMutex.withLock {
                    val work = manager.getWorkInfosForUniqueWork("jay_social_sync").get()
                    if (work.none { it.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) }) {
                        manager.enqueueUniqueWork(
                            "jay_social_sync", androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                            request.build()
                        ).result.get()
                    }
                }
            }
        }
    }
}
