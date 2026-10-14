package com.bnyro.clock.social.data

import com.bnyro.clock.App
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.BroadcastReceiver
import androidx.compose.runtime.mutableStateOf
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.domain.model.TimerObject
import com.bnyro.clock.domain.model.WatchState
import com.bnyro.clock.util.services.TimerService
import com.bnyro.clock.social.domain.MembershipAccessDto
import com.bnyro.clock.social.domain.SharedTimerDto
import com.bnyro.clock.social.domain.SocialGroupDto
import com.bnyro.clock.social.domain.SyncedResource
import com.bnyro.clock.util.Preferences
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class SharedTimerOperationTest {
    private lateinit var social: SocialDatabase
    private lateinit var alarms: AppDatabase
    private lateinit var repository: SocialRepository
    private lateinit var context: Context
    private var expiry = 0L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext<Context>()
        Preferences.init(context)
        Preferences.instance.edit().clear().commit()
        social = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        alarms = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = SocialRepository(context, social, AlarmRepository(alarms.alarmsDao()))
        expiry = System.currentTimeMillis() + 30_000
        social.socialDao().putResources(listOf(
            SyncedResource("identity", "membership", "membership", Json.encodeToString(
                MembershipAccessDto("member", "group", "group", "member", true, true)
            )),
            SyncedResource("group", "group", "group", Json.encodeToString(
                SocialGroupDto("group", "Kitchen", "everyone", true, true, true, true)
            )),
            SyncedResource("group", "timer", "timer", Json.encodeToString(
                SharedTimerDto("timer", "group", "Tea", durationSeconds = 60, incrementSeconds = 15,
                    expiresAt = Instant.ofEpochMilli(expiry).toString(), runId = "run", timeoutMinutes = 7,
                    repeatEnabled = true, repeatCount = 3, repeatIndex = 1, soundMode = "off")
            ))
        ))
        repository.refreshSharedState()
        Unit
    }

    @After
    fun tearDown() {
        social.close()
        alarms.close()
        Preferences.instance.edit().clear().commit()
    }

    @Test
    fun countingAdjustmentQueuesTheOriginalDurationAndRemainingBudgetBeforeProjection() = runBlocking {
        repository.adjustSharedTimer("timer", "group", "member", "adjusted-run", 2, expiry + 15_000, "adjust", System.currentTimeMillis())
        val operation = social.socialDao().getOperation("adjust")!!
        val payload = Json.parseToJsonElement(operation.payload!!).jsonObject
        assertEquals("member", payload.getValue("membership_id").jsonPrimitive.content)
        assertEquals("adjusted-run", payload.getValue("run_id").jsonPrimitive.content)
        assertEquals("2", payload.getValue("repeat_index").jsonPrimitive.content)
        assertEquals("3", payload.getValue("repeat_count").jsonPrimitive.content)
        assertEquals("60", payload.getValue("duration_seconds").jsonPrimitive.content)
        assertEquals("7", payload.getValue("timeout_minutes").jsonPrimitive.content)
        assertTrue("saved_at" in payload)
        val projected = SocialSynchronization(context, social).projectedResources().single { it.kind == "timer" }
        assertEquals("adjusted-run", Json.parseToJsonElement(projected.representation).jsonObject.getValue("run_id").jsonPrimitive.content)
        repository.adjustSharedTimer("timer", "group", "member", "ignored-retry", 0, expiry, "adjust", System.currentTimeMillis())
        assertEquals(operation, social.socialDao().getOperation("adjust"))
    }

    @Test
    fun elapsedAnswerUsesAnOccurrenceOperationWithoutMutatingTheSeries() = runBlocking {
        val trigger = System.currentTimeMillis() - 1_000
        repository.dismissSharedTimer("timer", "group", "member", "run", 1, trigger, 7, "answer", System.currentTimeMillis())
        val operation = social.socialDao().getOperation("answer")!!
        assertEquals("timer_occurrence", operation.kind)
        assertEquals("POST", operation.method)
        assertEquals("/v1/timers/timer/occurrence", operation.path)
        val payload = Json.parseToJsonElement(operation.payload!!).jsonObject
        assertEquals(setOf("membership_id", "run_id", "repeat_index", "occurred_at"), payload.keys)
        assertEquals("member", payload.getValue("membership_id").jsonPrimitive.content)
        assertEquals("run", payload.getValue("run_id").jsonPrimitive.content)
        val projected = SocialSynchronization(context, social).projectedResources()
        assertEquals("run", Json.parseToJsonElement(projected.single { it.kind == "timer" }.representation).jsonObject.getValue("run_id").jsonPrimitive.content)
        assertEquals(1, projected.count { it.kind == "timer_occurrence" })
    }

    @Test
    fun stoppingAFutureMemberCopySuppressesOnlyThatIndexWithoutAnEarlyAnswer() = runBlocking {
        repository.dismissSharedTimer("timer", "group", "member", "run", 1, expiry, 7, "private-stop", System.currentTimeMillis())
        assertTrue(social.socialDao().getPendingOperations().isEmpty())
        val suppressed = social.socialDao().getDismissedTimers().single()
        assertEquals("member", suppressed.membershipId)
        assertEquals("run", suppressed.runId)
        assertEquals(1, suppressed.repeatIndex)
        assertEquals(1, SocialSynchronization(context, social).projectedResources().count { it.kind == "timer" })
    }

    @Test
    fun oldMembershipCommandsAreDiscardedInsteadOfRebound() = runBlocking {
        repository.dismissSharedTimer("timer", "group", "old-member", "run", 1, expiry - 60_000, 7, "old-answer", System.currentTimeMillis())
        repository.adjustSharedTimer("timer", "group", "old-member", "new-run", 1, expiry, "old-adjust", System.currentTimeMillis())
        repository.cancelSharedTimer("timer", "group", "old-member", "old-cancel")
        assertTrue(social.socialDao().getPendingOperations().isEmpty())
        assertTrue(social.socialDao().getDismissedTimers().isEmpty())
    }
    @Test
    fun consecutiveCountingCommandsAccumulateBeforeTheirNativeProjectionChanges() = runBlocking {
        val savedAt = System.currentTimeMillis()
        repository.adjustSharedTimer("timer", "group", "member", "first-adjust", 1, expiry + 15_000,
            "z-first", savedAt, sourceRunId = "run", adjustmentSeconds = 15)
        repository.adjustSharedTimer("timer", "group", "member", "second-adjust", 1, expiry + 15_000,
            "a-second", savedAt, sourceRunId = "run", adjustmentSeconds = 15)
        val first = social.socialDao().getOperation("z-first")!!
        val second = social.socialDao().getOperation("a-second")!!
        val projected = SocialSynchronization(context, social).projectedResources().single { it.kind == "timer" }
        val timer = Json.parseToJsonElement(projected.representation).jsonObject
        assertEquals(expiry + 30_000, Instant.parse(timer.getValue("expires_at").jsonPrimitive.content).toEpochMilli())
        assertEquals("second-adjust", timer.getValue("run_id").jsonPrimitive.content)
        assertEquals("1", timer.getValue("repeat_index").jsonPrimitive.content)
        assertEquals("3", timer.getValue("repeat_count").jsonPrimitive.content)
        assertEquals("60", timer.getValue("duration_seconds").jsonPrimitive.content)
        val firstSaved = Instant.parse(Json.parseToJsonElement(first.payload!!).jsonObject.getValue("saved_at").jsonPrimitive.content)
        val secondSaved = Instant.parse(Json.parseToJsonElement(second.payload!!).jsonObject.getValue("saved_at").jsonPrimitive.content)
        assertEquals(firstSaved.plusNanos(1_000), secondSaved)
        assertEquals(savedAt, first.rawSavedAt)
        assertEquals(savedAt, second.rawSavedAt)
    }

    @Test
    fun finishedAddAndResetStartFreshBudgetsWhileFollowingAddsUseThatQueuedRun() = runBlocking {
        val savedAt = System.currentTimeMillis()
        val restartedExpiry = savedAt + 15_000
        repository.adjustSharedTimer("timer", "group", "member", "finished-add", 0, restartedExpiry,
            "finished", savedAt, sourceRunId = "run", adjustmentSeconds = 15, sourceRepeatIndex = 1, sourceFinished = true)
        repository.adjustSharedTimer("timer", "group", "member", "following-add", 0, savedAt + 15_001,
            "following", savedAt + 1, sourceRunId = "run", adjustmentSeconds = 15, sourceRepeatIndex = 1, sourceFinished = true)
        val adjusted = Json.parseToJsonElement(SocialSynchronization(context, social).projectedResources().single { it.kind == "timer" }.representation).jsonObject
        assertEquals(restartedExpiry + 15_000, Instant.parse(adjusted.getValue("expires_at").jsonPrimitive.content).toEpochMilli())
        assertEquals("0", adjusted.getValue("repeat_index").jsonPrimitive.content)
        repository.adjustSharedTimer("timer", "group", "member", "reset", 0, savedAt + 60_000,
            "reset", savedAt + 2, sourceRunId = "run", sourceRepeatIndex = 1, sourceFinished = true)
        val reset = Json.parseToJsonElement(SocialSynchronization(context, social).projectedResources().single { it.kind == "timer" }.representation).jsonObject
        assertEquals(savedAt + 60_000, Instant.parse(reset.getValue("expires_at").jsonPrimitive.content).toEpochMilli())
        assertEquals("0", reset.getValue("repeat_index").jsonPrimitive.content)
        assertEquals("3", reset.getValue("repeat_count").jsonPrimitive.content)
        assertEquals("60", reset.getValue("duration_seconds").jsonPrimitive.content)
    }

    @Test
    fun twoFinishedServicePressesCaptureAdditiveCommandsBeforeEitherPersists() = runBlocking {
        val controller = Robolectric.buildService(TimerService::class.java).create()
        val service = controller.get()
        val timerId = "timer"
        val source = TimerObject(id = 42, currentPosition = mutableStateOf(0), initialPosition = mutableStateOf(60_000),
            state = mutableStateOf(WatchState.PAUSED), incrementSeconds = 15, repeatEnabled = true, repeatCount = 3,
            repeatIndex = 1, repeatDurationMillis = 60_000, expiresAt = expiry - 60_000,
            sharedTimerId = timerId, sharedGroupId = "group", sharedMembershipId = "member", sharedRunId = "run")
        val server = SocialRepository.DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, server).id
        val name = "timer:$server:$identity:member:$timerId"
        val manager = WorkManager.getInstance(context) as WorkManagerImpl
        try {
            manager.cancelUniqueWork(name).result.get(5, TimeUnit.SECONDS)
            service.timerObjects.add(source)
            val receiver = TimerService::class.java.getDeclaredField("receiver").apply { isAccessible = true }.get(service) as BroadcastReceiver
            receiver.onReceive(context, TimerService.updateStateIntent(TimerService.ACTION_ADD_TIME, source.id))
            receiver.onReceive(context, TimerService.updateStateIntent(TimerService.ACTION_ADD_TIME, source.id))
            assertEquals(0, source.currentPosition.value)
            assertEquals("run", source.sharedRunId)
            assertTrue(social.socialDao().getPendingOperations().isEmpty())
            val work = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).filter { !it.state.isFinished }
            assertEquals(2, work.size)
            val inputs = withContext(Dispatchers.IO) {
                work.map { manager.workDatabase.workSpecDao().getWorkSpec(it.id.toString())!! }
                    .sortedBy { manager.workDatabase.dependencyDao().getPrerequisites(it.id).size }
                    .map { it.input }
            }
            assertEquals(listOf(15, 15), inputs.map { it.getInt("adjustment_seconds", 0) })
            assertEquals(listOf(true, true), inputs.map { it.getBoolean("source_finished", false) })
            assertEquals(listOf(1, 1), inputs.map { it.getInt("source_repeat_index", -1) })
            assertEquals(listOf(0, 0), inputs.map { it.getInt("repeat_index", -1) })
            for (input in inputs) {
                repository.adjustSharedTimer(timerId, "group", "member", input.getString("run_id")!!,
                    input.getInt("repeat_index", 0), input.getLong("expires_at", 0),
                    input.getString("operation_id")!!, input.getLong("saved_at", 0),
                    input.getString("source_run_id"), input.getInt("adjustment_seconds", 0),
                    input.getInt("source_repeat_index", 0), input.getBoolean("source_finished", false))
            }
            val timer = Json.parseToJsonElement(SocialSynchronization(context, social).projectedResources().single { it.kind == "timer" }.representation).jsonObject
            assertEquals(inputs.first().getLong("expires_at", 0) + 15_000,
                Instant.parse(timer.getValue("expires_at").jsonPrimitive.content).toEpochMilli())
            assertEquals("0", timer.getValue("repeat_index").jsonPrimitive.content)
            assertEquals("3", timer.getValue("repeat_count").jsonPrimitive.content)
            assertEquals("60", timer.getValue("duration_seconds").jsonPrimitive.content)
            for (operation in social.socialDao().getPendingOperations().filter { it.kind == "timer" }) {
                assertFalse("command_source" in Json.parseToJsonElement(operation.payload!!).jsonObject)
            }
        } finally {
            manager.cancelUniqueWork(name).result.get(5, TimeUnit.SECONDS)
            controller.destroy()
        }
    }

    @Test
    fun anUnrelatedRemoteGenerationDoesNotCountAsAnEarlierFinishedAdd() = runBlocking {
        val savedAt = System.currentTimeMillis()
        repository.adjustSharedTimer("timer", "group", "member", "local-add", 0, savedAt + 15_000,
            "local", savedAt, sourceRunId = "run", adjustmentSeconds = 15, sourceRepeatIndex = 1, sourceFinished = true)
        social.socialDao().resolveOperation("local", "confirmed")
        val remoteExpiry = savedAt + 600_000
        social.socialDao().putResources(listOf(SyncedResource("group", "timer", "timer", Json.encodeToString(
            SharedTimerDto("timer", "group", "Remote", durationSeconds = 60, incrementSeconds = 15,
                expiresAt = Instant.ofEpochMilli(remoteExpiry).toString(), runId = "unrelated-remote", timeoutMinutes = 7,
                repeatEnabled = true, repeatCount = 3, repeatIndex = 0, soundMode = "off")
        ))))
        repository.adjustSharedTimer("timer", "group", "member", "fresh-add", 0, savedAt + 15_001,
            "fresh", savedAt + 1, sourceRunId = "run", adjustmentSeconds = 15, sourceRepeatIndex = 1, sourceFinished = true)
        val timer = Json.parseToJsonElement(SocialSynchronization(context, social).projectedResources().single { it.kind == "timer" }.representation).jsonObject
        assertEquals(savedAt + 15_001, Instant.parse(timer.getValue("expires_at").jsonPrimitive.content).toEpochMilli())
        assertEquals("0", timer.getValue("repeat_index").jsonPrimitive.content)
    }

    @Test
    fun rejectedFinishedAddsDoNotExtendTheirRejectedGeneration() = runBlocking {
        val savedAt = System.currentTimeMillis()
        repository.adjustSharedTimer("timer", "group", "member", "rejected-run", 0, savedAt + 15_000,
            "rejected", savedAt, sourceRunId = "run", adjustmentSeconds = 15, sourceRepeatIndex = 1, sourceFinished = true)
        social.socialDao().resolveOperation("rejected", "rejected", "stale_update")
        repository.adjustSharedTimer("timer", "group", "member", "fresh-run", 0, savedAt + 15_001,
            "after-rejection", savedAt + 1, sourceRunId = "run", adjustmentSeconds = 15, sourceRepeatIndex = 1, sourceFinished = true)
        val timer = Json.parseToJsonElement(SocialSynchronization(context, social).projectedResources().single { it.kind == "timer" }.representation).jsonObject
        assertEquals(savedAt + 15_001, Instant.parse(timer.getValue("expires_at").jsonPrimitive.content).toEpochMilli())
    }

    @Test
    fun retryAfterAForegroundStartFailureCompletesTheSameDurableCommand() = runBlocking {
        var reject = true
        var starts = 0
        val rejecting = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): android.content.ComponentName? {
                starts++
                if (reject) {
                    reject = false
                    throw IllegalStateException("Test foreground start rejection")
                }
                return super.startForegroundService(service)
            }
        }
        val retrying = SocialRepository(rejecting, social, AlarmRepository(alarms.alarmsDao()))
        val savedAt = System.currentTimeMillis()
        val first = runCatching {
            retrying.adjustSharedTimer("timer", "group", "member", "retry-run", 1, expiry + 15_000,
                "retry-command", savedAt, sourceRunId = "run", adjustmentSeconds = 15)
        }
        assertTrue(first.exceptionOrNull() is IllegalStateException)
        val persisted = social.socialDao().getOperation("retry-command")!!
        val clock = social.socialDao().getClock()
        val manager = WorkManager.getInstance(context)
        withTimeout(5_000) {
            while (manager.getWorkInfosForUniqueWork("jay_social_sync_immediate").get(5, TimeUnit.SECONDS).none { !it.state.isFinished }) delay(10)
        }
        retrying.adjustSharedTimer("timer", "group", "member", "retry-run", 1, expiry + 15_000,
            "retry-command", savedAt, sourceRunId = "run", adjustmentSeconds = 15)
        assertEquals(2, starts)
        assertEquals(persisted, social.socialDao().getOperation("retry-command"))
        assertEquals(clock, social.socialDao().getClock())
        assertEquals(1, social.socialDao().getPendingOperations().count { it.kind == "timer" })
        val projected = Json.parseToJsonElement(SocialSynchronization(context, social).projectedResources().single { it.kind == "timer" }.representation).jsonObject
        assertEquals("retry-run", projected.getValue("run_id").jsonPrimitive.content)
        assertEquals(expiry + 15_000, Instant.parse(projected.getValue("expires_at").jsonPrimitive.content).toEpochMilli())
        assertEquals(persisted.payload, social.socialDao().getOperation("retry-command")!!.payload)
    }

}
