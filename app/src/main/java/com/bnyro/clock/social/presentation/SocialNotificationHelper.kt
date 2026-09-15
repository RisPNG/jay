package com.bnyro.clock.social.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.Permission
import com.bnyro.clock.social.data.SocialSyncResult
import com.bnyro.clock.ui.MainActivity
import java.time.OffsetDateTime

object SocialNotificationHelper {
    fun createNotificationChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(
                SOCIAL_CHANNEL,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            )
                .setName(context.getString(R.string.shared_alarm_activity))
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
        val grouped = (changes - direct.toSet()).groupBy {
            it.groupId to it.entityId.takeIf { _ -> it.entityType in setOf("alarm", "outcome") }
        }
        val notificationManager = NotificationManagerCompat.from(context)

        direct.forEach { change ->
            val eventTime = OffsetDateTime.parse(change.occurredAt).toInstant().toEpochMilli()
            notificationManager.notify(
                change.sequence.hashCode(),
                NotificationCompat.Builder(context, SOCIAL_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(change.presentationTitle(context, result.deviceId))
                    .setContentText(change.presentationTime(context))
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            change.sequence.hashCode(),
                            Intent(context, MainActivity::class.java)
                                .setAction(SHOW_SOCIAL_ACTIVITY_ACTION)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra(EXTRA_SOCIAL_ENTITY_TYPE, change.entityType)
                                .putExtra(EXTRA_SOCIAL_GROUP_ID, change.groupId),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .setWhen(eventTime)
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .build()
            )
        }

        grouped.forEach { (destination, groupChanges) ->
            val (groupId, alarmId) = destination
            val notificationKey = "$groupId:${alarmId ?: "group"}"
            val newest = groupChanges.last()
            val eventTime = OffsetDateTime.parse(newest.occurredAt).toInstant().toEpochMilli()
            val notificationId = notificationKey.hashCode()
            val accumulation = context.getSharedPreferences(
                "jay_social_notification_accumulation",
                Context.MODE_PRIVATE
            )
            val isActive = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .activeNotifications.any { it.tag == notificationKey && it.id == notificationId }
            val updateCount = groupChanges.size + if (isActive) {
                accumulation.getInt(notificationKey, 0)
            } else {
                0
            }
            val title = if (updateCount == 1) {
                newest.presentationTitle(context, result.deviceId)
            } else if (alarmId != null) {
                context.getString(
                    R.string.social_updates_for_alarm,
                    updateCount,
                    newest.entityLabel?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.unnamed_shared_alarm),
                    newest.groupName
                )
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
                notificationKey,
                notificationId,
                NotificationCompat.Builder(context, SOCIAL_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            notificationId,
                            Intent(context, MainActivity::class.java)
                                .setAction(SHOW_SOCIAL_ACTIVITY_ACTION)
                                .setData(Uri.Builder().scheme("jay").authority("activity").appendPath(groupId).appendPath(alarmId ?: "group").build())
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra(
                                    EXTRA_SOCIAL_ENTITY_TYPE,
                                    if (alarmId != null) "alarm" else "group"
                                )
                                .putExtra(EXTRA_SOCIAL_GROUP_ID, groupId)
                                .putExtra(EXTRA_SOCIAL_ENTITY_ID, alarmId),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .setWhen(eventTime)
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .build()
            )
            accumulation.edit().putInt(notificationKey, updateCount).apply()
        }
    }

    const val SHOW_SOCIAL_ACTIVITY_ACTION = "com.rispng.jay.SHOW_SOCIAL_ACTIVITY"
    const val EXTRA_SOCIAL_ENTITY_TYPE = "com.rispng.jay.SOCIAL_ENTITY_TYPE"
    const val EXTRA_SOCIAL_GROUP_ID = "com.rispng.jay.SOCIAL_GROUP_ID"
    const val EXTRA_SOCIAL_ENTITY_ID = "com.rispng.jay.SOCIAL_ENTITY_ID"
    const val SOCIAL_CHANNEL = "social"
}
