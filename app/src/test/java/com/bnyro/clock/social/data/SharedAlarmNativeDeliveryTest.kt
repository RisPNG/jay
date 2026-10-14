package com.bnyro.clock.social.data

import android.app.AlarmManager
import android.app.Service
import android.content.Intent
import android.os.UserManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.App
import com.bnyro.clock.AppContainer
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.RepeatUnit
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.receivers.AlarmReceiver
import com.bnyro.clock.util.services.AlarmService
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class SharedAlarmNativeDeliveryTest {
    private lateinit var context: App
    private lateinit var database: AppDatabase
    private lateinit var alarm: Alarm
    private var today = 0L
    private var yesterday = 0L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        Preferences.instance.edit().clear().commit()
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        context.container = AppContainer(context, database)
        val scheduled = Instant.now().atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.MINUTES)
        today = scheduled.toInstant().toEpochMilli()
        yesterday = scheduled.minusDays(1).toInstant().toEpochMilli()
        alarm = Alarm(time = scheduled.hour * 3_600_000L + scheduled.minute * 60_000L,
            enabled = true, startDate = scheduled.toLocalDate().minusDays(1).toEpochDay(),
            repeatUnit = RepeatUnit.DAY, soundEnabled = false, vibrate = false)
        alarm.id = context.container.alarmRepository.addAlarm(alarm)
        Preferences.edit {
            putString("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}", yesterday.toString())
            putLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", yesterday + 5 * 60_000L)
            putLong("${SocialPreferences.alarmSnoozedAtPrefix}${alarm.id}", 0L)
        }
        Unit
    }

    @After
    fun tearDown() {
        AlarmHelper.cancel(context, alarm)
        database.close()
        Preferences.instance.edit().clear().commit()
    }

    @Test
    fun theNextCalendarDeliveryRingsOfflineWithItsOwnDeadlineAndIdentity() = runBlocking {
        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, today)
            .putExtra(AlarmService.EXTRA_OCCURRENCE_ID, today.toString()))

        val delivered = requireNotNull(shadowOf(context).nextStartedService)
        assertEquals(today.toString(), delivered.getStringExtra(AlarmService.EXTRA_OCCURRENCE_ID))
        assertEquals(today + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", -1L))
        assertEquals(today.toString(), Preferences.instance.getString("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}", null))
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        try {
            assertEquals(Service.START_STICKY, controller.get().onStartCommand(delivered, 0, 1))
            assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertNotNull(shadowOf(context).nextStartedActivity)
            assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun schedulingTomorrowDoesNotAdvanceTheOccurrenceThatIsRingingToday() {
        Preferences.edit {
            putString("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}", today.toString())
            putLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", today + 5 * 60_000L)
        }

        AlarmHelper.enqueue(context, alarm, skipToday = true)

        val scheduled = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .single { shadowOf(it.operation).savedIntent.component?.className == AlarmReceiver::class.java.name }
        val intent = shadowOf(scheduled.operation).savedIntent
        assertTrue(scheduled.triggerAtTime > today)
        assertEquals(scheduled.triggerAtTime.toString(), intent.getStringExtra(AlarmService.EXTRA_OCCURRENCE_ID))
        assertEquals(today.toString(), Preferences.instance.getString("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}", null))
        assertEquals(today + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", -1L))
    }

    @Test
    fun aClosedEarlierCycleDoesNotSuppressTheFinalCalendarOccurrence() = runBlocking {
        alarm.endOccurrences = 2
        context.container.alarmRepository.updateAlarm(alarm)
        Preferences.edit { putLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", 0L) }

        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, today)
            .putExtra(AlarmService.EXTRA_OCCURRENCE_ID, today.toString()))

        assertFalse(requireNotNull(context.container.alarmRepository.getAlarmById(alarm.id)).enabled)
        assertEquals(today.toString(), requireNotNull(shadowOf(context).nextStartedService).getStringExtra(AlarmService.EXTRA_OCCURRENCE_ID))
        assertEquals(today + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", -1L))
    }

    @Test
    fun aSnoozedDeliveryRetainsItsOriginalOccurrenceEvenWhenTheAlarmIsDisabled() = runBlocking {
        val ring = today - 60_000L
        val original = today - 15 * 60_000L
        val snoozedAt = ring - 10 * 60_000L + 30_000L
        val originalTime = Instant.ofEpochMilli(original).atZone(ZoneId.systemDefault())
        alarm.time = originalTime.hour * 3_600_000L + originalTime.minute * 60_000L
        alarm.startDate = originalTime.toLocalDate().toEpochDay()
        alarm.enabled = false
        alarm.endOccurrences = 1
        alarm.snoozedUntil = ring
        context.container.alarmRepository.updateAlarm(alarm)
        SocialIgnoredAlarmWorker.schedule(context, alarm, ring, original.toString(), snoozedAt)
        AlarmHelper.enqueue(context, alarm)
        val scheduled = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .single { shadowOf(it.operation).savedIntent.component?.className == AlarmReceiver::class.java.name }
        val incoming = shadowOf(scheduled.operation).savedIntent
        assertEquals(original.toString(), incoming.getStringExtra(AlarmService.EXTRA_OCCURRENCE_ID))

        AlarmReceiver().onReceive(context, incoming)

        val delivered = requireNotNull(shadowOf(context).nextStartedService)
        assertEquals(original.toString(), delivered.getStringExtra(AlarmService.EXTRA_OCCURRENCE_ID))
        assertEquals(ring, delivered.getLongExtra(AlarmHelper.EXTRA_TRIGGER_TIME, -1L))
        assertNull(context.container.alarmRepository.getAlarmById(alarm.id)?.snoozedUntil)
        assertEquals(snoozedAt, Preferences.instance.getLong("${SocialPreferences.alarmSnoozedAtPrefix}${alarm.id}", -1L))
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        try {
            assertEquals(Service.START_STICKY, controller.get().onStartCommand(delivered, 0, 1))
            assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun aStaleCalendarDeliveryCannotClearTheNewManualTarget() = runBlocking {
        val nextRing = today + 10 * 60_000L
        val snoozedAt = System.currentTimeMillis()
        alarm.snoozedUntil = nextRing
        context.container.alarmRepository.updateAlarm(alarm)
        SocialIgnoredAlarmWorker.schedule(context, alarm, nextRing, today.toString(), snoozedAt)
        AlarmHelper.enqueue(context, alarm)

        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, today)
            .putExtra(AlarmService.EXTRA_OCCURRENCE_ID, today.toString()))

        assertEquals(nextRing, context.container.alarmRepository.getAlarmById(alarm.id)?.snoozedUntil)
        assertEquals(nextRing + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", -1L))
        assertEquals(snoozedAt, Preferences.instance.getLong("${SocialPreferences.alarmSnoozedAtPrefix}${alarm.id}", -1L))
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun aStaleSnoozeDeliveryCannotClearTheReplacementManualTarget() = runBlocking {
        val original = today - 15 * 60_000L
        val oldRing = today - 60_000L
        val originalTime = Instant.ofEpochMilli(original).atZone(ZoneId.systemDefault())
        val nextRing = today + 10 * 60_000L
        val snoozedAt = System.currentTimeMillis()
        alarm.time = originalTime.hour * 3_600_000L + originalTime.minute * 60_000L
        alarm.snoozedUntil = nextRing
        context.container.alarmRepository.updateAlarm(alarm)
        SocialIgnoredAlarmWorker.schedule(context, alarm, nextRing, original.toString(), snoozedAt)
        AlarmHelper.enqueue(context, alarm)

        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, oldRing)
            .putExtra(AlarmService.EXTRA_OCCURRENCE_ID, original.toString()))

        assertEquals(nextRing, context.container.alarmRepository.getAlarmById(alarm.id)?.snoozedUntil)
        assertEquals(original.toString(), Preferences.instance.getString("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}", null))
        assertEquals(nextRing + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", -1L))
        assertEquals(snoozedAt, Preferences.instance.getLong("${SocialPreferences.alarmSnoozedAtPrefix}${alarm.id}", -1L))
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun anOldCalendarIntentCannotBecomeANewOccurrenceAfterTheTimeWasEditedEarlier() = runBlocking {
        val earlier = Instant.ofEpochMilli(today).atZone(ZoneId.systemDefault()).minusMinutes(10)
        alarm.time = earlier.hour * 3_600_000L + earlier.minute * 60_000L
        alarm.startDate = earlier.toLocalDate().minusDays(1).toEpochDay()
        context.container.alarmRepository.updateAlarm(alarm)
        val current = earlier.toInstant().toEpochMilli()
        Preferences.edit {
            putString("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}", current.toString())
            putLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", current + 5 * 60_000L)
        }

        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, today)
            .putExtra(AlarmService.EXTRA_OCCURRENCE_ID, today.toString()))

        assertNull(shadowOf(context).nextStartedService)
        assertEquals(current.toString(), Preferences.instance.getString("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}", null))
        assertEquals(current + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", -1L))
    }

    @Test
    fun legacyCalendarDeliveryUsesTheConfiguredTimeAndInstallsTheNewItemDeadline() {
        Preferences.edit { remove("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}") }

        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java).putExtra(AlarmHelper.EXTRA_ID, alarm.id))

        val delivered = requireNotNull(shadowOf(context).nextStartedService)
        assertEquals(today, delivered.getLongExtra(AlarmHelper.EXTRA_TRIGGER_TIME, -1L))
        assertEquals(today.toString(), delivered.getStringExtra(AlarmService.EXTRA_OCCURRENCE_ID))
        assertEquals(today + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", -1L))
    }

    @Test
    fun aLegacyDeliveryCannotConsumeAFutureManualSnooze() = runBlocking {
        val snoozedAt = System.currentTimeMillis()
        val future = snoozedAt + 10 * 60_000L
        alarm.snoozedUntil = future
        context.container.alarmRepository.updateAlarm(alarm)
        SocialIgnoredAlarmWorker.schedule(context, alarm, future, today.toString(), snoozedAt)

        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java).putExtra(AlarmHelper.EXTRA_ID, alarm.id))

        assertNull(shadowOf(context).nextStartedService)
        assertEquals(future, context.container.alarmRepository.getAlarmById(alarm.id)?.snoozedUntil)
        assertEquals(future + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", -1L))
        assertEquals(snoozedAt, Preferences.instance.getLong("${SocialPreferences.alarmSnoozedAtPrefix}${alarm.id}", -1L))
    }

    @Test
    fun aLegacyDueSnoozeKeepsItsOriginalKeyAndInstallsTheItemDeadline() = runBlocking {
        val due = today - 60_000L
        val original = today - 15 * 60_000L
        val snoozedAt = due - 10 * 60_000L + 30_000L
        val originalTime = Instant.ofEpochMilli(original).atZone(ZoneId.systemDefault())
        alarm.time = originalTime.hour * 3_600_000L + originalTime.minute * 60_000L
        alarm.snoozedUntil = due
        context.container.alarmRepository.updateAlarm(alarm)
        Preferences.edit {
            putString("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}", original.toString())
            remove("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}")
            putLong("${SocialPreferences.alarmSnoozedAtPrefix}${alarm.id}", snoozedAt)
        }

        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java).putExtra(AlarmHelper.EXTRA_ID, alarm.id))

        val delivered = requireNotNull(shadowOf(context).nextStartedService)
        assertEquals(due, delivered.getLongExtra(AlarmHelper.EXTRA_TRIGGER_TIME, -1L))
        assertEquals(original.toString(), delivered.getStringExtra(AlarmService.EXTRA_OCCURRENCE_ID))
        assertEquals(due + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", -1L))
        assertEquals(snoozedAt, Preferences.instance.getLong("${SocialPreferences.alarmSnoozedAtPrefix}${alarm.id}", -1L))
    }

    @Test
    fun anOldQueuedServiceDeliveryCannotBorrowTheNewCalendarOccurrence() {
        alarm.missedRestartEnabled = true
        runBlocking { context.container.alarmRepository.updateAlarm(alarm) }
        Preferences.edit {
            putString("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}", today.toString())
            putLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", today + 5 * 60_000L)
        }
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        try {
            val result = controller.get().onStartCommand(Intent(context, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, alarm.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, yesterday)
                .putExtra(AlarmService.EXTRA_OCCURRENCE_ID, yesterday.toString()), 0, 1)

            assertEquals(Service.START_NOT_STICKY, result)
            assertNull(shadowOf(controller.get()).lastForegroundNotification)
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            assertEquals(today.toString(), Preferences.instance.getString("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}", null))
            assertEquals(today + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", -1L))
        } finally {
            controller.destroy()
        }
    }
}
