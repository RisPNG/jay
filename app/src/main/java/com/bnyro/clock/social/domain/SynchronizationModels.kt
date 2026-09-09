package com.bnyro.clock.social.domain

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Entity(tableName = "sync_scopes")
data class SyncScopeState(
    @PrimaryKey val route: String,
    val scopeId: String,
    val cursor: String?,
    val stagingCursor: String? = null,
    val stagingMode: String? = null,
    val revision: Long = 0
)

@Entity(tableName = "sync_resources", primaryKeys = ["scopeId", "kind", "key"])
data class SyncedResource(
    val scopeId: String,
    val kind: String,
    val key: String,
    val representation: String
)

@Entity(tableName = "sync_staging", primaryKeys = ["scopeId", "kind", "key"])
data class StagedResource(
    val scopeId: String,
    val kind: String,
    val key: String,
    val action: String,
    val representation: String
)

@Entity(tableName = "pending_social_activity", primaryKeys = ["scopeId", "key"])
data class PendingSocialActivity(
    val scopeId: String,
    val key: String,
    val representation: String
)

@Entity(tableName = "pending_operations", indices = [Index(value = ["operationId"], unique = true), Index("state")])
data class PendingOperation(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val operationId: String,
    val scopeId: String,
    val kind: String,
    val targetId: String,
    val method: String,
    val path: String,
    val payload: String?,
    val optimisticState: String?,
    val rawSavedAt: Long,
    val effectiveSavedAt: Long,
    val observedRevision: Long = 0,
    val dependencyId: String? = null,
    val state: String = "pending",
    val rejectionCode: String? = null,
    val response: String? = null,
    val audioSourcePath: String? = null
)

@Entity(tableName = "sync_clock")
data class SyncClockState(
    @PrimaryKey val id: Int = 1,
    val serverMillis: Long,
    val wallMillis: Long,
    val elapsedMillis: Long,
    val bootCount: Int,
    val lastSavedMillis: Long
)

@Serializable
data class ScopeSyncPage(
    @SerialName("scope_id") val scopeId: String,
    val mode: String,
    @SerialName("from_revision") val fromRevision: Long,
    @SerialName("through_revision") val throughRevision: Long,
    @SerialName("next_cursor") val nextCursor: String,
    @SerialName("has_more") val hasMore: Boolean,
    val items: List<ScopeSyncItem>,
    @SerialName("server_time") val serverTime: String
)

@Serializable
data class ScopeSyncItem(
    val kind: String,
    val key: String,
    val action: String,
    val data: JsonObject,
    val revision: Long,
    val ordinal: Int
)

@Serializable
data class MembershipAccessDto(
    val id: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("scope_id") val scopeId: String,
    val role: String,
    @SerialName("notify_membership") val notifyMembership: Boolean,
    @SerialName("notify_administrative") val notifyAdministrative: Boolean
)

@Serializable
data class SharedSoundDto(
    val id: String,
    @SerialName("group_id") val groupId: String,
    val title: String,
    val status: String,
    val sha256: String? = null,
    @SerialName("byte_length") val byteLength: Long? = null,
    @SerialName("duration_ms") val durationMs: Int? = null,
    @SerialName("failure_code") val failureCode: String? = null
)
