package com.bnyro.clock.social.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.App
import com.bnyro.clock.AppContainer
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.social.domain.DeviceCapabilities
import com.bnyro.clock.social.domain.MembershipAccessDto
import com.bnyro.clock.social.domain.SharedAlarmDto
import com.bnyro.clock.social.domain.SharedTimerDto
import com.bnyro.clock.social.domain.SocialGroupDto
import com.bnyro.clock.social.domain.SyncedResource
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.TimerService
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class LegacyGroupTimingTest {
    private lateinit var context: App
    private lateinit var social: SocialDatabase
    private lateinit var alarms: AppDatabase
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var repository: SocialRepository
    private var expiry = 0L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context.packageManager).setInstallSourceInfo(context.packageName, null, null)
        Preferences.init(context)
        Preferences.edit { clear() }
        social = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        alarms = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        context.container = AppContainer(context, alarms)
        alarmRepository = AlarmRepository(alarms.alarmsDao())
        repository = SocialRepository(context, social, alarmRepository)
        expiry = System.currentTimeMillis() + 600_000L
        social.socialDao().putResources(listOf(
            SyncedResource("identity", "membership", "membership", Json.encodeToString(
                MembershipAccessDto("membership", "group", "group", "member", true, true)
            )),
            SyncedResource("group", "group", "group", Json.encodeToString(
                SocialGroupDto("group", "Kitchen", "everyone", true, true, true, true)
            )),
            SyncedResource("group", "timer", "timer", Json.encodeToString(
                SharedTimerDto("timer", "group", "Tea", durationSeconds = 60, incrementSeconds = 15,
                    expiresAt = Instant.ofEpochMilli(expiry).toString(), timeoutMinutes = 3,
                    repeatEnabled = true, repeatCount = 4, repeatIndex = 1, soundMode = "off")
            )),
            SyncedResource("group", "alarm", "alarm", Json.encodeToString(
                SharedAlarmDto(id = "alarm", groupId = "group", revision = 1, saveId = "save", time = 0L,
                    label = "Wake up", enabled = false, days = emptyList(), vibrate = false,
                    startDate = LocalDate.now().toString(), repeatInterval = 1, repeatUnit = "DAY",
                    repeatAnchor = "DAY_OF_MONTH", repeatDurationUnit = "DAY", snoozeEnabled = true,
                    snoozeMinutes = 5, timeoutMinutes = 3, missedRestartEnabled = true,
                    vibrationPattern = listOf(0, 1000), vibrationPatternName = "Default", soundMode = "off")
            ))
        ))
    }

    @After
    fun tearDown() {
        social.close()
        alarms.close()
        Preferences.edit { clear() }
    }

    @Test
    fun compatibilityMaterializesTheOldGlobalTimeoutsWithoutChangingPersonalAlarms() = runBlocking {
        Preferences.edit {
            putInt(SocialPreferences.legacyAlarmTimeoutMinutesKey, 23)
            putInt(SocialPreferences.legacyTimerTimeoutMinutesKey, 17)
        }
        Preferences.init(context)
        val personal = Alarm(time = 0L, enabled = false, timeoutMinutes = 4, missedRestartEnabled = true)
        personal.id = alarmRepository.addAlarm(personal)

        repository.refreshSharedState()

        val shared = requireNotNull(alarmRepository.getAlarmById(requireNotNull(social.socialDao().getAlarmLinkByRemoteId("alarm")).localAlarmId))
        assertEquals(23, shared.timeoutMinutes)
        assertFalse(shared.missedRestartEnabled)
        assertFalse(Preferences.instance.getBoolean("${SocialPreferences.alarmAdvancedTimingPrefix}${shared.id}", true))
        val timer = requireNotNull(shadowOf(context).nextStartedService)
        assertEquals(TimerService.SYNC_SHARED_TIMER_ACTION, timer.action)
        assertEquals("timer", timer.getStringExtra(TimerService.SHARED_TIMER_RUN_ID_EXTRA_KEY))
        assertEquals(17, timer.getIntExtra(TimerService.SHARED_TIMER_TIMEOUT_EXTRA_KEY, -1))
        assertFalse(timer.getBooleanExtra(TimerService.SHARED_TIMER_REPEAT_ENABLED_EXTRA_KEY, true))
        assertEquals(0, timer.getIntExtra(TimerService.SHARED_TIMER_REPEAT_COUNT_EXTRA_KEY, -1))
        assertEquals(0, timer.getIntExtra(TimerService.SHARED_TIMER_REPEAT_INDEX_EXTRA_KEY, -1))
        assertEquals(4, alarmRepository.getAlarmById(personal.id)?.timeoutMinutes)
        assertTrue(requireNotNull(alarmRepository.getAlarmById(personal.id)).missedRestartEnabled)
    }

    @Test
    fun changingServerTimingSupportUpdatesExistingAlarmsWithoutAnAlarmEdit() = runBlocking {
        repository.refreshSharedState()
        val localId = requireNotNull(social.socialDao().getAlarmLinkByRemoteId("alarm")).localAlarmId
        assertEquals(10, alarmRepository.getAlarmById(localId)?.timeoutMinutes)
        val occurrenceId = (System.currentTimeMillis() - 60_000L).toString()
        val snoozedUntil = System.currentTimeMillis() + 7 * 60_000L
        alarmRepository.updateAlarm(requireNotNull(alarmRepository.getAlarmById(localId)).copy(snoozedUntil = snoozedUntil))
        Preferences.edit {
            putString("${SocialPreferences.alarmOccurrencePrefix}$localId", occurrenceId)
            putLong("${SocialPreferences.alarmDeadlinePrefix}$localId", snoozedUntil + 10 * 60_000L)
        }
        val identity = DeviceIdentityStore.loadOrCreate(context, SocialRepository.DEFAULT_SERVER_URL)
        Preferences.edit {
            putString(SocialPreferences.capabilitiesServerKey, SocialRepository.DEFAULT_SERVER_URL)
            putString(SocialPreferences.capabilitiesDeviceKey, identity.id)
            putString(SocialPreferences.capabilitiesInstallationKey, "")
            putString(SocialPreferences.capabilitiesKey, Json.encodeToString(DeviceCapabilities(advancedGroupTiming = true)))
        }

        repository.refreshSharedState()

        assertEquals(3, alarmRepository.getAlarmById(localId)?.timeoutMinutes)
        assertTrue(requireNotNull(alarmRepository.getAlarmById(localId)).missedRestartEnabled)
        assertEquals(snoozedUntil, alarmRepository.getAlarmById(localId)?.snoozedUntil)
        assertEquals(occurrenceId, Preferences.instance.getString("${SocialPreferences.alarmOccurrencePrefix}$localId", null))
        Preferences.edit { putString(SocialPreferences.capabilitiesKey, Json.encodeToString(DeviceCapabilities())) }
        repository.refreshSharedState()
        assertEquals(10, alarmRepository.getAlarmById(localId)?.timeoutMinutes)
        assertFalse(requireNotNull(alarmRepository.getAlarmById(localId)).missedRestartEnabled)
        assertEquals(snoozedUntil, alarmRepository.getAlarmById(localId)?.snoozedUntil)
        assertEquals(occurrenceId, Preferences.instance.getString("${SocialPreferences.alarmOccurrencePrefix}$localId", null))
    }

    @Test
    fun compatibilitySavesUseTheOriginalAlarmAndTimerRequestFields() = runBlocking {
        repository.refreshSharedState()
        repository.startSharedTimer("group", "New timer", TimerSettings(seconds = 60, timeoutMinutes = 3, repeatEnabled = true, repeatCount = 4))
        repository.createSharedAlarm("group", Alarm(time = 0L, enabled = false, timeoutMinutes = 3, missedRestartEnabled = true))
        val operations = social.socialDao().getPendingOperations()
        val timer = Json.parseToJsonElement(requireNotNull(operations.single { it.kind == "timer" && it.method == "POST" }.payload)).jsonObject
        assertTrue(setOf("run_id", "timeout_minutes", "repeat_enabled", "repeat_count", "repeat_index").none { it in timer })
        assertEquals("60", timer.getValue("duration_seconds").jsonPrimitive.content)
        val alarm = Json.parseToJsonElement(requireNotNull(operations.single { it.kind == "alarm" && it.method == "POST" }.payload)).jsonObject
        assertTrue(setOf("timeout_minutes", "missed_restart_enabled", "missed_restart_minutes").none { it in alarm })
        assertTrue("snooze_minutes" in alarm)
        val localId = requireNotNull(social.socialDao().getAlarmLinkByRemoteId("alarm")).localAlarmId
        repository.updateAlarm(requireNotNull(alarmRepository.getAlarmById(localId)).copy(label = "Edited"))
        repository.adjustSharedTimer("timer", "group", "membership", "new-run", 2, expiry + 15_000L, "adjust", System.currentTimeMillis())
        val updates = social.socialDao().getPendingOperations().filter { it.method == "PUT" }
        val timerUpdate = Json.parseToJsonElement(requireNotNull(updates.single { it.kind == "timer" }.payload)).jsonObject
        val alarmUpdate = Json.parseToJsonElement(requireNotNull(updates.single { it.kind == "alarm" }.payload)).jsonObject
        assertTrue(setOf("run_id", "timeout_minutes", "repeat_enabled", "repeat_count", "repeat_index").none { it in timerUpdate })
        assertTrue(setOf("timeout_minutes", "missed_restart_enabled", "missed_restart_minutes").none { it in alarmUpdate })
    }

    @Test
    fun individualLegacyTimerAnswersStayLocal() = runBlocking {
        repository.refreshSharedState()

        repository.dismissSharedTimer("timer", "group", "membership", "timer", 0, expiry - 700_000L, 10, "answer", System.currentTimeMillis())

        assertNull(social.socialDao().getOperation("answer"))
        assertEquals("timer", social.socialDao().getDismissedTimers().single().timerId)
    }

    @Test
    fun legacyAnswerAsOneUsesTheOriginalDeleteOperation() = runBlocking {
        social.socialDao().putResources(listOf(SyncedResource("group", "group", "group", Json.encodeToString(
            SocialGroupDto("group", "Kitchen", "everyone", true, true, true, true, sharedAnswers = true)
        ))))
        repository.refreshSharedState()

        repository.dismissSharedTimer("timer", "group", "membership", "timer", 0, expiry - 700_000L, 10, "answer", System.currentTimeMillis())

        val answer = requireNotNull(social.socialDao().getOperation("answer"))
        assertEquals("DELETE", answer.method)
        assertEquals("/v1/timers/timer", answer.path)
        assertEquals(setOf("membership_id"), Json.parseToJsonElement(requireNotNull(answer.payload)).jsonObject.keys)
    }
}
