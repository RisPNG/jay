package com.bnyro.clock.social.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.social.domain.SyncedResource
import com.bnyro.clock.social.domain.SyncScopeState
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SocialSynchronizationTest {
    @Test
    fun rejectedFutureClockDoesNotPoisonSubsequentSaves() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val mutation = exchange.requestMethod == "PUT"
            val body = if (mutation) """{"code":"clock_invalid","detail":"Future clock"}""" else
                """{"scope_id":"group","mode":"snapshot","from_revision":0,"through_revision":1,"next_cursor":"complete","has_more":false,"server_time":"${Instant.now()}","items":[]}"""
            exchange.requestBody.close()
            exchange.sendResponseHeaders(if (mutation) 409 else 200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val synchronization = SocialSynchronization(context, database)
            val future = System.currentTimeMillis() + 86_400_000
            val old = synchronization.saveOperation("group", "timer", "timer", "PUT", "/timer", JsonObject(emptyMap()), null, capturedSavedAt = future)
            val base = "http://127.0.0.1:${server.address.port}"
            val api = SocialApi(base, DeviceIdentityStore.loadOrCreate(context, base))
            synchronization.flushOperations(api)
            synchronization.synchronizeScope(api, "/sync")
            val next = synchronization.saveOperation("group", "timer", "timer", "PUT", "/timer", JsonObject(emptyMap()), null)
            assertTrue(next.effectiveSavedAt < System.currentTimeMillis() + 1000)
            assertEquals(old.payload, database.socialDao().getOperation(old.operationId)?.payload)
            assertEquals("clock_invalid", database.socialDao().getOperation(old.operationId)?.rejectionCode)
        } finally {
            server.stop(0)
            database.close()
        }
    }

    @Test
    fun activityFromAnInterruptedPageRemainsAvailableAfterRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        var failFinal = true
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val final = exchange.requestURI.query == "cursor=page-two"
            val body = if (final && failFinal) "{}" else {
                val items = if (final) "[]" else """[{"kind":"activity","key":"event","action":"upsert","revision":2,"ordinal":0,"data":{"id":"event"}}]"""
                """{"scope_id":"group","mode":"delta","from_revision":1,"through_revision":2,"next_cursor":"${if (final) "complete" else "page-two"}","has_more":${!final},"server_time":"${Instant.now()}","items":$items}"""
            }
            exchange.requestBody.close()
            exchange.sendResponseHeaders(if (final && failFinal) 503 else 200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            database.socialDao().putScope(SyncScopeState("/sync", "group", "old-cursor"))
            val base = "http://127.0.0.1:${server.address.port}"
            val api = SocialApi(base, DeviceIdentityStore.loadOrCreate(context, base))
            assertTrue(runCatching { SocialSynchronization(context, database).synchronizeScope(api, "/sync") }.isFailure)
            assertTrue(database.socialDao().getPendingActivity().isEmpty())
            failFinal = false
            val recovered = SocialSynchronization(context, database).synchronizeScope(api, "/sync")
            assertEquals("event", recovered.single().key)
            assertEquals("event", database.socialDao().getPendingActivity().single().key)
            assertTrue(database.socialDao().getResources().isEmpty())
        } finally {
            server.stop(0)
            database.close()
        }
    }

    @Test
    fun delayedTimerActionKeepsItsOriginalSaveTimeAndResultingExpiry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        try {
            val synchronization = SocialSynchronization(context, database)
            val captured = System.currentTimeMillis() - 60_000
            val payload = JsonObject(mapOf("expires_at" to JsonPrimitive("2026-09-09T12:01:00Z")))
            val operation = synchronization.saveOperation("group", "timer", "timer", "PUT", "/timer", payload, null, capturedSavedAt = captured)
            assertEquals(captured, operation.rawSavedAt)
            assertEquals(captured, operation.effectiveSavedAt)
            val retry = synchronization.saveOperation("group", "timer", "timer", "PUT", "/timer", JsonObject(emptyMap()), null, operationId = operation.operationId)
            assertEquals(operation.payload, retry.payload)
            assertTrue(requireNotNull(retry.payload).contains("12:01:00Z"))
        } finally {
            database.close()
        }
    }

    @Test
    fun retriesKeepTheOriginalKeyAndSavedPayload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        val requests = mutableListOf<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestHeaders.getFirst("Idempotency-Key") to exchange.requestBody.bufferedReader().readText()
            val bytes = "{}".toByteArray()
            exchange.sendResponseHeaders(if (requests.size == 1) 503 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val synchronization = SocialSynchronization(context, database)
            val operation = synchronization.saveOperation("group", "timer", "timer", "PUT", "/v1/timers/timer",
                JsonObject(mapOf("expires_at" to JsonPrimitive("2026-09-09T12:00:00Z"))), null)
            val identity = DeviceIdentityStore.loadOrCreate(context, "http://127.0.0.1:${server.address.port}")
            val api = SocialApi("http://127.0.0.1:${server.address.port}", identity)
            assertTrue(runCatching { synchronization.flushOperations(api) }.isFailure)
            assertEquals(operation.payload, database.socialDao().getPendingOperations().single().payload)
            SocialSynchronization(context, database).flushOperations(api)
            assertEquals(requests[0], requests[1])
            assertEquals(operation.operationId, requests[1].first)
            assertTrue(database.socialDao().getPendingOperations().isEmpty())
        } finally {
            server.stop(0)
            database.close()
        }
    }

    @Test
    fun interruptedSnapshotKeepsTheOldStateUntilItsFinalPage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        var failFinal = true
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val final = exchange.requestURI.query == "cursor=page-two"
            val body = if (final && failFinal) "{}" else {
                val key = if (final) "second" else "first"
                """{"scope_id":"group","mode":"snapshot","from_revision":0,"through_revision":2,"next_cursor":"${if (final) "complete" else "page-two"}","has_more":${!final},"server_time":"${Instant.now()}","items":[{"kind":"timer","key":"$key","action":"upsert","revision":2,"ordinal":0,"data":{"id":"$key"}}]}"""
            }
            exchange.requestBody.close()
            exchange.sendResponseHeaders(if (final && failFinal) 503 else 200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            database.socialDao().putResources(listOf(SyncedResource("group", "timer", "old", "{}")))
            database.socialDao().putScope(SyncScopeState("/sync", "group", "old-cursor"))
            val base = "http://127.0.0.1:${server.address.port}"
            val api = SocialApi(base, DeviceIdentityStore.loadOrCreate(context, base))
            assertTrue(runCatching { SocialSynchronization(context, database).synchronizeScope(api, "/sync") }.isFailure)
            assertEquals(listOf("old"), database.socialDao().getResources().map { it.key })
            assertEquals("old-cursor", database.socialDao().getScope("/sync")?.cursor)
            failFinal = false
            SocialSynchronization(context, database).synchronizeScope(api, "/sync")
            assertEquals(setOf("first", "second"), database.socialDao().getResources().map { it.key }.toSet())
            assertEquals("complete", database.socialDao().getScope("/sync")?.cursor)
            assertTrue(database.socialDao().getStagedResources("group").isEmpty())
        } finally {
            server.stop(0)
            database.close()
        }
    }

    @Test
    fun rejectedOperationsLoseTheirOptimisticEffectButRemainReviewable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        try {
            val synchronization = SocialSynchronization(context, database)
            database.socialDao().putResources(listOf(SyncedResource("group", "timer", "timer", "{\"label\":\"Latest\"}")))
            val first = synchronization.saveOperation("group", "timer", "timer", "PUT", "/timer", JsonObject(emptyMap()), JsonObject(mapOf("label" to JsonPrimitive("Offline"))))
            val second = synchronization.saveOperation("group", "timer", "other", "PUT", "/other", JsonObject(emptyMap()), null)
            assertTrue(second.effectiveSavedAt > first.effectiveSavedAt)
            assertTrue(synchronization.projectedResources().single().representation.contains("Offline"))
            database.socialDao().resolveOperation(first.operationId, "rejected", "superseded")
            assertFalse(synchronization.projectedResources().single().representation.contains("Offline"))
            assertEquals("superseded", database.socialDao().getOperation(first.operationId)?.rejectionCode)
        } finally {
            database.close()
        }
    }
}
