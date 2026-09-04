package com.bnyro.clock.social.domain

import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class AlarmPermission {
    EVERYONE,
    LEADERS
}

enum class MemberRole {
    MEMBER,
    LEADER
}

enum class AlarmTimeBasis {
    MEMBER_LOCAL,
    GROUP_TIME_ZONE
}

enum class SharedSoundMode {
    OFF,
    MEMBER_DEFAULT,
    SHARED
}

enum class AlarmActivityKind {
    SNOOZED,
    DISMISSED,
    IGNORED
}

const val PERSONAL_ALARM_SOURCE_ID = "personal"

@Entity(tableName = "social_groups")
data class SocialGroup(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val alarmPermission: AlarmPermission,
    val notifyAlarmChanges: Boolean,
    val notifySnoozed: Boolean,
    val notifyDismissed: Boolean,
    val notifyIgnored: Boolean,
    val notifyMembership: Boolean,
    val notifyAdministrative: Boolean,
    val role: MemberRole,
    val alarmTimeBasis: AlarmTimeBasis = AlarmTimeBasis.MEMBER_LOCAL,
    val alarmTimeZone: String = "UTC"
)

val SocialGroup.canEditAlarms: Boolean
    get() = alarmPermission == AlarmPermission.EVERYONE || role == MemberRole.LEADER

@Entity(tableName = "social_members", primaryKeys = ["groupId", "deviceId"])
data class SocialMember(
    val groupId: String,
    val deviceId: String,
    val name: String,
    val role: MemberRole
)

@Entity(
    tableName = "shared_alarm_links",
    indices = [Index(value = ["localAlarmId"], unique = true), Index("groupId")]
)
data class SharedAlarmLink(
    @androidx.room.PrimaryKey val remoteAlarmId: String,
    val localAlarmId: Long,
    val groupId: String,
    val revision: Int,
    val soundMode: SharedSoundMode,
    val soundId: String?,
    val soundTitle: String?,
    val timeZone: String?
)

data class AlarmGroupName(
    val localAlarmId: Long,
    val remoteAlarmId: String,
    val groupId: String,
    val groupName: String
)

@Entity(tableName = "dismissed_shared_timers")
data class DismissedSharedTimer(
    @androidx.room.PrimaryKey val timerId: String,
    val expiresAt: Long
)

data class SocialChange(
    val sequence: Long,
    val groupId: String,
    val groupName: String,
    val entityType: String,
    val entityId: String,
    val action: String,
    val entityLabel: String?,
    val entityTime: Long?,
    val actorDeviceId: String?,
    val actorName: String?,
    val subjectDeviceId: String?,
    val subjectName: String?,
    val recipientDeviceId: String?,
    val details: JsonObject?,
    val occurredAt: String
)

data class SocialActivityPage(
    val items: List<SocialChange>,
    val nextBefore: Long?
)

@Serializable
data class DeviceNameWords(
    val adjectives: List<String>,
    val nouns: List<String>
)

@Serializable
data class DeviceRegistration(
    val id: String,
    val name: String,
    val token: String,
    @SerialName("time_zone") val timeZone: String
)

@Serializable
data class DeviceUpdate(val name: String)

@Serializable
data class PushTokenUpdate(val token: String)

@Serializable
data class PlayEntitlementVerification(
    @SerialName("integrity_token") val integrityToken: String
)

@Serializable
data class PlayEntitlementStatus(
    @SerialName("shared_sound_upload") val sharedSoundUpload: Boolean,
    @SerialName("expires_at") val expiresAt: String? = null
)

@Serializable
data class GroupCreate(
    val name: String,
    @SerialName("alarm_permission") val alarmPermission: String,
    @SerialName("notify_alarm_changes") val notifyAlarmChanges: Boolean,
    @SerialName("notify_snoozed") val notifySnoozed: Boolean,
    @SerialName("notify_dismissed") val notifyDismissed: Boolean,
    @SerialName("notify_ignored") val notifyIgnored: Boolean,
    @SerialName("alarm_time_basis") val alarmTimeBasis: String = "member_local",
    @SerialName("alarm_time_zone") val alarmTimeZone: String = "UTC"
)

@Serializable
data class GroupUpdate(
    val name: String,
    @SerialName("alarm_permission") val alarmPermission: String,
    @SerialName("notify_alarm_changes") val notifyAlarmChanges: Boolean,
    @SerialName("notify_snoozed") val notifySnoozed: Boolean,
    @SerialName("notify_dismissed") val notifyDismissed: Boolean,
    @SerialName("notify_ignored") val notifyIgnored: Boolean,
    @SerialName("alarm_time_basis") val alarmTimeBasis: String,
    @SerialName("alarm_time_zone") val alarmTimeZone: String
)

@Serializable
data class SharedSoundSelection(
    val mode: String,
    @SerialName("sound_id") val soundId: String? = null
)

@Serializable
data class MemberNotificationUpdate(
    @SerialName("notify_membership") val notifyMembership: Boolean,
    @SerialName("notify_administrative") val notifyAdministrative: Boolean
)

@Serializable
data class InviteCreate(@SerialName("expires_in_hours") val expiresInHours: Int? = null)

@Serializable
data class InviteJoin(val token: String)

@Serializable
data class MemberUpdate(val role: String)

@Serializable
data class SharedAlarmRequest(
    @SerialName("group_id") val groupId: String? = null,
    val time: Long,
    val label: String?,
    val enabled: Boolean,
    val days: List<Int>,
    val vibrate: Boolean,
    @SerialName("start_date") val startDate: Long,
    @SerialName("repeat_interval") val repeatInterval: Int,
    @SerialName("repeat_unit") val repeatUnit: String,
    @SerialName("repeat_anchor") val repeatAnchor: String,
    @SerialName("repeat_duration") val repeatDuration: Int? = null,
    @SerialName("repeat_duration_unit") val repeatDurationUnit: String,
    @SerialName("end_date") val endDate: Long? = null,
    @SerialName("end_occurrences") val endOccurrences: Int? = null,
    val advanced: Boolean = false,
    @SerialName("snooze_enabled") val snoozeEnabled: Boolean,
    @SerialName("snooze_minutes") val snoozeMinutes: Int,
    @SerialName("sound_enabled") val soundEnabled: Boolean,
    @SerialName("vibration_pattern") val vibrationPattern: List<Int>,
    @SerialName("vibration_pattern_name") val vibrationPatternName: String,
    @SerialName("sound_change") val soundChange: SharedSoundSelection? = null,
    @SerialName("expected_revision") val expectedRevision: Int? = null
)

@Serializable
data class SharedAlarmDelete(@SerialName("expected_revision") val expectedRevision: Int)

@Serializable
data class AlarmActivityRequest(
    val id: String,
    @SerialName("alarm_revision") val alarmRevision: Int,
    val kind: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("occurrence_id") val occurrenceId: String? = null,
    val reason: String? = null
)

@Serializable
data class AlarmOccurrenceSchedule(
    @SerialName("alarm_revision") val alarmRevision: Int,
    @SerialName("occurrence_id") val occurrenceId: String,
    @SerialName("trigger_at") val triggerAt: String,
    @SerialName("deadline_at") val deadlineAt: String,
    @SerialName("cycle_date") val cycleDate: String
)

@Serializable
data class SharedSoundUploadRequest(
    val title: String,
    val sha256: String,
    @SerialName("byte_length") val byteLength: Long,
    @SerialName("duration_ms") val durationMs: Int
)

@Serializable
data class SharedSoundUploadResponse(
    val id: String,
    val url: String,
    val headers: Map<String, String>
)

@Serializable
data class SharedSoundDownloadResponse(
    val url: String,
    val sha256: String,
    @SerialName("byte_length") val byteLength: Long
)

@Serializable
data class IdResponse(
    val id: String,
    val revision: Int? = null,
    @SerialName("group_id") val groupId: String? = null
)

@Serializable
data class InviteResponse(
    val id: String,
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
    val url: String
)

@Serializable
data class SocialGroupDto(
    val id: String,
    val name: String,
    @SerialName("alarm_permission") val alarmPermission: String,
    @SerialName("notify_alarm_changes") val notifyAlarmChanges: Boolean,
    @SerialName("notify_snoozed") val notifySnoozed: Boolean,
    @SerialName("notify_dismissed") val notifyDismissed: Boolean,
    @SerialName("notify_ignored") val notifyIgnored: Boolean,
    @SerialName("notify_membership") val notifyMembership: Boolean,
    @SerialName("notify_administrative") val notifyAdministrative: Boolean,
    val role: String,
    @SerialName("alarm_time_basis") val alarmTimeBasis: String = "member_local",
    @SerialName("alarm_time_zone") val alarmTimeZone: String = "UTC"
)

@Serializable
data class SocialMemberDto(
    @SerialName("group_id") val groupId: String,
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val role: String
)

@Serializable
data class SharedAlarmDto(
    val id: String,
    @SerialName("group_id") val groupId: String,
    val revision: Int,
    val time: Long,
    val label: String?,
    val enabled: Boolean,
    val days: List<Int>,
    val vibrate: Boolean,
    @SerialName("start_date") val startDate: Long,
    @SerialName("repeat_interval") val repeatInterval: Int,
    @SerialName("repeat_unit") val repeatUnit: String,
    @SerialName("repeat_anchor") val repeatAnchor: String,
    @SerialName("repeat_duration") val repeatDuration: Int? = null,
    @SerialName("repeat_duration_unit") val repeatDurationUnit: String,
    @SerialName("end_date") val endDate: Long? = null,
    @SerialName("end_occurrences") val endOccurrences: Int? = null,
    val advanced: Boolean = false,
    @SerialName("snooze_enabled") val snoozeEnabled: Boolean,
    @SerialName("snooze_minutes") val snoozeMinutes: Int,
    @SerialName("sound_enabled") val soundEnabled: Boolean,
    @SerialName("vibration_pattern") val vibrationPattern: List<Int>,
    @SerialName("vibration_pattern_name") val vibrationPatternName: String,
    @SerialName("sound_mode") val soundMode: String = "member_default",
    @SerialName("sound_id") val soundId: String? = null,
    @SerialName("sound_title") val soundTitle: String? = null,
    val deleted: Boolean
)

@Serializable
data class SocialChangeDto(
    val sequence: Long,
    @SerialName("group_id") val groupId: String,
    @SerialName("group_name") val groupName: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val action: String,
    @SerialName("entity_label") val entityLabel: String? = null,
    @SerialName("entity_time") val entityTime: Long? = null,
    @SerialName("actor_device_id") val actorDeviceId: String? = null,
    @SerialName("actor_name") val actorName: String? = null,
    @SerialName("subject_device_id") val subjectDeviceId: String? = null,
    @SerialName("subject_name") val subjectName: String? = null,
    @SerialName("recipient_device_id") val recipientDeviceId: String? = null,
    val details: JsonObject? = null,
    @SerialName("occurred_at") val occurredAt: String
)

@Serializable
data class ActivityPageDto(
    val items: List<SocialChangeDto>,
    @SerialName("next_before") val nextBefore: Long? = null
)

@Serializable
data class SharedTimerRequest(
    val label: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int,
    @SerialName("increment_seconds") val incrementSeconds: Int,
    val vibrate: Boolean = true,
    @SerialName("sound_enabled") val soundEnabled: Boolean = true,
    @SerialName("vibration_pattern") val vibrationPattern: List<Int>,
    @SerialName("vibration_pattern_name") val vibrationPatternName: String,
    val sound: SharedSoundSelection
)

@Serializable
data class SharedTimerActionRequest(val action: String)

@Serializable
data class SharedTimerDto(
    val id: String,
    @SerialName("group_id") val groupId: String,
    val label: String?,
    @SerialName("duration_seconds") val durationSeconds: Int,
    @SerialName("increment_seconds") val incrementSeconds: Int,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("started_by") val startedBy: String,
    val vibrate: Boolean = true,
    @SerialName("sound_enabled") val soundEnabled: Boolean = true,
    @SerialName("vibration_pattern") val vibrationPattern: List<Int> = emptyList(),
    @SerialName("vibration_pattern_name") val vibrationPatternName: String = "Default",
    @SerialName("sound_mode") val soundMode: String = "member_default",
    @SerialName("sound_id") val soundId: String? = null,
    @SerialName("sound_title") val soundTitle: String? = null
)

@Serializable
data class SyncResponse(
    val cursor: Long,
    val groups: List<SocialGroupDto>,
    val members: List<SocialMemberDto>,
    val alarms: List<SharedAlarmDto>,
    val timers: List<SharedTimerDto> = emptyList(),
    val changes: List<SocialChangeDto> = emptyList()
)
