package com.bnyro.clock.social.domain

import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class AlarmPermission {
    EVERYONE,
    LEADERS
}

enum class MemberRole {
    MEMBER,
    LEADER
}

enum class AlarmActivityKind {
    SNOOZED,
    DISMISSED
}

@Entity(tableName = "social_groups")
data class SocialGroup(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val alarmPermission: AlarmPermission,
    val notifySnoozed: Boolean,
    val notifyDismissed: Boolean,
    val role: MemberRole
)

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
    val revision: Int
)

@Entity(tableName = "social_activity")
data class SocialActivity(
    @androidx.room.PrimaryKey val id: String,
    val alarmId: String,
    val groupId: String,
    val alarmRevision: Int,
    val deviceId: String,
    val deviceName: String,
    val kind: AlarmActivityKind,
    val occurredAt: String
)

data class AlarmGroupName(
    val localAlarmId: Long,
    val groupId: String,
    val groupName: String
)

@Entity(tableName = "shared_alarm_deliveries", primaryKeys = ["alarmId", "deviceId"])
data class SharedAlarmDelivery(
    val alarmId: String,
    val deviceId: String,
    val revision: Int,
    val deliveredAt: String
)

data class AlarmDeliveryCount(
    val localAlarmId: Long,
    val deliveredCount: Int,
    val memberCount: Int
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
    val token: String
)

@Serializable
data class DeviceUpdate(val name: String)

@Serializable
data class PushTokenUpdate(val token: String)

@Serializable
data class GroupCreate(
    val name: String,
    @SerialName("alarm_permission") val alarmPermission: String,
    @SerialName("notify_snoozed") val notifySnoozed: Boolean,
    @SerialName("notify_dismissed") val notifyDismissed: Boolean
)

@Serializable
data class GroupUpdate(
    val name: String,
    @SerialName("alarm_permission") val alarmPermission: String,
    @SerialName("notify_snoozed") val notifySnoozed: Boolean,
    @SerialName("notify_dismissed") val notifyDismissed: Boolean
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
    val repeat: Boolean,
    @SerialName("snooze_enabled") val snoozeEnabled: Boolean,
    @SerialName("snooze_minutes") val snoozeMinutes: Int,
    @SerialName("sound_enabled") val soundEnabled: Boolean,
    @SerialName("vibration_pattern") val vibrationPattern: List<Int>,
    @SerialName("vibration_pattern_name") val vibrationPatternName: String,
    @SerialName("expected_revision") val expectedRevision: Int? = null
)

@Serializable
data class SharedAlarmDelete(@SerialName("expected_revision") val expectedRevision: Int)

@Serializable
data class AlarmActivityRequest(
    @SerialName("alarm_revision") val alarmRevision: Int,
    val kind: String,
    @SerialName("occurred_at") val occurredAt: String
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
    @SerialName("notify_snoozed") val notifySnoozed: Boolean,
    @SerialName("notify_dismissed") val notifyDismissed: Boolean,
    val role: String
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
    val repeat: Boolean,
    @SerialName("snooze_enabled") val snoozeEnabled: Boolean,
    @SerialName("snooze_minutes") val snoozeMinutes: Int,
    @SerialName("sound_enabled") val soundEnabled: Boolean,
    @SerialName("vibration_pattern") val vibrationPattern: List<Int>,
    @SerialName("vibration_pattern_name") val vibrationPatternName: String,
    val deleted: Boolean
)

@Serializable
data class SocialActivityDto(
    val id: String,
    @SerialName("alarm_id") val alarmId: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("alarm_revision") val alarmRevision: Int,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    val kind: String,
    @SerialName("occurred_at") val occurredAt: String
)

@Serializable
data class SyncResponse(
    val cursor: Long,
    val groups: List<SocialGroupDto>,
    val members: List<SocialMemberDto>,
    val alarms: List<SharedAlarmDto>,
    val activity: List<SocialActivityDto>,
    val deliveries: List<DeliveryDto> = emptyList()
)

@Serializable
data class DeliveryDto(
    @SerialName("alarm_id") val alarmId: String,
    @SerialName("device_id") val deviceId: String,
    val revision: Int,
    @SerialName("delivered_at") val deliveredAt: String
)
