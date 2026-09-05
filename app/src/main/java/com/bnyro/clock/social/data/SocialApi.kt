package com.bnyro.clock.social.data

import com.bnyro.clock.social.domain.AlarmActivityRequest
import com.bnyro.clock.social.domain.AlarmOccurrenceSchedule
import com.bnyro.clock.social.domain.DeviceRegistration
import com.bnyro.clock.social.domain.DeviceUpdate
import com.bnyro.clock.social.domain.GroupCreate
import com.bnyro.clock.social.domain.GroupUpdate
import com.bnyro.clock.social.domain.IdResponse
import com.bnyro.clock.social.domain.InviteCreate
import com.bnyro.clock.social.domain.InviteJoin
import com.bnyro.clock.social.domain.InviteResponse
import com.bnyro.clock.social.domain.MemberUpdate
import com.bnyro.clock.social.domain.MemberNotificationUpdate
import com.bnyro.clock.social.domain.ActivityPageDto
import com.bnyro.clock.social.domain.PlayEntitlementStatus
import com.bnyro.clock.social.domain.PlayEntitlementVerification
import com.bnyro.clock.social.domain.SharedAlarmDelete
import com.bnyro.clock.social.domain.SharedAlarmRequest
import com.bnyro.clock.social.domain.SharedTimerActionRequest
import com.bnyro.clock.social.domain.SharedTimerRequest
import com.bnyro.clock.social.domain.SharedSoundDownloadResponse
import com.bnyro.clock.social.domain.SharedSoundUploadRequest
import com.bnyro.clock.social.domain.SharedSoundUploadResponse
import com.bnyro.clock.social.domain.SyncResponse
import com.bnyro.clock.social.domain.PushTokenUpdate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.time.ZoneId
import java.io.File

class SocialApi(
    serverUrl: String,
    private val identity: DeviceIdentity
) {
    private val baseUrl = URI(serverUrl).normalize().toString().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun register() {
        request(
            "/v1/devices/register",
            "POST",
            json.encodeToString(
                DeviceRegistration(
                    identity.id,
                    identity.name,
                    identity.token,
                    ZoneId.systemDefault().id
                )
            ),
            authenticated = false
        )
    }

    fun updateDevice(name: String) {
        request("/v1/device", "PATCH", json.encodeToString(DeviceUpdate(name)))
    }

    fun updatePushToken(token: String) {
        request(
            "/v1/device/push-token",
            "PUT",
            json.encodeToString(PushTokenUpdate(token))
        )
    }

    fun updatePlayEntitlement(integrityToken: String): PlayEntitlementStatus =
        json.decodeFromString(
            request(
                "/v1/device/play-entitlement",
                "POST",
                json.encodeToString(PlayEntitlementVerification(integrityToken))
            )
        )

    fun deleteDevice() {
        request("/v1/device", "DELETE")
    }

    fun synchronize(cursor: Long): SyncResponse = json.decodeFromString(
        request("/v1/sync?since=$cursor")
    )

    fun createGroup(group: GroupCreate): IdResponse = json.decodeFromString(
        request("/v1/groups", "POST", json.encodeToString(group))
    )

    fun updateGroup(groupId: String, group: GroupUpdate) {
        request("/v1/groups/$groupId", "PATCH", json.encodeToString(group))
    }

    fun leaveGroup(groupId: String) {
        request("/v1/groups/$groupId/membership", "DELETE")
    }

    fun deleteGroup(groupId: String) {
        request("/v1/groups/$groupId", "DELETE")
    }

    fun createInvite(groupId: String): InviteResponse = json.decodeFromString(
        request("/v1/groups/$groupId/invites", "POST", json.encodeToString(InviteCreate()))
    )

    fun joinGroup(token: String) {
        request("/v1/groups/join", "POST", json.encodeToString(InviteJoin(token)))
    }

    fun updateMember(groupId: String, deviceId: String, role: String) {
        request(
            "/v1/groups/$groupId/members/$deviceId",
            "PATCH",
            json.encodeToString(MemberUpdate(role))
        )
    }

    fun updateMemberNotificationSettings(groupId: String, update: MemberNotificationUpdate) {
        request(
            "/v1/groups/$groupId/notification-settings",
            "PATCH",
            json.encodeToString(update)
        )
    }

    fun removeMember(groupId: String, deviceId: String) {
        request("/v1/groups/$groupId/members/$deviceId", "DELETE")
    }

    fun beginSoundUpload(
        groupId: String,
        upload: SharedSoundUploadRequest
    ): SharedSoundUploadResponse = json.decodeFromString(
        request(
            "/v1/groups/$groupId/sounds/uploads",
            "POST",
            json.encodeToString(upload)
        )
    )

    fun uploadSound(upload: SharedSoundUploadResponse, file: File) {
        val connection = URI(upload.url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(file.length())
        upload.headers.forEach(connection::setRequestProperty)
        connection.outputStream.use { output -> file.inputStream().use { it.copyTo(output) } }
        val status = connection.responseCode
        val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (status !in 200..299) throw SocialApiException(status, response)
    }

    fun completeSoundUpload(soundId: String) {
        request("/v1/sounds/$soundId/complete", "POST")
    }

    fun getSoundDownload(soundId: String): SharedSoundDownloadResponse =
        json.decodeFromString(request("/v1/sounds/$soundId/download"))

    fun downloadSound(download: SharedSoundDownloadResponse, file: File) {
        val connection = URI(download.url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        val status = connection.responseCode
        if (status !in 200..299) {
            val response = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            throw SocialApiException(status, response)
        }
        connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        connection.disconnect()
    }

    fun createAlarm(alarm: SharedAlarmRequest): IdResponse = json.decodeFromString(
        request("/v1/alarms", "POST", json.encodeToString(alarm))
    )

    fun updateAlarm(alarmId: String, alarm: SharedAlarmRequest): IdResponse =
        json.decodeFromString(
            request("/v1/alarms/$alarmId", "PUT", json.encodeToString(alarm))
        )

    fun deleteAlarm(alarmId: String, revision: Int): IdResponse = json.decodeFromString(
        request(
            "/v1/alarms/$alarmId",
            "DELETE",
            json.encodeToString(SharedAlarmDelete(revision))
        )
    )

    fun recordActivity(alarmId: String, activity: AlarmActivityRequest) {
        request("/v1/alarms/$alarmId/activity", "POST", json.encodeToString(activity))
    }

    fun registerAlarmOccurrence(alarmId: String, occurrence: AlarmOccurrenceSchedule) {
        request(
            "/v1/alarms/$alarmId/occurrence",
            "PUT",
            json.encodeToString(occurrence)
        )
    }

    fun startTimer(groupId: String, timer: SharedTimerRequest): IdResponse =
        json.decodeFromString(
            request("/v1/groups/$groupId/timers", "POST", json.encodeToString(timer))
        )

    fun adjustTimer(timerId: String, action: String) {
        request(
            "/v1/timers/$timerId",
            "PATCH",
            json.encodeToString(SharedTimerActionRequest(action))
        )
    }

    fun cancelTimer(timerId: String) {
        request("/v1/timers/$timerId", "DELETE")
    }

    fun getGroupActivity(groupId: String, before: Long?): ActivityPageDto =
        json.decodeFromString(
            request("/v1/groups/$groupId/activity${before?.let { "?before=$it" }.orEmpty()}")
        )

    fun getAlarmActivity(alarmId: String, before: Long?): ActivityPageDto =
        json.decodeFromString(
            request("/v1/alarms/$alarmId/activity${before?.let { "?before=$it" }.orEmpty()}")
        )

    suspend fun listenForChanges(
        shouldContinue: () -> Boolean,
        onChange: suspend () -> Unit
    ) = withContext(Dispatchers.IO) {
        val connection = URI("$baseUrl/v1/events").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 0
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.setRequestProperty("Authorization", "Bearer ${identity.token}")
        connection.setRequestProperty("X-Jay-Device-ID", identity.id)
        val status = connection.responseCode
        if (status !in 200..299) {
            val response = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            throw SocialApiException(status, response)
        }
        try {
            connection.inputStream.bufferedReader().use { reader ->
                while (shouldContinue()) {
                    val line = reader.readLine() ?: break
                    if (line == "event: sync") onChange()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun request(
        path: String,
        method: String = "GET",
        body: String? = null,
        authenticated: Boolean = true
    ): String {
        val connection = URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/json")
        if (authenticated) {
            connection.setRequestProperty("Authorization", "Bearer ${identity.token}")
            connection.setRequestProperty("X-Jay-Device-ID", identity.id)
        }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.bufferedWriter().use { it.write(body) }
        }
        val status = connection.responseCode
        val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (status !in 200..299) throw SocialApiException(status, response)
        return response
    }
}

class SocialApiException(val status: Int, response: String) : Exception(
    runCatching {
        when (val detail = (Json.parseToJsonElement(response) as? JsonObject)?.get("detail")) {
            is JsonPrimitive -> detail.contentOrNull
            is JsonArray -> detail.mapNotNull {
                ((it as? JsonObject)?.get("msg") as? JsonPrimitive)?.contentOrNull
            }.joinToString("\n").ifBlank { null }
            else -> null
        }
    }.getOrNull() ?: response.ifBlank { "Request failed with status $status" }
)
