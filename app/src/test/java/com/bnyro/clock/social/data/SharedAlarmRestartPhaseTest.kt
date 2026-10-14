package com.bnyro.clock.social.data

import android.app.Service
import android.content.Intent
import android.os.UserManager
import android.os.Looper
import org.json.JSONObject
import androidx.room.Room
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.App
import com.bnyro.clock.AppContainer
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.social.domain.MembershipAccessDto
import com.bnyro.clock.social.domain.SharedAlarmDto
import com.bnyro.clock.social.domain.SharedAlarmLink
import com.bnyro.clock.social.domain.SharedSoundMode
import com.bnyro.clock.social.domain.SocialGroupDto
import com.bnyro.clock.social.domain.SocialOccurrenceDto
import com.bnyro.clock.social.domain.SyncedResource
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.AlarmService
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = App::class)
class SharedAlarmRestartPhaseTest {
    private lateinit var context: App
    private lateinit var social: SocialDatabase
    private lateinit var alarms: AppDatabase
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var occurrenceId: String
    private var localId = 0L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        Preferences.init(context)
        Preferences.instance.edit().clear().commit()
        social = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        alarms = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        context.container = AppContainer(context, alarms)
        alarmRepository = AlarmRepository(alarms.alarmsDao())
        localId = alarmRepository.addAlarm(Alarm(time = 0L, enabled = false, endOccurrences = 1, missedRestartEnabled = true, soundEnabled = false, vibrate = false))
        occurrenceId = (System.currentTimeMillis() - 60_000L).toString()
        WorkManager.getInstance(context).cancelUniqueWork("jay_ignored_alarm_$localId").result.get()
        Preferences.edit { putString("${SocialPreferences.alarmOccurrencePrefix}$localId", occurrenceId) }
        social.socialDao().putAlarmLink(SharedAlarmLink("alarm", localId, "group", 1, SharedSoundMode.OFF, null, null, null, "save"))
        social.socialDao().putResources(listOf(
            SyncedResource("identity", "membership", "membership", Json.encodeToString(MembershipAccessDto("membership", "group", "group", "member", true, true))),
            SyncedResource("group", "group", "group", Json.encodeToString(SocialGroupDto("group", "Family", "everyone", true, true, true, true, sharedAnswers = true))),
            SyncedResource("group", "alarm", "alarm", Json.encodeToString(SharedAlarmDto(
                id = "alarm", groupId = "group", revision = 1, saveId = "save", time = 0L, label = null,
                enabled = true, days = listOf(0, 1, 2, 3, 4, 5, 6), vibrate = false,
                startDate = LocalDate.now().minusDays(1).toString(), repeatInterval = 1, repeatUnit = "DAY",
                repeatAnchor = "DAY_OF_MONTH", repeatDurationUnit = "DAY", endOccurrences = 1,
                snoozeEnabled = true, snoozeMinutes = 10, missedRestartEnabled = true,
                vibrationPattern = listOf(0, 1000), vibrationPatternName = "Default", soundMode = "off"
            )))
        ))
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelUniqueWork("jay_ignored_alarm_$localId").result.get()
        social.close()
        alarms.close()
        Preferences.instance.edit().clear().commit()
    }

    @Test
    fun aLegacyOriginalPhaseInstallsTheItemDeadlineAndReplacesItsOldWorker() = runBlocking {
        val trigger = System.currentTimeMillis() + 60_000L
        occurrenceId = trigger.toString()
        Preferences.edit { putString("${SocialPreferences.alarmOccurrencePrefix}$localId", occurrenceId) }
        val workManager = WorkManager.getInstance(context)
        val legacyWork = OneTimeWorkRequestBuilder<SocialIgnoredAlarmWorker>()
            .setInputData(Data.Builder().putLong("alarm_id", localId)
                .putString("occurrence_id", occurrenceId).putString("event_id", "legacy")
                .putLong("deadline_at", trigger + 10 * 60_000L).build())
            .setInitialDelay(11, java.util.concurrent.TimeUnit.MINUTES).build()
        workManager.enqueueUniqueWork("jay_ignored_alarm_$localId", ExistingWorkPolicy.REPLACE, legacyWork).result.get()
        social.socialDao().putResources(listOf(SyncedResource("group", "occurrence", "occurrence", Json.encodeToString(SocialOccurrenceDto(
            "alarm", occurrenceId, "pending", 1,
            triggerAt = Instant.ofEpochMilli(trigger).toString(), deadlineAt = Instant.ofEpochMilli(trigger + 5 * 60_000L).toString()
        )))))

        SocialRepository(context, social, alarmRepository).refreshSharedState()

        assertEquals(trigger + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}$localId", -1L))
        assertNull(alarmRepository.getAlarmById(localId)?.snoozedUntil)
        assertFalse(shadowOf(context).broadcastIntents.any { it.action == AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION })
        val work = workManager.getWorkInfosForUniqueWork("jay_ignored_alarm_$localId").get().single { !it.state.isFinished }
        assertTrue(work.id != legacyWork.id)
        val input = withContext(Dispatchers.IO) {
            WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(work.id.toString())!!.input
        }
        assertEquals(trigger + 5 * 60_000L, input.getLong("deadline_at", -1L))
        assertEquals(occurrenceId, input.getString("occurrence_id"))
    }

    @Test
    fun anUnchangedLegacyManualSnoozeInstallsItsPhaseWithoutRearming() = runBlocking {
        val ring = System.currentTimeMillis() + 15 * 60_000L
        alarmRepository.updateAlarm(requireNotNull(alarmRepository.getAlarmById(localId)).copy(snoozedUntil = ring))
        val workManager = WorkManager.getInstance(context)
        val legacyWork = OneTimeWorkRequestBuilder<SocialIgnoredAlarmWorker>()
            .setInputData(Data.Builder().putLong("alarm_id", localId)
                .putString("occurrence_id", occurrenceId).putString("event_id", "legacy")
                .putLong("deadline_at", ring + 10 * 60_000L).build())
            .setInitialDelay(25, java.util.concurrent.TimeUnit.MINUTES).build()
        workManager.enqueueUniqueWork("jay_ignored_alarm_$localId", ExistingWorkPolicy.REPLACE, legacyWork).result.get()
        social.socialDao().putResources(listOf(SyncedResource("group", "occurrence", "occurrence", Json.encodeToString(SocialOccurrenceDto(
            "alarm", occurrenceId, "pending", 1, Instant.ofEpochMilli(ring).toString(),
            triggerAt = Instant.ofEpochMilli(occurrenceId.toLong()).toString(), deadlineAt = Instant.ofEpochMilli(ring + 5 * 60_000L).toString()
        )))))

        SocialRepository(context, social, alarmRepository).refreshSharedState()

        assertEquals(ring, alarmRepository.getAlarmById(localId)?.snoozedUntil)
        assertEquals(ring + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}$localId", -1L))
        assertFalse(shadowOf(context).broadcastIntents.any { it.action == AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION })
        val work = workManager.getWorkInfosForUniqueWork("jay_ignored_alarm_$localId").get().single { !it.state.isFinished }
        assertTrue(work.id != legacyWork.id)
        val input = withContext(Dispatchers.IO) {
            WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(work.id.toString())!!.input
        }
        assertEquals(ring + 5 * 60_000L, input.getLong("deadline_at", -1L))
    }

    @Test
    fun anActiveSnoozedPhaseRefreshesItsPolicyWithoutRestartingTheRing() = runBlocking {
        val ring = System.currentTimeMillis() - 60_000L
        Preferences.edit { putLong("${SocialPreferences.alarmDeadlinePrefix}$localId", ring + 5 * 60_000L) }
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(context, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, localId).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ring), 0, 1)
            shadowOf(context).nextStartedActivity
            Preferences.edit { putLong("${SocialPreferences.alarmDeadlinePrefix}$localId", ring + 10 * 60_000L) }
            social.socialDao().putResources(listOf(SyncedResource("group", "occurrence", "occurrence", Json.encodeToString(SocialOccurrenceDto(
                "alarm", occurrenceId, "pending", 1, Instant.ofEpochMilli(ring).toString(),
                deadlineAt = Instant.ofEpochMilli(ring + 5 * 60_000L).toString()
            )))))

            SocialRepository(context, social, alarmRepository).refreshSharedState()
            shadowOf(Looper.getMainLooper()).idle()

            assertNull(alarmRepository.getAlarmById(localId)?.snoozedUntil)
            assertEquals(ring + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}$localId", -1L))
            assertFalse(shadowOf(context).broadcastIntents.any { it.action == AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION })
            assertNull(shadowOf(context).nextStartedActivity)
            val playing = AlarmService::class.java.getDeclaredField("isPlaying").apply { isAccessible = true }
            assertTrue(playing.getBoolean(controller.get()))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun refreshingALegacyPhasePreservesAnotherAlarmThatIsRinging() = runBlocking {
        val other = Alarm(time = 0L, enabled = true, soundEnabled = false, vibrate = false)
        other.id = alarmRepository.addAlarm(other)
        val trigger = System.currentTimeMillis()
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(context, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, other.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, trigger), 0, 1)
            shadowOf(context).nextStartedActivity
            social.socialDao().putResources(listOf(SyncedResource("group", "occurrence", "occurrence", Json.encodeToString(SocialOccurrenceDto(
                "alarm", occurrenceId, "pending", 1, triggerAt = Instant.ofEpochMilli(trigger + 60_000L).toString(),
                deadlineAt = Instant.ofEpochMilli(trigger + 6 * 60_000L).toString()
            )))))

            SocialRepository(context, social, alarmRepository).refreshSharedState()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(trigger + 6 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}$localId", -1L))
            val current = AlarmService::class.java.getDeclaredField("currentAlarm").apply { isAccessible = true }
            assertEquals(other.id, (current.get(controller.get()) as Alarm).id)
            val playing = AlarmService::class.java.getDeclaredField("isPlaying").apply { isAccessible = true }
            assertTrue(playing.getBoolean(controller.get()))
            assertFalse(shadowOf(context).broadcastIntents.any { it.action == AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION })
            assertNull(shadowOf(context).nextStartedActivity)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun aClosedOriginalPhaseAndAnOlderOriginalSnapshotCannotInstallWorkers() = runBlocking {
        val trigger = System.currentTimeMillis() + 60_000L
        social.socialDao().putResources(listOf(SyncedResource("group", "occurrence", "occurrence", Json.encodeToString(SocialOccurrenceDto(
            "alarm", occurrenceId, "pending", 1, triggerAt = Instant.ofEpochMilli(trigger).toString(),
            deadlineAt = Instant.ofEpochMilli(trigger + 5 * 60_000L).toString()
        )))))
        Preferences.edit { putLong("${SocialPreferences.alarmDeadlinePrefix}$localId", 0L) }
        SocialRepository(context, social, alarmRepository).refreshSharedState()
        assertEquals(0L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}$localId", -1L))
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork("jay_ignored_alarm_$localId").get().all { it.state.isFinished })

        Preferences.edit {
            remove("${SocialPreferences.alarmDeadlinePrefix}$localId")
            putLong("${SocialPreferences.alarmSnoozedAtPrefix}$localId", trigger - 30_000L)
        }
        SocialRepository(context, social, alarmRepository).refreshSharedState()
        assertFalse(Preferences.instance.contains("${SocialPreferences.alarmDeadlinePrefix}$localId"))
        assertEquals(trigger - 30_000L, Preferences.instance.getLong("${SocialPreferences.alarmSnoozedAtPrefix}$localId", -1L))
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork("jay_ignored_alarm_$localId").get().all { it.state.isFinished })
        assertNull(alarmRepository.getAlarmById(localId)?.snoozedUntil)
    }

    @Test
    fun aReceivedPhaseAlreadyRingingDoesNotRearmOrStopTheLocalRing() = runBlocking {
        val nextRing = System.currentTimeMillis() - 60_000L
        val deadline = nextRing + 5 * 60_000L
        Preferences.edit { putLong("${SocialPreferences.alarmDeadlinePrefix}$localId", deadline) }
        social.socialDao().putResources(listOf(SyncedResource("group", "occurrence", "occurrence", Json.encodeToString(SocialOccurrenceDto("alarm", occurrenceId, "pending", 1, Instant.ofEpochMilli(nextRing).toString())))))

        SocialRepository(context, social, alarmRepository).refreshSharedState()

        assertNull(alarmRepository.getAlarmById(localId)?.snoozedUntil)
        assertEquals(deadline, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}$localId", -1L))
        assertFalse(shadowOf(context).broadcastIntents.any { it.action == AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION })
    }

    @Test
    fun aReceivedExpiredPhaseDoesNotStartAnOldRing() = runBlocking {
        val nextRing = System.currentTimeMillis() - 20 * 60_000L
        social.socialDao().putResources(listOf(SyncedResource("group", "occurrence", "occurrence", Json.encodeToString(SocialOccurrenceDto("alarm", occurrenceId, "pending", 1, Instant.ofEpochMilli(nextRing).toString())))))

        SocialRepository(context, social, alarmRepository).refreshSharedState()

        assertNull(alarmRepository.getAlarmById(localId)?.snoozedUntil)
        assertFalse(shadowOf(context).broadcastIntents.any { it.action == AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION })
    }

    @Test
    fun aPendingLocalDismissalSuppressesTheOlderSharedSnooze() = runBlocking {
        val nextRing = System.currentTimeMillis() + 10 * 60_000L
        Preferences.edit { putLong("${SocialPreferences.alarmDeadlinePrefix}$localId", 0L) }
        social.socialDao().putResources(listOf(SyncedResource("group", "occurrence", "occurrence", Json.encodeToString(SocialOccurrenceDto("alarm", occurrenceId, "pending", 1, Instant.ofEpochMilli(nextRing).toString())))))

        SocialRepository(context, social, alarmRepository).refreshSharedState()

        assertNull(alarmRepository.getAlarmById(localId)?.snoozedUntil)
        assertEquals(0L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}$localId", -1L))
        assertFalse(shadowOf(context).broadcastIntents.any { it.action == AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION })
    }

    @Test
    fun olderManualAndAutomaticSnapshotsDoNotReplaceTheLocalSnoozeOutcome() = runBlocking {
        val trigger = System.currentTimeMillis() - 21 * 60_000L
        val localRing = trigger + 30 * 60_000L
        alarmRepository.updateAlarm(requireNotNull(alarmRepository.getAlarmById(localId)).copy(snoozedUntil = localRing))
        Preferences.edit {
            putLong("${SocialPreferences.alarmDeadlinePrefix}$localId", localRing + 5 * 60_000L)
            putLong("${SocialPreferences.alarmSnoozedAtPrefix}$localId", trigger + 20 * 60_000L)
        }
        for ((outcome, target) in listOf(10 to 25, 5 to 15)) {
            social.socialDao().putResources(listOf(SyncedResource("group", "occurrence", "occurrence", Json.encodeToString(SocialOccurrenceDto("alarm", occurrenceId, "pending", 1, Instant.ofEpochMilli(trigger + target * 60_000L).toString(), Instant.ofEpochMilli(trigger + outcome * 60_000L).toString())))))
            SocialRepository(context, social, alarmRepository).refreshSharedState()
            assertEquals(localRing, alarmRepository.getAlarmById(localId)?.snoozedUntil)
            assertEquals(trigger + 20 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmSnoozedAtPrefix}$localId", -1L))
        }
    }

    @Test
    fun aLaterManualOutcomeCanChooseAnEarlierRingTime() = runBlocking {
        val trigger = System.currentTimeMillis() - 21 * 60_000L
        val localRing = trigger + 30 * 60_000L
        val manualRing = trigger + 26 * 60_000L
        alarmRepository.updateAlarm(requireNotNull(alarmRepository.getAlarmById(localId)).copy(snoozedUntil = localRing))
        Preferences.edit {
            putLong("${SocialPreferences.alarmDeadlinePrefix}$localId", localRing + 5 * 60_000L)
            putLong("${SocialPreferences.alarmSnoozedAtPrefix}$localId", trigger + 20 * 60_000L)
        }
        social.socialDao().putResources(listOf(SyncedResource("group", "occurrence", "occurrence", Json.encodeToString(SocialOccurrenceDto("alarm", occurrenceId, "pending", 1, Instant.ofEpochMilli(manualRing).toString(), Instant.ofEpochMilli(trigger + 21 * 60_000L).toString())))))

        SocialRepository(context, social, alarmRepository).refreshSharedState()

        assertEquals(manualRing, alarmRepository.getAlarmById(localId)?.snoozedUntil)
        assertEquals(manualRing + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}$localId", -1L))
        assertEquals(trigger + 21 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmSnoozedAtPrefix}$localId", -1L))
        assertEquals(occurrenceId, Preferences.instance.getString("${SocialPreferences.alarmOccurrencePrefix}$localId", null))
    }

    @Test
    fun lateDeliverySkipsExpiredRingsAndPublishesOnlyTheCanonicalOriginalTimeoutStep() = runBlocking {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        for ((lateMinutes, nextMinutes, lastTimeoutMinutes) in listOf(Triple(20, 30, 20), Triple(36, 45, 35))) {
            Preferences.instance.all.keys.filter { it.startsWith("jayPendingAlarmActivity:") }.forEach { key -> Preferences.edit { remove(key) } }
            val trigger = System.currentTimeMillis() - lateMinutes * 60_000L
            val sourceDeadline = trigger + 5 * 60_000L
            alarmRepository.updateAlarm(requireNotNull(alarmRepository.getAlarmById(localId)).copy(snoozedUntil = null))
            Preferences.edit {
                putLong("${SocialPreferences.alarmDeadlinePrefix}$localId", sourceDeadline)
                putLong("${SocialPreferences.alarmSnoozedAtPrefix}$localId", 0L)
            }
            val controller = Robolectric.buildService(AlarmService::class.java).create()
            try {
                val result = controller.get().onStartCommand(Intent(context, AlarmService::class.java)
                    .putExtra(AlarmHelper.EXTRA_ID, localId).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, trigger), 0, 1)
                assertEquals(Service.START_NOT_STICKY, result)
                assertEquals(trigger + nextMinutes * 60_000L, alarmRepository.getAlarmById(localId)?.snoozedUntil)
                assertEquals(trigger + (nextMinutes + 5) * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}$localId", -1L))
                assertEquals(trigger + lastTimeoutMinutes * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmSnoozedAtPrefix}$localId", -1L))
                assertNull(shadowOf(context).nextStartedActivity)
                val event = JSONObject(Preferences.instance.all.entries.single { it.key.startsWith("jayPendingAlarmActivity:") }.value as String)
                assertEquals(Instant.ofEpochMilli(sourceDeadline).toString(), event.getString("occurred_at"))
                assertEquals(Instant.ofEpochMilli(trigger + 15 * 60_000L).toString(), event.getString("snoozed_until"))
                assertEquals(occurrenceId, event.getString("occurrence_id"))
            } finally {
                controller.destroy()
            }
        }
    }

    @Test
    fun aStaleIncomingAlarmPreservesTheCurrentAlarmAndItsOccurrenceIdentity() = runBlocking {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val other = Alarm(time = 0L, enabled = true, soundEnabled = false, vibrate = false)
        other.id = alarmRepository.addAlarm(other)
        val trigger = System.currentTimeMillis()
        Preferences.edit {
            putString("${SocialPreferences.alarmOccurrencePrefix}${other.id}", "other-occurrence")
            putLong("${SocialPreferences.alarmDeadlinePrefix}${other.id}", trigger + 5 * 60_000L)
            putLong("${SocialPreferences.alarmDeadlinePrefix}$localId", 0L)
        }
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(context, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, other.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, trigger), 0, 1)
            shadowOf(context).nextStartedActivity
            val stale = controller.get().onStartCommand(Intent(context, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, localId).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, trigger), 0, 2)
            assertEquals(Service.START_STICKY, stale)
            assertNull(shadowOf(context).nextStartedActivity)

            context.sendBroadcast(Intent(AlarmService.ALARM_INTENT_ACTION)
                .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.SNOOZE_ACTION).setPackage(context.packageName))
            shadowOf(Looper.getMainLooper()).idle()
            val event = Preferences.instance.all.entries.filter { it.key.startsWith("jayPendingAlarmActivity:") }
                .map { JSONObject(it.value as String) }.single { it.getLong("alarm_id") == other.id }
            assertEquals("other-occurrence", event.getString("occurrence_id"))
            assertEquals("SNOOZED", event.getString("activity_kind"))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun aQueuedOldRingIntentRestoresTheCurrentSharedPhaseWithoutRinging() = runBlocking {
        val oldRing = System.currentTimeMillis()
        val currentRing = oldRing + 15 * 60_000L
        Preferences.edit { putLong("${SocialPreferences.alarmDeadlinePrefix}$localId", currentRing + 5 * 60_000L) }
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        try {
            val result = controller.get().onStartCommand(Intent(context, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, localId).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, oldRing), 0, 1)

            assertEquals(Service.START_NOT_STICKY, result)
            assertEquals(currentRing, alarmRepository.getAlarmById(localId)?.snoozedUntil)
            assertNull(shadowOf(context).nextStartedActivity)
        } finally {
            controller.destroy()
        }
    }
}
