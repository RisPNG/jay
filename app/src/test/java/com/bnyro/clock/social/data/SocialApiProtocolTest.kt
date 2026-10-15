package com.bnyro.clock.social.data

import com.bnyro.clock.social.domain.PendingOperation
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialApiProtocolTest {
    private data class Request(
        val path: String,
        val method: String,
        val protocol: String?,
        val authorization: String?,
        val identity: String?,
        val installation: String?,
        val accept: String?,
        val operationId: String?,
        val body: String
    )

    @Test
    fun registrationSynchronizationMutationsAndLiveEventsAdvertiseTheProtocol() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += Request(
                path, exchange.requestMethod, exchange.requestHeaders.getFirst("X-Jay-Protocol"),
                exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("X-Jay-Identity-ID"),
                exchange.requestHeaders.getFirst("X-Jay-Installation"), exchange.requestHeaders.getFirst("Accept"),
                exchange.requestHeaders.getFirst("Idempotency-Key"), exchange.requestBody.bufferedReader().use { it.readText() }
            )
            val body = when (path) {
                "/v1/sync" -> """{"scope_id":"identity","mode":"snapshot","from_revision":0,"through_revision":1,"next_cursor":"complete","has_more":false,"server_time":"${Instant.now()}","items":[]}"""
                "/v1/identity/capabilities" -> """{"advanced_group_timing":true}"""
                "/v1/events" -> "data: {\"scopes\":[\"group\"]}\n\n"
                else -> "{}"
            }
            exchange.responseHeaders.add("Content-Type", if (path == "/v1/events") "text/event-stream" else "application/json")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val api = SocialApi("http://127.0.0.1:${server.address.port}", DeviceIdentity("identity", "Test device", "test-token", "test-secret"), "installation")
            api.register()
            api.synchronizeScope("/v1/sync", null)
            assertTrue(api.deviceCapabilities().advancedGroupTiming)
            val operation = PendingOperation(
                operationId = "4ea76593-8579-4871-b770-8fbded455b03", scopeId = "group", kind = "timer", targetId = "timer",
                method = "DELETE", path = "/v1/timers/timer", payload = """{"membership_id":"member"}""",
                optimisticState = null, rawSavedAt = 1L, effectiveSavedAt = 1L
            )
            api.executeOperation(operation)
            var listening = true
            val changes = mutableListOf<Set<String>?>()
            api.listenForChanges({ listening }) {
                changes += it
                listening = false
            }

            assertEquals(5, requests.size)
            assertTrue(requests.all { it.protocol == "2" })
            val registration = requests.single { it.path == "/v1/identities/register" }
            assertEquals("POST", registration.method)
            assertNull(registration.authorization)
            assertNull(registration.identity)
            requests.filter { it !== registration }.forEach {
                assertEquals("Bearer test-token", it.authorization)
                assertEquals("identity", it.identity)
                assertEquals("installation", it.installation)
            }
            val mutation = requests.single { it.path == "/v1/timers/timer" }
            assertEquals("DELETE", mutation.method)
            assertEquals(operation.operationId, mutation.operationId)
            assertEquals(operation.payload, mutation.body)
            assertEquals("text/event-stream", requests.single { it.path == "/v1/events" }.accept)
            assertEquals(listOf(setOf("group")), changes)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun anOlderApiWithoutTheCapabilityKeepsLegacyBehavior() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.close()
            val bytes = """{"shared_sound_upload":false,"requires_play_entitlement":true,"expires_at":null}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val api = SocialApi("http://127.0.0.1:${server.address.port}", DeviceIdentity("identity", "Test device", "test-token", "test-secret"))

            assertFalse(api.deviceCapabilities().advancedGroupTiming)
        } finally {
            server.stop(0)
        }
    }
}
