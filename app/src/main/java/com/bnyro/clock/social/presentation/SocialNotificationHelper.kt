package com.bnyro.clock.social.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.Permission
import com.bnyro.clock.social.data.SocialSyncResult
import com.bnyro.clock.util.NotificationHelper
import com.bnyro.clock.ui.MainActivity
import java.time.Instant

object SocialNotificationHelper {
    @SuppressLint("MissingPermission")
    fun notifyDeviceIssue(context: Context, id: Int, title: String, message: String) {
        if (!Permission.NotificationPermission.hasPermission(context)) return
        NotificationManagerCompat.from(context).notify(
            id,
            NotificationCompat.Builder(context, NotificationHelper.SOCIAL_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build()
        )
    }

    @SuppressLint("MissingPermission")
    fun notifySocialChanges(context: Context, result: SocialSyncResult) {
        if (!Permission.NotificationPermission.hasPermission(context)) return

        val changes = result.changes.filter { change ->
            if (change.actorDeviceId == result.deviceId) return@filter false
            if (change.action == "invitation_created") return@filter false
            val group = result.groups[change.groupId]
            when (change.entityType) {
                "alarm" -> group?.notifyAlarmChanges == true
                "outcome" -> when (change.action) {
                    "snoozed" -> group?.notifySnoozed == true
                    "dismissed" -> group?.notifyDismissed == true
                    "ignored" -> group?.notifyIgnored == true
                    else -> false
                }
                "membership" -> change.subjectDeviceId == result.deviceId ||
                    group?.notifyMembership == true
                "administrative" -> change.subjectDeviceId == result.deviceId ||
                    group?.notifyAdministrative == true
                "group" -> group?.notifyAdministrative == true
                else -> false
            }
        }
        val direct = changes.filter {
            it.subjectDeviceId == result.deviceId &&
                it.action == "removed"
        }
        val grouped = (changes - direct.toSet()).groupBy { it.groupId }
        val notificationManager = NotificationManagerCompat.from(context)

        direct.forEach { change ->
            val eventTime = Instant.parse(change.occurredAt).toEpochMilli()
            notificationManager.notify(
                change.sequence.hashCode(),
                NotificationCompat.Builder(context, NotificationHelper.SOCIAL_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(change.presentationTitle(context, result.deviceId))
                    .setContentText(change.presentationTime(context))
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            change.sequence.hashCode(),
                            Intent(context, MainActivity::class.java)
                                .setAction(SHOW_SOCIAL_ACTIVITY_ACTION)
                                .putExtra(EXTRA_SOCIAL_ENTITY_TYPE, change.entityType),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .setWhen(eventTime)
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .build()
            )
        }

        grouped.forEach { (groupId, groupChanges) ->
            val newest = groupChanges.last()
            val eventTime = Instant.parse(newest.occurredAt).toEpochMilli()
            val notificationId = groupId.hashCode()
            val accumulation = context.getSharedPreferences(
                "jay_social_notification_accumulation",
                Context.MODE_PRIVATE
            )
            val isActive = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .activeNotifications.any { it.id == notificationId }
            val updateCount = groupChanges.size + if (isActive) {
                accumulation.getInt(groupId, 0)
            } else {
                0
            }
            val title = if (updateCount == 1) {
                newest.presentationTitle(context, result.deviceId)
            } else {
                context.getString(
                    R.string.social_updates_in_group,
                    updateCount,
                    newest.groupName
                )
            }
            val message = if (updateCount == 1) {
                newest.presentationTime(context)
            } else {
                groupChanges.takeLast(3).joinToString("\n") {
                    it.presentationTitle(context, result.deviceId)
                }
            }
            notificationManager.notify(
                notificationId,
                NotificationCompat.Builder(context, NotificationHelper.SOCIAL_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            groupId.hashCode(),
                            Intent(context, MainActivity::class.java)
                                .setAction(SHOW_SOCIAL_ACTIVITY_ACTION)
                                .putExtra(
                                    EXTRA_SOCIAL_ENTITY_TYPE,
                                    if (groupChanges.all {
                                            it.entityType in setOf("alarm", "outcome")
                                        }) "alarm" else "group"
                                ),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .setWhen(eventTime)
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .build()
            )
            accumulation.edit().putInt(groupId, updateCount).apply()
        }
    }

    const val SHOW_SOCIAL_ACTIVITY_ACTION = "com.rispng.jay.SHOW_SOCIAL_ACTIVITY"
    const val EXTRA_SOCIAL_ENTITY_TYPE = "com.rispng.jay.SOCIAL_ENTITY_TYPE"
    const val SYNC_FAILURE_NOTIFICATION_ID = 190_001
    const val ENTITLEMENT_NOTIFICATION_ID = 190_002
}
