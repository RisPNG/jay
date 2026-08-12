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
import com.bnyro.clock.util.TimeHelper

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
            val action = when (change.kind) {
                AlarmChangeKind.CREATED -> R.string.alarm_created_by_member
                AlarmChangeKind.EDITED -> R.string.alarm_edited_by_member
                AlarmChangeKind.ENABLED -> R.string.alarm_enabled_by_member
                AlarmChangeKind.DISABLED -> R.string.alarm_disabled_by_member
                AlarmChangeKind.DELETED -> R.string.alarm_deleted_by_member
            }
            val alarmName = change.alarmLabel?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.unnamed_shared_alarm)
            val title = change.alarmTime?.let {
                context.getString(
                    R.string.shared_alarm_change_title,
                    alarmName,
                    TimeHelper.millisToFormatted(it)
                )
            } ?: alarmName
            val message = context.getString(
                action,
                change.deviceName ?: context.getString(R.string.unknown_group_member),
                change.groupName
            )
            NotificationManagerCompat.from(context).notify(
                change.sequence.hashCode(),
                NotificationCompat.Builder(context, NotificationHelper.SOCIAL_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
}
