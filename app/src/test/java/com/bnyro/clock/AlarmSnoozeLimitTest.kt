package com.bnyro.clock

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.usecase.ClockBackupUseCase
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.presentation.screens.alarm.AlarmActivity
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.receivers.AlarmReceiver
import com.bnyro.clock.util.services.AlarmService
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
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
@Config(sdk = [30], application = App::class, instrumentedPackages = ["com.bnyro.clock.util"])
class AlarmSnoozeLimitTest {
    private lateinit var context: App
    private lateinit var database: AppDatabase
    private lateinit var alarm: Alarm
    private val ringTime = Instant.parse("2026-10-14T09:00:00Z").toEpochMilli()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        Preferences.init(context)
        Preferences.instance.edit().clear().commit()
        assertTrue(SystemClock.setCurrentTimeMillis(ringTime - 60_000L))
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        context.container = AppContainer(database)
        val scheduled = Instant.ofEpochMilli(ringTime).atZone(ZoneId.systemDefault())
        alarm = Alarm(
            time = scheduled.hour * 3_600_000L + scheduled.minute * 60_000L,
            enabled = true,
            snoozeMinutes = 5,
            soundEnabled = false,
            vibrate = false
        )
        alarm.id = context.container.alarmRepository.addAlarm(alarm)
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        context.container.alarmRepository.getAlarms().forEach { AlarmHelper.cancel(context, it) }
        database.close()
        Preferences.instance.edit().clear().commit()
        Unit
    }

    @Test
    fun aFiniteBudgetCountsPersistedSnoozesAndRejectsAnotherPressFromAStaleAlarm() = runBlocking {
        alarm.snoozeLimit = 2
        context.container.alarmRepository.updateAlarm(alarm)
        assertTrue(SystemClock.setCurrentTimeMillis(ringTime + 100_000L))
        val first = requireNotNull(AlarmHelper.snooze(context, alarm, ringTime, 5))
        assertEquals(ringTime + 5 * 60_000L, first)
        assertTrue(SystemClock.setCurrentTimeMillis(first + 100_000L))

        val second = requireNotNull(AlarmHelper.snooze(context, alarm, first, 5))

        val spent = requireNotNull(context.container.alarmRepository.getAlarmById(alarm.id))
        assertEquals(ringTime + 10 * 60_000L, second)
        assertEquals(2, spent.snoozeCount)
        assertFalse(spent.canSnooze)
        assertNull(AlarmHelper.snooze(context, alarm, second, 5))
        assertEquals(second, context.getSystemService(AlarmManager::class.java).nextAlarmClock.triggerTime)
    }

    @Test
    fun zeroRemainsUnlimitedWhileTheMaximumBudgetAllowsItsHundredthSnooze() = runBlocking {
        assertTrue(SystemClock.setCurrentTimeMillis(ringTime + 100_000L))
        for (limit in listOf(0, 100)) {
            alarm.snoozeLimit = limit
            alarm.snoozeCount = 99
            context.container.alarmRepository.updateAlarm(alarm)

            val target = requireNotNull(AlarmHelper.snooze(context, alarm, ringTime, 5))

            val snoozed = requireNotNull(context.container.alarmRepository.getAlarmById(alarm.id))
            assertEquals(100, snoozed.snoozeCount)
            assertEquals(limit == 0, snoozed.canSnooze)
            assertEquals(limit == 0, AlarmHelper.snooze(context, alarm, target, 5) != null)
        }
    }

    @Test
    fun nativeSnoozedDeliveryRetainsTheCountAndTheNextOccurrenceStartsFresh() = runBlocking {
        alarm.snoozeLimit = 1
        context.container.alarmRepository.updateAlarm(alarm)
        assertTrue(SystemClock.setCurrentTimeMillis(ringTime + 100_000L))
        val target = requireNotNull(AlarmHelper.snooze(context, alarm, ringTime, 5))
        assertTrue(SystemClock.setCurrentTimeMillis(target))

        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
            .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, target))

        val snoozedRing = requireNotNull(context.container.alarmRepository.getAlarmById(alarm.id))
        assertEquals(1, snoozedRing.snoozeCount)
        assertFalse(snoozedRing.canSnooze)
        assertNull(snoozedRing.snoozedUntil)
        val nextDay = ringTime + 86_400_000L
        assertTrue(SystemClock.setCurrentTimeMillis(nextDay))

        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
            .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, nextDay))

        val nextRing = requireNotNull(context.container.alarmRepository.getAlarmById(alarm.id))
        assertEquals(0, nextRing.snoozeCount)
        assertTrue(nextRing.canSnooze)
    }

    @Test
    fun rearmingAnAlarmStartsWithItsConfiguredBudget() = runBlocking {
        alarm.snoozeLimit = 2
        alarm.snoozeCount = 2
        alarm.snoozedUntil = ringTime + 5 * 60_000L
        context.container.alarmRepository.updateAlarm(alarm)

        CreateUpdateDeleteAlarmUseCase(context, context.container.alarmRepository).updateAlarm(alarm)

        val rearmed = requireNotNull(context.container.alarmRepository.getAlarmById(alarm.id))
        assertEquals(2, rearmed.snoozeLimit)
        assertEquals(0, rearmed.snoozeCount)
        assertNull(rearmed.snoozedUntil)
        assertTrue(rearmed.canSnooze)
    }

    @Test
    fun backupsKeepTheLimitAndClearRuntimeCountsWithoutDuplicatingAnExistingAlarm() = runBlocking {
        alarm.snoozeLimit = 2
        alarm.snoozeCount = 1
        alarm.snoozedUntil = ringTime + 5 * 60_000L
        context.container.alarmRepository.updateAlarm(alarm)
        val useCase = ClockBackupUseCase(context)

        val backup = useCase.capture(emptyList())

        assertEquals(2, backup.alarms.single().snoozeLimit)
        assertEquals(0, backup.alarms.single().snoozeCount)
        assertNull(backup.alarms.single().snoozedUntil)
        val imported = backup.copy(alarms = listOf(backup.alarms.single().copy(snoozeCount = 99)), preferences = null)
        useCase.restore(imported)
        assertEquals(1, context.container.alarmRepository.getAlarms().size)
        AlarmHelper.cancel(context, alarm)
        context.container.alarmRepository.deleteAlarm(alarm)

        useCase.restore(imported)

        val restored = context.container.alarmRepository.getAlarms().single()
        assertEquals(2, restored.snoozeLimit)
        assertEquals(0, restored.snoozeCount)
        assertNull(restored.snoozedUntil)
    }

    @Test
    fun volumeSnoozeDoesNothingWhenDisabledOrAfterTheLimit() = runBlocking {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        for (snoozeEnabled in listOf(true, false)) {
            alarm.snoozeEnabled = snoozeEnabled
            alarm.snoozeLimit = 1
            alarm.snoozeCount = if (snoozeEnabled) 1 else 0
            context.container.alarmRepository.updateAlarm(alarm)
            val controller = Robolectric.buildActivity(AlarmActivity::class.java,
                Intent(context, AlarmActivity::class.java).putExtra(AlarmHelper.EXTRA_ID, alarm.id)
                    .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ringTime)).create()
            try {
                assertTrue(controller.get().onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, null))
                assertFalse(controller.get().isFinishing)
                val unchanged = requireNotNull(context.container.alarmRepository.getAlarmById(alarm.id))
                assertNull(unchanged.snoozedUntil)
                assertEquals(alarm.snoozeCount, unchanged.snoozeCount)
            } finally {
                controller.destroy()
            }
        }
    }

    @Test
    fun aSpentBudgetHidesNotificationSnoozeAndRejectsBroadcastsUntilTheOriginalTimeout() = runBlocking {
        alarm.snoozeLimit = 1
        alarm.snoozeCount = 1
        context.container.alarmRepository.updateAlarm(alarm)
        Preferences.edit { putInt(Preferences.alarmTimeoutMinutesKey, 1) }
        assertTrue(SystemClock.setCurrentTimeMillis(ringTime))
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(context, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
                .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ringTime), 0, 1)
            val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertTrue(notification.actions.none { it.title == context.getString(R.string.snooze) })
            assertTrue(notification.actions.any { it.title == context.getString(R.string.dismiss) })

            context.sendBroadcast(Intent(AlarmService.ALARM_INTENT_ACTION)
                .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.SNOOZE_ACTION)
                .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
                .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ringTime)
                .setPackage(context.packageName))
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(shadowOf(controller.get()).isStoppedBySelf)
            val unchanged = requireNotNull(context.container.alarmRepository.getAlarmById(alarm.id))
            assertEquals(1, unchanged.snoozeCount)
            assertNull(unchanged.snoozedUntil)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59))
            assertFalse(shadowOf(controller.get()).isStoppedBySelf)

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.id == alarm.id.toInt() + 8000
            })
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun theLastAllowedNotificationSnoozePersistsTheCountAndStopsTheCurrentRing() = runBlocking {
        alarm.snoozeLimit = 1
        context.container.alarmRepository.updateAlarm(alarm)
        assertTrue(SystemClock.setCurrentTimeMillis(ringTime + 100_000L))
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(context, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
                .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ringTime), 0, 1)
            val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)

            notification.actions.single { it.title == context.getString(R.string.snooze) }.actionIntent.send()
            shadowOf(Looper.getMainLooper()).idle()

            val snoozed = requireNotNull(context.container.alarmRepository.getAlarmById(alarm.id))
            assertEquals(1, snoozed.snoozeCount)
            assertEquals(ringTime + 5 * 60_000L, snoozed.snoozedUntil)
            assertFalse(snoozed.canSnooze)
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun version16AlarmsKeepTheirPendingSnoozeAndUpgradeToAnUnlimitedBudget() = runBlocking {
        val name = "alarm-snooze-limit-migration"
        val original = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var upgraded: AppDatabase? = null
        try {
            val pending = alarm.copy(id = 0, snoozedUntil = ringTime + 5 * 60_000L)
            pending.id = original.alarmsDao().insert(pending)
            val sqlite = original.openHelper.writableDatabase
            val legacySchema = sqlite.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'alarms'").use {
                it.moveToFirst()
                it.getString(0).replace(Regex(", `(snoozeLimit|snoozeCount)` INTEGER NOT NULL DEFAULT [0-9]+"), "")
            }
            sqlite.execSQL("ALTER TABLE alarms RENAME TO alarms_before_snooze_limit")
            sqlite.execSQL(legacySchema)
            val columns = sqlite.query("PRAGMA table_info(alarms)").use { rows ->
                buildList {
                    while (rows.moveToNext()) add("`${rows.getString(rows.getColumnIndexOrThrow("name"))}`")
                }.joinToString(", ")
            }
            sqlite.execSQL("INSERT INTO alarms ($columns) SELECT $columns FROM alarms_before_snooze_limit")
            sqlite.execSQL("DROP TABLE alarms_before_snooze_limit")
            sqlite.version = 16
            original.close()

            upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name).build()

            val restored = requireNotNull(upgraded.alarmsDao().findById(pending.id))
            assertEquals(pending, restored)
            assertTrue(restored.canSnooze)
            upgraded.alarmsDao().update(restored.copy(snoozeLimit = 100, snoozeCount = 99))
            assertEquals(100, upgraded.alarmsDao().findById(pending.id)?.snoozeLimit)
            assertEquals(99, upgraded.alarmsDao().findById(pending.id)?.snoozeCount)
        } finally {
            original.close()
            upgraded?.close()
            context.deleteDatabase(name)
        }
    }
}
