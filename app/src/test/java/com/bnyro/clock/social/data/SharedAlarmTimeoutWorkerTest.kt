package com.bnyro.clock.social.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.WorkForegroundUpdater
import androidx.work.impl.utils.WorkProgressUpdater
import com.bnyro.clock.App
import com.bnyro.clock.AppContainer
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.social.domain.AlarmPermission
import com.bnyro.clock.social.domain.MemberRole
import com.bnyro.clock.social.domain.SharedAlarmLink
import com.bnyro.clock.social.domain.SharedSoundMode
import com.bnyro.clock.social.domain.SocialGroup
import com.bnyro.clock.util.Preferences
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = App::class)
class SharedAlarmTimeoutWorkerTest {
    private lateinit var context: App
    private lateinit var alarms: AppDatabase
    private lateinit var social: SocialDatabase
    private var localId = 0L
    private var deadline = 0L
    private var previousSnoozedAt = 0L
    private lateinit var occurrenceId: String

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        Preferences.instance.edit().clear().commit()
        alarms = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        context.container = AppContainer(context, alarms)
        social = SocialDatabase.getDatabase(context)
        withContext(Dispatchers.IO) { social.clearAllTables() }
        localId = context.container.alarmRepository.addAlarm(Alarm(
            time = 0L, enabled = false, endOccurrences = 1, missedRestartEnabled = true
        ))
        deadline = System.currentTimeMillis() - 1_000L
        previousSnoozedAt = deadline - 15 * 60_000L
        occurrenceId = (previousSnoozedAt - 5 * 60_000L).toString()
        social.socialDao().putGroups(listOf(SocialGroup(
            id = "group", name = "Family", alarmPermission = AlarmPermission.EVERYONE,
            notifyAlarmChanges = true, notifySnoozed = true, notifyDismissed = true,
            notifyIgnored = true, notifyMembership = true, notifyAdministrative = true,
            role = MemberRole.MEMBER, membershipId = "member"
        )))
        social.socialDao().putAlarmLink(SharedAlarmLink(
            "alarm", localId, "group", 1, SharedSoundMode.OFF, null, null, null, "save"
        ))
        Preferences.edit {
            putString("${SocialPreferences.alarmOccurrencePrefix}$localId", occurrenceId)
            putLong("${SocialPreferences.alarmDeadlinePrefix}$localId", deadline)
            putLong("${SocialPreferences.alarmSnoozedAtPrefix}$localId", previousSnoozedAt)
        }
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        WorkManagerImpl.getInstance(context).cancelUniqueWork("jay_social_sync_immediate").result.get()
        withContext(Dispatchers.IO) { social.clearAllTables() }
        alarms.close()
        Preferences.instance.edit().clear().commit()
        Unit
    }

    @Test
    fun aTimeoutProducerQueuesItsCanonicalOutcomeWithoutAdvancingTheLocalPhase() = runBlocking {
        val eventId = UUID.randomUUID().toString()

        assertEquals(ListenableWorker.Result.success(), timeoutWorker(eventId).doWork())

        val operation = requireNotNull(social.socialDao().getOperation(eventId))
        val payload = Json.parseToJsonElement(requireNotNull(operation.payload)).jsonObject
        assertEquals("outcome", operation.kind)
        assertEquals("snoozed", payload.getValue("kind").jsonPrimitive.content)
        assertEquals("timed_out", payload.getValue("reason").jsonPrimitive.content)
        assertEquals(occurrenceId, payload.getValue("occurrence_key").jsonPrimitive.content)
        assertEquals(Instant.ofEpochMilli(deadline).toString(), payload.getValue("occurred_at").jsonPrimitive.content)
        assertEquals(Instant.ofEpochMilli(deadline + 10 * 60_000L).toString(), payload.getValue("snoozed_until").jsonPrimitive.content)
        assertNull(operation.optimisticState)
        assertEquals(occurrenceId, Preferences.instance.getString("${SocialPreferences.alarmOccurrencePrefix}$localId", null))
        assertEquals(deadline, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}$localId", -1L))
        assertEquals(previousSnoozedAt, Preferences.instance.getLong("${SocialPreferences.alarmSnoozedAtPrefix}$localId", -1L))
        assertNull(context.container.alarmRepository.getAlarmById(localId)?.snoozedUntil)
    }

    @Test
    fun anOlderTimeoutCannotQueueAnOutcomeForTheNewManualSnoozePhase() = runBlocking {
        val manualSnoozedAt = deadline - 1_000L
        val nextRing = manualSnoozedAt + 10 * 60_000L
        context.container.alarmRepository.updateAlarm(requireNotNull(
            context.container.alarmRepository.getAlarmById(localId)
        ).copy(snoozedUntil = nextRing))
        Preferences.edit {
            putLong("${SocialPreferences.alarmDeadlinePrefix}$localId", nextRing + 5 * 60_000L)
            putLong("${SocialPreferences.alarmSnoozedAtPrefix}$localId", manualSnoozedAt)
        }

        assertEquals(ListenableWorker.Result.success(), timeoutWorker(UUID.randomUUID().toString()).doWork())

        assertTrue(social.socialDao().getPendingOperations().isEmpty())
        assertEquals(nextRing + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}$localId", -1L))
        assertEquals(manualSnoozedAt, Preferences.instance.getLong("${SocialPreferences.alarmSnoozedAtPrefix}$localId", -1L))
        assertEquals(nextRing, context.container.alarmRepository.getAlarmById(localId)?.snoozedUntil)
    }

    private fun timeoutWorker(eventId: String): SocialIgnoredAlarmWorker {
        val manager = WorkManagerImpl.getInstance(context)
        val configuration = manager.configuration
        val executor = manager.workTaskExecutor
        val parameters = WorkerParameters(
            UUID.randomUUID(), Data.Builder().putLong("alarm_id", localId)
                .putString("occurrence_id", occurrenceId).putString("event_id", eventId)
                .putLong("deadline_at", deadline).build(), emptyList(),
            WorkerParameters.RuntimeExtras(), 0, 0, configuration.executor,
            configuration.workerCoroutineContext, executor, configuration.workerFactory,
            WorkProgressUpdater(manager.workDatabase, executor),
            WorkForegroundUpdater(manager.workDatabase, manager.processor, executor)
        )
        return SocialIgnoredAlarmWorker(context, parameters)
    }
}
