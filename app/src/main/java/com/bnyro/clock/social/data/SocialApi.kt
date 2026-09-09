package com.bnyro.clock.social.data

import com.bnyro.clock.social.domain.DeviceRegistration
import com.bnyro.clock.social.domain.ActivityPageDto
import com.bnyro.clock.social.domain.DeviceCapabilities
import com.bnyro.clock.social.domain.PlayEntitlementVerification
import com.bnyro.clock.social.domain.SharedSoundDownloadResponse
import com.bnyro.clock.social.domain.SharedSoundUploadResponse
import com.bnyro.clock.social.domain.PendingOperation
import com.bnyro.clock.social.domain.ScopeSyncPage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.HttpURLConnection
import java.net.URI
import java.time.ZoneId
import java.io.File

class SocialApi(
    serverUrl: String,
    private val identity: DeviceIdentity
) {
    private val baseUrl = URI(serverUrl).normalize().toString().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun synchronizeScope(route: String, cursor: String?): ScopeSyncPage = json.decodeFromString(
        request(route + (cursor?.let { "?cursor=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}" } ?: ""))
    )

    fun executeOperation(operation: PendingOperation): String = request(
        operation.path, operation.method, operation.payload, operationId = operation.operationId
    )

    fun register() {
        request(
            "/v1/identities/register",
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

    fun deviceCapabilities(): DeviceCapabilities = json.decodeFromString(
        request("/v1/identity/capabilities")
    )

    fun updatePlayEntitlement(integrityToken: String): DeviceCapabilities =
        json.decodeFromString(
            request(
                "/v1/identity/play-entitlement",
                "POST",
                json.encodeToString(PlayEntitlementVerification(integrityToken)),
                operationId = java.util.UUID.randomUUID().toString()
            )
        )

    fun getSoundUpload(soundId: String): SharedSoundUploadResponse = json.decodeFromString(
        request("/v1/sounds/$soundId/upload")
    )

    fun getSound(soundId: String): com.bnyro.clock.social.domain.SharedSoundDto = json.decodeFromString(
        request("/v1/sounds/$soundId")
    )

    suspend fun uploadSound(
        upload: SharedSoundUploadResponse,
        file: File,
        onProgress: (Long) -> Unit = {}
    ) {
        val connection = URI(upload.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(file.length())
            upload.headers.forEach(connection::setRequestProperty)
            var uploaded = 0L
            connection.outputStream.use { output ->
                file.inputStream().use { input ->
                    val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(bytes)
                        if (count < 0) break
                        output.write(bytes, 0, count)
                        uploaded += count
                        onProgress(uploaded)
                    }
                }
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw SocialApiException(status, response)
        } finally {
            connection.disconnect()
        }
    }

    fun getSoundDownload(soundId: String): SharedSoundDownloadResponse =
        json.decodeFromString(request("/v1/sounds/$soundId/download"))

    fun downloadSound(download: SharedSoundDownloadResponse, file: File) {
        val connection = URI(download.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
            val status = connection.responseCode
            if (status !in 200..299) {
                val response = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw SocialApiException(status, response)
            }
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        if (android.os.SystemClock.elapsedRealtime() > deadline) throw java.net.SocketTimeoutException("Sound download timed out")
                        val count = input.read(bytes)
                        if (count < 0) break
                        total += count
                        check(total <= download.byteLength && total <= 33_554_432) { "Sound download exceeds its declared size" }
                        output.write(bytes, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun getGroupActivity(groupId: String, before: String?): ActivityPageDto =
        json.decodeFromString(
            request("/v1/groups/$groupId/activity${before?.let { "?before=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}" }.orEmpty()}")
        )

    fun getAlarmActivity(alarmId: String, before: String?): ActivityPageDto =
        json.decodeFromString(
            request("/v1/alarms/$alarmId/activity${before?.let { "?before=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}" }.orEmpty()}")
        )

    suspend fun listenForChanges(
        shouldContinue: () -> Boolean,
        onChange: suspend (Set<String>?) -> Unit
    ) = withContext(Dispatchers.IO) {
        val connection = URI("$baseUrl/v1/events").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 25_000
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.setRequestProperty("Authorization", "Bearer ${identity.token}")
        connection.setRequestProperty("X-Jay-Identity-ID", identity.id)
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val response = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw SocialApiException(status, response)
            }
            connection.inputStream.bufferedReader().use { reader ->
                while (shouldContinue()) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    currentCoroutineContext().ensureActive()
                    if (line.startsWith("data: ")) {
                        val hint = Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                        onChange(if (hint["all"]?.jsonPrimitive?.booleanOrNull == true) null
                            else hint["scopes"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty())
                    }
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
        authenticated: Boolean = true,
        operationId: String? = null
    ): String {
        val connection = URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            operationId?.let { connection.setRequestProperty("Idempotency-Key", it) }
            if (authenticated) {
                connection.setRequestProperty("Authorization", "Bearer ${identity.token}")
                connection.setRequestProperty("X-Jay-Identity-ID", identity.id)
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw SocialApiException(status, response)
            return response
        } finally {
            connection.disconnect()
        }
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
) {
    val code: String = runCatching {
        ((Json.parseToJsonElement(response) as? JsonObject)?.get("code") as? JsonPrimitive)?.content
    }.getOrNull() ?: "request_failed"
}
