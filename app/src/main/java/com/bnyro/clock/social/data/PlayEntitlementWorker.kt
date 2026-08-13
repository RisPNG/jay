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
        val status = (applicationContext as App).container.socialRepository.refreshPlayEntitlement()
        applicationContext.getSharedPreferences("clock_you", Context.MODE_PRIVATE).edit()
            .putString(ENTITLEMENT_EXPIRES_AT, status.expiresAt)
            .apply()
        if (!status.sharedSoundUpload) {
            SocialNotificationHelper.notifyDeviceIssue(
                applicationContext,
                SocialNotificationHelper.ENTITLEMENT_NOTIFICATION_ID,
                applicationContext.getString(R.string.play_entitlement_lost_title),
                applicationContext.getString(R.string.play_entitlement_lost_message)
            )
        } else androidx.core.app.NotificationManagerCompat.from(applicationContext).cancel(
            SocialNotificationHelper.ENTITLEMENT_NOTIFICATION_ID
        )
        Result.success()
    } catch (_: Exception) {
        val expiresAt = applicationContext.getSharedPreferences(
            "clock_you",
            Context.MODE_PRIVATE
        ).getString(ENTITLEMENT_EXPIRES_AT, null)
        if (expiresAt != null && Instant.now().isAfter(Instant.parse(expiresAt))) {
            SocialNotificationHelper.notifyDeviceIssue(
                applicationContext,
                SocialNotificationHelper.ENTITLEMENT_NOTIFICATION_ID,
                applicationContext.getString(R.string.play_entitlement_lost_title),
                applicationContext.getString(R.string.play_entitlement_refresh_failed_message)
            )
        }
        Result.retry()
    }

    companion object {
        private const val ENTITLEMENT_EXPIRES_AT = "jayPlayEntitlementExpiresAt"
    }
}
