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
        val preferences = applicationContext.getSharedPreferences("clock_you", Context.MODE_PRIVATE)
        val wasEntitled = preferences.getBoolean(
            SocialPreferences.entitlementSharedUploadKey,
            false
        )
        preferences.edit()
            .putBoolean(SocialPreferences.entitlementSharedUploadKey, status.sharedSoundUpload)
            .putString(SocialPreferences.entitlementExpiresAtKey, status.expiresAt)
            .apply()
        if (status.sharedSoundUpload) {
            androidx.core.app.NotificationManagerCompat.from(applicationContext).cancel(
                SocialNotificationHelper.ENTITLEMENT_NOTIFICATION_ID
            )
        } else if (wasEntitled) {
            SocialNotificationHelper.notifyDeviceIssue(
                applicationContext,
                SocialNotificationHelper.ENTITLEMENT_NOTIFICATION_ID,
                applicationContext.getString(R.string.play_entitlement_lost_title),
                applicationContext.getString(R.string.play_entitlement_lost_message)
            )
        }
        Result.success()
    } catch (_: Exception) {
        val expiresAt = applicationContext.getSharedPreferences(
            "clock_you",
            Context.MODE_PRIVATE
        ).getString(SocialPreferences.entitlementExpiresAtKey, null)
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
}
