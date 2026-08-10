package com.bnyro.clock.social.presentation

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.Permission
import com.bnyro.clock.social.data.SocialSyncResult
import com.bnyro.clock.social.domain.AlarmActivityKind
import com.bnyro.clock.social.domain.AlarmChangeKind
import com.bnyro.clock.util.NotificationHelper

object SocialNotificationHelper {
    @SuppressLint("MissingPermission")
    fun notifySocialChanges(context: Context, result: SocialSyncResult) {
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
                        result.alarmLabels[activity.alarmId]
                            ?.takeIf { it.isNotBlank() }
                            ?.let {
                                context.getString(
                                    R.string.member_alarm_activity,
                                    activity.deviceName,
                                    action,
                                    it
                                )
                            }
                            ?: context.getString(
                                R.string.member_unnamed_alarm_activity,
                                activity.deviceName,
                                action
                            )
                    )
                    .setAutoCancel(true)
                    .build()
            )
        }

        result.alarmChanges.filter { it.deviceId != result.deviceId }.forEach { change ->
            val message = when (change.kind) {
                AlarmChangeKind.CREATED -> R.string.member_created_alarm
                AlarmChangeKind.EDITED -> R.string.member_edited_alarm
                AlarmChangeKind.ENABLED -> R.string.member_enabled_alarm
                AlarmChangeKind.DISABLED -> R.string.member_disabled_alarm
                AlarmChangeKind.DELETED -> R.string.member_deleted_alarm
            }
            NotificationManagerCompat.from(context).notify(
                change.sequence.hashCode(),
                NotificationCompat.Builder(context, NotificationHelper.SOCIAL_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.shared_alarm_change))
                    .setContentText(
                        context.getString(
                            message,
                            change.deviceName
                                ?: context.getString(R.string.unknown_group_member),
                            change.alarmLabel
                                ?: context.getString(R.string.unnamed_shared_alarm),
                            change.groupName
                        )
                    )
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
}
