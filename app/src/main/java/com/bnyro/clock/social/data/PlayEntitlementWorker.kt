package com.bnyro.clock.social.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bnyro.clock.App
import com.bnyro.clock.R
import com.bnyro.clock.social.presentation.SocialNotificationHelper
import java.time.Instant

class PlayEntitlementWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as App).container.socialRepository.refreshPlayEntitlement()
        Result.success()
    } catch (_: Exception) {
        val capabilities = (applicationContext as App).container.socialRepository.deviceCapabilities
        val expiresAt = capabilities.expiresAt
        if (capabilities.requiresPlayEntitlement && expiresAt != null &&
            !Instant.now().isBefore(Instant.parse(expiresAt))
        ) {
            SocialNotificationHelper.notifyDeviceIssue(
                applicationContext,
                SocialNotificationHelper.ENTITLEMENT_NOTIFICATION_ID,
                applicationContext.getString(R.string.play_entitlement_lost_title),
                applicationContext.getString(R.string.play_entitlement_refresh_failed_message)
            )
        }
        Result.retry()
    }
}
