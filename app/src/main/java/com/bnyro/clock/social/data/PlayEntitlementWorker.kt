package com.bnyro.clock.social.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bnyro.clock.App

class PlayEntitlementWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as App).container.socialRepository.refreshPlayEntitlement()
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
