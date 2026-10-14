package com.bnyro.clock.social.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.social.domain.AlarmActivityRequest
import com.bnyro.clock.social.domain.SyncedResource
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AutomaticAlarmTimeoutOperationTest {
    @Test
    fun aTimeoutAlreadyAdvancedByTheServerCompletesWithoutAUserChangeFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/") { exchange ->
            requests++
            val bytes = """{"code":"response_outdated","detail":"This timeout no longer belongs to the pending alarm phase"}""".toByteArray()
            exchange.requestBody.close()
            exchange.sendResponseHeaders(409, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val synchronization = SocialSynchronization(context, database)
            val deadline = Instant.now().minusSeconds(1)
            val eventId = UUID.randomUUID().toString()
            val canonical = SyncedResource("group", "occurrence", "current-phase", """{"snoozed_until":"${deadline.plusSeconds(600)}"}""")
            database.socialDao().putResources(listOf(canonical))
            val operation = synchronization.saveOperation("group", "outcome", eventId, "POST", "/v1/alarms/alarm/activity",
                Json.encodeToJsonElement(AlarmActivityRequest(eventId, 1, "snoozed", deadline.toString(),
                    "original-occurrence", "timed_out", UUID.randomUUID().toString(), deadline.plusSeconds(600).toString())).jsonObject,
                null, ordered = false, operationId = eventId)
            val base = "http://127.0.0.1:${server.address.port}"
            val api = SocialApi(base, DeviceIdentityStore.loadOrCreate(context, base))

            synchronization.flushOperations(api)

            val resolved = requireNotNull(database.socialDao().getOperation(eventId))
            assertEquals("confirmed", resolved.state)
            assertEquals("response_outdated", resolved.rejectionCode)
            assertEquals(operation.payload, resolved.payload)
            assertNull(resolved.response)
            assertNull(resolved.optimisticState)
            assertTrue(database.socialDao().getPendingOperations().isEmpty())
            assertTrue(database.socialDao().getProjectedOperations().isEmpty())
            assertTrue(database.socialDao().getRejectedOperationsStream().first().isEmpty())
            assertEquals(listOf(canonical), synchronization.projectedResources())

            database.socialDao().confirmAcceptedOperations()
            SocialSynchronization(context, database).flushOperations(api)
            assertEquals(1, requests)
            assertEquals(resolved, database.socialDao().getOperation(eventId))
        } finally {
            server.stop(0)
            database.close()
        }
    }

    @Test
    fun manualResponsesAndAutomaticAccessOrScheduleErrorsRemainReviewable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val codes = mapOf("manual" to "response_outdated", "schedule" to "revision_changed",
            "membership" to "membership_changed", "permission" to "editor_required",
            "different-kind" to "response_outdated")
        server.createContext("/") { exchange ->
            val name = exchange.requestURI.path.substringAfterLast('/')
            val bytes = """{"code":"${codes.getValue(name)}","detail":"The current state changed"}""".toByteArray()
            exchange.requestBody.close()
            exchange.sendResponseHeaders(if (name == "permission") 403 else 409, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val synchronization = SocialSynchronization(context, database)
            val operations = codes.map { (name, code) ->
                val eventId = UUID.randomUUID().toString()
                val payload = Json.encodeToJsonElement(AlarmActivityRequest(eventId, 1, "snoozed", Instant.now().toString(),
                    "original-occurrence", if (name == "manual") null else "timed_out", UUID.randomUUID().toString())).jsonObject
                synchronization.saveOperation("group", if (name == "different-kind") "timer" else "outcome",
                    eventId, "POST", "/$name", payload, null, ordered = false, operationId = eventId) to code
            }
            val base = "http://127.0.0.1:${server.address.port}"

            synchronization.flushOperations(SocialApi(base, DeviceIdentityStore.loadOrCreate(context, base)))

            assertTrue(database.socialDao().getPendingOperations().isEmpty())
            val rejected = database.socialDao().getRejectedOperationsStream().first().associateBy { it.operationId }
            assertEquals(operations.size, rejected.size)
            operations.forEach { (operation, code) ->
                assertEquals("rejected", rejected.getValue(operation.operationId).state)
                assertEquals(code, rejected.getValue(operation.operationId).rejectionCode)
                assertEquals(operation.payload, rejected.getValue(operation.operationId).payload)
            }
        } finally {
            server.stop(0)
            database.close()
        }
    }
}
