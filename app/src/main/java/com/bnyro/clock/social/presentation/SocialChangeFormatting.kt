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
                TimeHelper.millisToFormatted(context, it),
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
                TimeHelper.millisToFormatted(context, it),
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

fun SocialChange.groupLogTitle(context: Context): String {
    val actor = actorName ?: context.getString(R.string.unknown_group_member)
    val subject = subjectName ?: entityLabel ?: context.getString(R.string.unknown_group_member)
    return when (action) {
        "created" -> context.getString(R.string.group_log_created, actor)
        "joined" -> context.getString(R.string.group_log_joined, subject)
        "left" -> context.getString(R.string.group_log_left, subject)
        "removed" -> context.getString(R.string.group_log_removed, actor, subject)
        "promoted" -> context.getString(R.string.group_log_promoted, actor, subject)
        "demoted" -> context.getString(R.string.group_log_demoted, actor, subject)
        "renamed" -> context.getString(
            R.string.group_log_member_renamed,
            (details?.get("previous_name") as? JsonPrimitive)?.contentOrNull ?: actor,
            subject
        )
        "updated" -> when {
            details?.get("previous_alarm_permission") != details?.get("alarm_permission") ->
                context.getString(R.string.group_log_permissions_updated, actor)
            details?.get("previous_name") != details?.get("name") -> context.getString(
                R.string.group_log_renamed,
                actor,
                (details?.get("previous_name") as? JsonPrimitive)?.contentOrNull ?: groupName,
                (details?.get("name") as? JsonPrimitive)?.contentOrNull ?: groupName
            )
            details?.get("previous_alarm_time_basis") != details?.get("alarm_time_basis") ||
                details?.get("previous_alarm_time_zone") != details?.get("alarm_time_zone") ->
                context.getString(R.string.group_log_time_zone_updated, actor)
            else -> context.getString(R.string.group_log_notifications_updated, actor)
        }
        "invitation_created" -> context.getString(R.string.group_log_invitation_created, actor)
        else -> context.getString(R.string.group_activity_updated, groupName)
    }
}

fun SocialChange.alarmLogTitle(context: Context): String {
    val actor = actorName ?: context.getString(R.string.unknown_group_member)
    val subject = subjectName ?: context.getString(R.string.unknown_group_member)
    return when (action) {
        "created" -> context.getString(R.string.alarm_log_created, actor)
        "edited" -> context.getString(R.string.alarm_log_edited, actor)
        "enabled" -> context.getString(R.string.alarm_log_enabled, actor)
        "disabled" -> context.getString(R.string.alarm_log_disabled, actor)
        "deleted" -> if (
            (details?.get("reason") as? JsonPrimitive)?.contentOrNull ==
            "three_inactive_cycles"
        ) {
            context.getString(R.string.alarm_log_deleted_inactive)
        } else {
            context.getString(R.string.alarm_log_deleted, actor)
        }
        "snoozed" -> context.getString(R.string.alarm_log_snoozed, actor)
        "dismissed" -> context.getString(R.string.alarm_log_dismissed, actor)
        "ignored" -> context.getString(R.string.alarm_log_ignored, actor)
        "delivered" -> context.getString(R.string.alarm_log_delivered, subject)
        "corrected" -> context.getString(R.string.alarm_log_corrected)
        else -> context.getString(R.string.group_activity_updated, groupName)
    }
}

fun SocialChange.logDetails(context: Context): String? {
    val detailItems = when {
        entityType == "alarm" && action == "created" -> buildList {
            entityLabel?.takeIf { it.isNotBlank() }?.let {
                add(context.getString(R.string.alarm_log_name, it))
            }
            entityTime?.let {
                add(context.getString(R.string.alarm_log_time, TimeHelper.millisToFormatted(context, it)))
            }
        }
        entityType == "alarm" && action == "edited" -> buildList {
            if (details?.get("previous_label") != details?.get("label")) {
                add(
                    context.getString(
                        R.string.alarm_log_name_changed,
                        (details?.get("previous_label") as? JsonPrimitive)?.contentOrNull
                            ?: context.getString(R.string.unnamed_shared_alarm),
                        (details?.get("label") as? JsonPrimitive)?.contentOrNull
                            ?: context.getString(R.string.unnamed_shared_alarm)
                    )
                )
            }
            if (details?.get("previous_time") != details?.get("time")) {
                val previousTime = (details?.get("previous_time") as? JsonPrimitive)?.contentOrNull
                    ?.toLongOrNull()
                val time = (details?.get("time") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                if (previousTime != null && time != null) {
                    add(
                        context.getString(
                            R.string.alarm_log_time_changed,
                            TimeHelper.millisToFormatted(context, previousTime),
                            TimeHelper.millisToFormatted(context, time)
                        )
                    )
                }
            }
            val otherChanges = buildList {
                if (details?.get("previous_days") != details?.get("days")) {
                    add(context.getString(R.string.days))
                }
                if (
                    details?.get("previous_repeat_interval") != details?.get("repeat_interval") ||
                    details?.get("previous_repeat_unit") != details?.get("repeat_unit")
                ) {
                    add(context.getString(R.string.repeats_every))
                }
                if (
                    details?.get("previous_repeat_duration") != details?.get("repeat_duration")
                ) {
                    add(context.getString(R.string.repeats_for))
                }
                if (
                    details?.get("previous_end_occurrences") != details?.get("end_occurrences")
                ) {
                    add(context.getString(R.string.ends))
                }
                if (details?.get("previous_snooze_enabled") != details?.get("snooze_enabled")) {
                    add(context.getString(R.string.snooze))
                }
                if (details?.get("previous_snooze_minutes") != details?.get("snooze_minutes")) {
                    add(context.getString(R.string.alarm_log_snooze_duration))
                }
                if (details?.get("previous_sound_enabled") != details?.get("sound_enabled")) {
                    add(context.getString(R.string.sound))
                }
                if (details?.get("previous_vibrate") != details?.get("vibrate")) {
                    add(context.getString(R.string.vibrate))
                }
                if (
                    details?.get("previous_vibration_pattern") != details?.get("vibration_pattern") ||
                    details?.get("previous_vibration_pattern_name") !=
                    details?.get("vibration_pattern_name")
                ) add(context.getString(R.string.alarm_log_vibration_pattern))
            }
            if (otherChanges.isNotEmpty()) {
                add(
                    context.getString(
                        R.string.alarm_log_other_changes,
                        otherChanges.joinToString(", ")
                    )
                )
            }
        }
        action == "corrected" -> listOfNotNull(
            (details?.get("corrected_by") as? JsonPrimitive)?.contentOrNull?.let {
                context.getString(R.string.alarm_log_correction, it)
            }
        )
        else -> emptyList()
    }
    return detailItems.takeIf { it.isNotEmpty() }?.joinToString("\n")
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
