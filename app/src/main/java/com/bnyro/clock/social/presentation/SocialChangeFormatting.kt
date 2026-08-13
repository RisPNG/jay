package com.bnyro.clock.social.presentation

import android.content.Context
import android.text.format.DateUtils
import com.bnyro.clock.R
import com.bnyro.clock.social.domain.SocialChange
import com.bnyro.clock.util.TimeHelper
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

fun SocialChange.presentationTitle(context: Context, deviceId: String): String {
    val actor = actorName ?: context.getString(R.string.unknown_group_member)
    val subject = subjectName ?: entityLabel ?: context.getString(R.string.unknown_group_member)
    val alarm = entityLabel?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.unnamed_shared_alarm)
    return when (action) {
        "created", "edited", "enabled", "disabled", "deleted" -> entityTime?.let {
            context.getString(
                R.string.shared_alarm_change_title,
                actor,
                action,
                alarm,
                TimeHelper.millisToFormatted(it),
                groupName
            )
        } ?: context.getString(
            R.string.social_alarm_activity_title,
            actor,
            action,
            alarm,
            groupName
        )
        "snoozed", "dismissed", "ignored" -> entityTime?.let {
            context.getString(
                R.string.shared_alarm_change_title,
                actor,
                action,
                alarm,
                TimeHelper.millisToFormatted(it),
                groupName
            )
        } ?: context.getString(
            R.string.social_alarm_activity_title,
            actor,
            action,
            alarm,
            groupName
        )
        "joined" -> context.getString(R.string.member_joined_group, subject, groupName)
        "left" -> context.getString(R.string.member_left_group, subject, groupName)
        "removed" -> if (subjectDeviceId == deviceId) {
            context.getString(R.string.you_were_removed_from_group, groupName, actor)
        } else {
            context.getString(R.string.member_removed_from_group, actor, subject, groupName)
        }
        "promoted" -> if (subjectDeviceId == deviceId) {
            context.getString(R.string.you_were_promoted_in_group, actor, groupName)
        } else {
            context.getString(R.string.member_promoted_in_group, actor, subject, groupName)
        }
        "demoted" -> if (subjectDeviceId == deviceId) {
            context.getString(R.string.you_were_demoted_in_group, actor, groupName)
        } else {
            context.getString(R.string.member_demoted_in_group, actor, subject, groupName)
        }
        "renamed" -> context.getString(
            R.string.member_renamed,
            (details?.get("previous_name") as? JsonPrimitive)?.contentOrNull ?: actor,
            subject,
            groupName
        )
        "updated" -> when {
            details?.get("previous_alarm_permission") != details?.get("alarm_permission") ->
                context.getString(R.string.group_alarm_permissions_updated, actor, groupName)
            details?.get("previous_name") != details?.get("name") -> context.getString(
                R.string.group_renamed,
                actor,
                (details?.get("previous_name") as? JsonPrimitive)?.contentOrNull ?: groupName,
                (details?.get("name") as? JsonPrimitive)?.contentOrNull ?: groupName
            )
            else -> context.getString(R.string.group_notification_policy_updated, actor, groupName)
        }
        "invitation_created" -> context.getString(R.string.group_invitation_created, actor)
        "delivered" -> context.getString(R.string.alarm_delivered_to_member, alarm, subject)
        "corrected" -> context.getString(R.string.ignored_alarm_corrected, alarm, groupName)
        else -> context.getString(R.string.group_activity_updated, groupName)
    }
}

fun SocialChange.presentationDetails(context: Context): String? {
    val detailItems = when {
        action == "edited" -> buildList {
            if (details?.get("previous_label") != details?.get("label")) add("name")
            if (details?.get("previous_time") != details?.get("time")) add("time")
            if (details?.get("previous_days") != details?.get("days")) add("days")
            if (details?.get("previous_repeat") != details?.get("repeat")) add("repeat")
            if (details?.get("previous_snooze_enabled") != details?.get("snooze_enabled")) {
                add("snooze")
            }
            if (details?.get("previous_snooze_minutes") != details?.get("snooze_minutes")) {
                add("snooze duration")
            }
            if (details?.get("previous_sound_enabled") != details?.get("sound_enabled")) {
                add("sound")
            }
            if (details?.get("previous_vibrate") != details?.get("vibrate")) add("vibration")
            if (
                details?.get("previous_vibration_pattern") != details?.get("vibration_pattern") ||
                details?.get("previous_vibration_pattern_name") !=
                details?.get("vibration_pattern_name")
            ) add("vibration pattern")
        }
        action == "updated" -> buildList {
            if (details?.get("previous_name") != details?.get("name")) add("group name")
            if (
                details?.get("previous_alarm_permission") != details?.get("alarm_permission")
            ) add("alarm permissions")
            if (
                details?.get("previous_notify_alarm_changes") !=
                details?.get("notify_alarm_changes")
            ) add("alarm-change notifications")
            if (
                details?.get("previous_notify_snoozed") != details?.get("notify_snoozed")
            ) add("snooze notifications")
            if (
                details?.get("previous_notify_dismissed") != details?.get("notify_dismissed")
            ) add("dismissal notifications")
            if (
                details?.get("previous_notify_ignored") != details?.get("notify_ignored")
            ) add("ignored-alarm notifications")
        }
        action == "ignored" -> listOfNotNull(
            (details?.get("reason") as? JsonPrimitive)?.contentOrNull?.replace('_', ' ')
        )
        action == "corrected" -> listOfNotNull(
            (details?.get("corrected_by") as? JsonPrimitive)?.contentOrNull?.let {
                "Corrected by a later $it report"
            }
        )
        else -> emptyList()
    }
    return detailItems.takeIf { it.isNotEmpty() }?.joinToString(", ")
        ?.replaceFirstChar { it.uppercase() }
}

fun SocialChange.presentationTime(context: Context): String {
    val eventTime = Instant.parse(occurredAt).toEpochMilli()
    return if (DateUtils.isToday(eventTime)) {
        DateUtils.formatDateTime(context, eventTime, DateUtils.FORMAT_SHOW_TIME)
    } else {
        context.getString(
            R.string.social_event_date_time,
            DateUtils.formatDateTime(
                context,
                eventTime,
                DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_SHOW_YEAR or
                    DateUtils.FORMAT_ABBREV_MONTH
            ),
            DateUtils.formatDateTime(context, eventTime, DateUtils.FORMAT_SHOW_TIME)
        )
    }
}
