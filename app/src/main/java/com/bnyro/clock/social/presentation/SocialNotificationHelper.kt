package com.bnyro.clock.social.presentation

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.Permission
import com.bnyro.clock.social.data.SocialSyncResult
import com.bnyro.clock.social.domain.AlarmActivityKind
import com.bnyro.clock.util.NotificationHelper

object SocialNotificationHelper {
    @SuppressLint("MissingPermission")
    fun notifyNewActivity(context: Context, result: SocialSyncResult) {
        if (!Permission.NotificationPermission.hasPermission(context)) return

        result.newActivity.filter { it.deviceId != result.deviceId }.forEach { activity ->
            val action = when (activity.kind) {
                AlarmActivityKind.SNOOZED -> context.getString(R.string.social_snoozed)
                AlarmActivityKind.DISMISSED -> context.getString(R.string.social_dismissed)
            }
            NotificationManagerCompat.from(context).notify(
                activity.id.hashCode(),
                NotificationCompat.Builder(context, NotificationHelper.SOCIAL_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.shared_alarm_activity))
                    .setContentText(
                        context.getString(
                            R.string.member_alarm_activity,
                            activity.deviceName,
                            action
                        )
                    )
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
}
