package com.bnyro.clock.social.data

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.social.domain.SyncClockState
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedTimerQueueClockTest {
    @Test
    fun equalCapturedMillisecondsUsePersistentOrderWithinThatMillisecond() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        try {
            val sync = SocialSynchronization(context, database)
            val captured = System.currentTimeMillis()
            val first = sync.saveOperation("group", "timer", "timer", "PUT", "/v1/timers/timer",
                JsonObject(mapOf("run_id" to JsonPrimitive("first"))), JsonObject(mapOf("run_id" to JsonPrimitive("first"))),
                operationId = "z-first", capturedSavedAt = captured)
            database.socialDao().resolveOperation(first.operationId, "confirmed")
            val second = sync.saveOperation("group", "timer", "timer", "PUT", "/v1/timers/timer",
                JsonObject(mapOf("run_id" to JsonPrimitive("second"))), JsonObject(mapOf("run_id" to JsonPrimitive("second"))),
                operationId = "a-second", capturedSavedAt = captured)
            val firstSaved = Instant.parse(Json.parseToJsonElement(first.payload!!).jsonObject.getValue("saved_at").jsonPrimitive.content)
            val secondSaved = Instant.parse(Json.parseToJsonElement(second.payload!!).jsonObject.getValue("saved_at").jsonPrimitive.content)
            assertEquals(firstSaved.plusNanos(1_000), secondSaved)
            assertTrue(secondSaved < firstSaved.plusMillis(1))
            assertEquals(first.effectiveSavedAt, second.effectiveSavedAt)
            assertEquals(captured, second.rawSavedAt)
        } finally {
            database.close()
        }
    }

    @Test
    fun aLaterRemoteClockObservationDoesNotElevateOlderCapturedCommands() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        try {
            val sync = SocialSynchronization(context, database)
            val captured = System.currentTimeMillis()
            database.socialDao().putClock(SyncClockState(serverMillis = captured, wallMillis = captured,
                elapsedMillis = SystemClock.elapsedRealtime(), bootCount = 0, lastSavedMillis = captured + 60_000))
            val first = sync.saveOperation("group", "timer", "timer", "PUT", "/v1/timers/timer", JsonObject(emptyMap()), JsonObject(emptyMap()),
                operationId = "first", capturedSavedAt = captured)
            val second = sync.saveOperation("group", "timer", "timer", "PUT", "/v1/timers/timer", JsonObject(emptyMap()), JsonObject(emptyMap()),
                operationId = "second", capturedSavedAt = captured)
            val saved = Instant.parse(Json.parseToJsonElement(second.payload!!).jsonObject.getValue("saved_at").jsonPrimitive.content)
            assertEquals(captured, first.effectiveSavedAt)
            assertEquals(captured, second.effectiveSavedAt)
            assertTrue(saved < Instant.ofEpochMilli(captured + 1))
            assertEquals(captured + 60_000, database.socialDao().getClock()!!.lastSavedMillis)
        } finally {
            database.close()
        }
    }

    @Test
    fun aTrulyOlderCapturedCommandKeepsItsEarlierTimestamp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        try {
            val sync = SocialSynchronization(context, database)
            val captured = System.currentTimeMillis()
            sync.saveOperation("group", "timer", "timer", "PUT", "/v1/timers/timer", JsonObject(emptyMap()), JsonObject(emptyMap()),
                operationId = "newer", capturedSavedAt = captured)
            val older = sync.saveOperation("group", "timer", "timer", "PUT", "/v1/timers/timer", JsonObject(emptyMap()), JsonObject(emptyMap()),
                operationId = "older", capturedSavedAt = captured - 1)
            assertEquals(Instant.ofEpochMilli(captured - 1).toString(), Json.parseToJsonElement(older.payload!!).jsonObject.getValue("saved_at").jsonPrimitive.content)
        } finally {
            database.close()
        }
    }
}
