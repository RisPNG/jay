package com.bnyro.clock

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.TimerDescriptor
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.presentation.screens.alarm.AlarmActivity
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.RingingCoordinator
import com.bnyro.clock.util.services.AlarmService
import com.bnyro.clock.util.services.TimerService
import com.bnyro.clock.domain.model.WatchState
import com.bnyro.clock.domain.model.VolumeButtonAction
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class RingingOverlapTest {
    private lateinit var app: App
    private lateinit var database: AppDatabase
    private lateinit var alarmController: ServiceController<AlarmService>
    private var timerController: ServiceController<TimerService>? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Preferences.instance.edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        app.container = AppContainer(app, database)
        alarmController = Robolectric.buildService(AlarmService::class.java).create()
    }

    @After
    fun tearDown() {
        timerController?.destroy()
        alarmController.destroy()
        assertNull(RingingCoordinator.selected)
        database.close()
        Preferences.instance.edit().clear().commit()
    }

    @Test
    fun aSuppressedAlarmKeepsItsTimeoutWhileTheMoreImportantAlarmContinues() = runBlocking {
        val quiet = Alarm(time = 0L, label = "Quiet", soundEnabled = false, vibrate = false, timeoutMinutes = 1)
        quiet.id = app.container.alarmRepository.addAlarm(quiet)
        val important = Alarm(time = 0L, label = "Important", soundEnabled = false, vibrate = true, timeoutMinutes = 5)
        important.id = app.container.alarmRepository.addAlarm(important)
        val now = System.currentTimeMillis()
        alarmController.get().onStartCommand(Intent(app, AlarmService::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, quiet.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, now), 0, 1)
        alarmController.get().onStartCommand(Intent(app, AlarmService::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, important.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, now), 0, 2)

        assertEquals(5 * 60_000L, RingingCoordinator.selected?.timeoutMillis)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))

        assertEquals(5 * 60_000L, RingingCoordinator.selected?.timeoutMillis)
        assertFalse(shadowOf(alarmController.get()).isStoppedBySelf)
        val notifications = app.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue(notifications.any { it.id == quiet.id.toInt() + 8000 })
        assertFalse(notifications.any { it.id == important.id.toInt() + 8000 })
    }

    @Test
    fun dismissingTheWinnerResumesTheOtherAlarmAndItsNotificationStillTargetsIt() = runBlocking {
        val first = Alarm(time = 0L, label = "First", soundEnabled = false, vibrate = false, timeoutMinutes = 5)
        first.id = app.container.alarmRepository.addAlarm(first)
        val second = Alarm(time = 0L, label = "Second", soundEnabled = false, vibrate = true, timeoutMinutes = 5)
        second.id = app.container.alarmRepository.addAlarm(second)
        val now = System.currentTimeMillis()
        listOf(first, second).forEachIndexed { index, alarm ->
            alarmController.get().onStartCommand(Intent(app, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, alarm.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, now), 0, index + 1)
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        val winner = manager.activeNotifications.single {
            shadowOf(it.notification.contentIntent).savedIntent.getLongExtra(AlarmHelper.EXTRA_ID, -1L) == second.id
        }
        winner.notification.actions.single { it.title == app.getString(R.string.dismiss) }.actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(RingingCoordinator.selected!!.vibrate)
        assertFalse(shadowOf(alarmController.get()).isStoppedBySelf)
        val remaining = manager.activeNotifications.single {
            it.notification.contentIntent != null &&
                shadowOf(it.notification.contentIntent).savedIntent.getLongExtra(AlarmHelper.EXTRA_ID, -1L) == first.id
        }
        remaining.notification.actions.single { it.title == app.getString(R.string.dismiss) }.actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(RingingCoordinator.selected)
        assertTrue(shadowOf(alarmController.get()).isStoppedBySelf)
    }

    @Test
    fun aTimerCanTakeOverASilentAlarmAndGiveItBackWhenAnswered() = runBlocking {
        val alarm = Alarm(time = 0L, soundEnabled = false, vibrate = false, timeoutMinutes = 5)
        alarm.id = app.container.alarmRepository.addAlarm(alarm)
        alarmController.get().onStartCommand(Intent(app, AlarmService::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
            .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, System.currentTimeMillis()), 0, 1)
        timerController = Robolectric.buildService(TimerService::class.java).create()
        val timerService = timerController!!.get()
        timerService.onStartCommand(Intent(app, TimerService::class.java)
            .putExtra(TimerService.INITIAL_TIMER_EXTRA_KEY, TimerDescriptor(101,
                TimerSettings(seconds = 1, soundEnabled = false, vibrate = true, timeoutMinutes = 1))), 0, 1)
        timerService.onStartCommand(Intent(app, TimerService::class.java)
            .setAction(TimerService.ACTION_TIMER_EXPIRED).putExtra(TimerService.ID_EXTRA_KEY, 101), 0, 2)

        assertFalse(RingingCoordinator.selected!!.isAlarm)
        assertNotNull(app.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull {
            it.notification.actions?.any { action ->
                shadowOf(action.actionIntent).savedIntent.getLongExtra(AlarmHelper.EXTRA_ID, -1L) == alarm.id
            } == true
        })
        timerService.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, 101))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(RingingCoordinator.selected!!.isAlarm)
        assertFalse(shadowOf(alarmController.get()).isStoppedBySelf)
    }

    @Test
    fun aLaterQuietTimerDoesNotReplaceTheWinnerAndStillExpiresOnItsOwnDeadline() {
        timerController = Robolectric.buildService(TimerService::class.java).create()
        val service = timerController!!.get()
        listOf(
            TimerDescriptor(101, TimerSettings(seconds = 1, label = "Important", soundEnabled = false, vibrate = true, timeoutMinutes = 5)),
            TimerDescriptor(102, TimerSettings(seconds = 1, label = "Quiet", soundEnabled = false, vibrate = false, timeoutMinutes = 1))
        ).forEachIndexed { index, descriptor ->
            service.onStartCommand(Intent(app, TimerService::class.java)
                .putExtra(TimerService.INITIAL_TIMER_EXTRA_KEY, descriptor), 0, index * 2 + 1)
            service.onStartCommand(Intent(app, TimerService::class.java)
                .setAction(TimerService.ACTION_TIMER_EXPIRED)
                .putExtra(TimerService.ID_EXTRA_KEY, descriptor.id), 0, index * 2 + 2)
        }

        assertTrue(RingingCoordinator.selected!!.vibrate)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, 101))
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(RingingCoordinator.selected)
        assertEquals(102, service.timerObjects.single().id)
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.contains("Quiet") == true
        })
    }

    @Test
    fun aLateHiddenReportCannotHideTheResumedAlarmPresentation() = runBlocking {
        val quiet = Alarm(time = 0L, soundEnabled = false, vibrate = false, timeoutMinutes = 5)
        quiet.id = app.container.alarmRepository.addAlarm(quiet)
        val important = Alarm(time = 0L, soundEnabled = false, vibrate = true, timeoutMinutes = 5)
        important.id = app.container.alarmRepository.addAlarm(important)
        val service = alarmController.get()
        service.onStartCommand(Intent(app, AlarmService::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, quiet.id)
            .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, System.currentTimeMillis()), 0, 1)
        val original = requireNotNull(shadowOf(app).nextStartedActivity)
        service.onStartCommand(Intent(app, AlarmService::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, important.id)
            .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, System.currentTimeMillis()), 0, 2)
        val takeover = requireNotNull(shadowOf(app).nextStartedActivity)
        service.sendBroadcast(Intent(AlarmService.ALARM_INTENT_ACTION).putExtras(takeover)
            .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.DISMISS_ACTION).setPackage(app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        val resumed = requireNotNull(shadowOf(app).nextStartedActivity)
        service.sendBroadcast(Intent(AlarmService.ALARM_INTENT_ACTION).putExtras(resumed)
            .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.ALERT_SHOWN_ACTION).setPackage(app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        val notificationId = shadowOf(service).lastForegroundNotificationId
        val manager = app.getSystemService(NotificationManager::class.java)
        assertNull(manager.activeNotifications.single { it.id == notificationId }.notification.fullScreenIntent)

        service.sendBroadcast(Intent(AlarmService.ALARM_INTENT_ACTION).putExtras(original)
            .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.ALERT_HIDDEN_ACTION).setPackage(app.packageName))
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(manager.activeNotifications.single { it.id == notificationId }.notification.fullScreenIntent)
    }

    @Test
    fun aLateHiddenReportCannotHideTheResumedTimerPresentation() {
        timerController = Robolectric.buildService(TimerService::class.java).create()
        val service = timerController!!.get()
        service.onStartCommand(Intent(app, TimerService::class.java)
            .putExtra(TimerService.INITIAL_TIMER_EXTRA_KEY, TimerDescriptor(101,
                TimerSettings(seconds = 1, soundEnabled = false, vibrate = false))), 0, 1)
        service.onStartCommand(Intent(app, TimerService::class.java)
            .setAction(TimerService.ACTION_TIMER_EXPIRED).putExtra(TimerService.ID_EXTRA_KEY, 101), 0, 2)
        val original = requireNotNull(shadowOf(app).nextStartedActivity)
        service.onStartCommand(Intent(app, TimerService::class.java)
            .putExtra(TimerService.INITIAL_TIMER_EXTRA_KEY, TimerDescriptor(102,
                TimerSettings(seconds = 1, soundEnabled = false, vibrate = true))), 0, 3)
        service.onStartCommand(Intent(app, TimerService::class.java)
            .setAction(TimerService.ACTION_TIMER_EXPIRED).putExtra(TimerService.ID_EXTRA_KEY, 102), 0, 4)
        shadowOf(app).nextStartedActivity
        service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, 102))
        shadowOf(Looper.getMainLooper()).idle()
        val resumed = requireNotNull(shadowOf(app).nextStartedActivity)
        service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_ALERT_SHOWN, 101).putExtras(resumed))
        shadowOf(Looper.getMainLooper()).idle()
        val notificationId = shadowOf(service).lastForegroundNotificationId
        val manager = app.getSystemService(NotificationManager::class.java)
        assertNull(manager.activeNotifications.single { it.id == notificationId }.notification.fullScreenIntent)

        service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_ALERT_HIDDEN, 101).putExtras(original))
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(manager.activeNotifications.single { it.id == notificationId }.notification.fullScreenIntent)
    }

    @Test
    fun anOldTimerWindowCannotStopTheCountdownStartedByResettingItsRing() {
        timerController = Robolectric.buildService(TimerService::class.java).create()
        val service = timerController!!.get()
        service.onStartCommand(Intent(app, TimerService::class.java)
            .putExtra(TimerService.INITIAL_TIMER_EXTRA_KEY, TimerDescriptor(101,
                TimerSettings(seconds = 30, soundEnabled = false, vibrate = false))), 0, 1)
        service.onStartCommand(Intent(app, TimerService::class.java)
            .setAction(TimerService.ACTION_TIMER_EXPIRED).putExtra(TimerService.ID_EXTRA_KEY, 101), 0, 2)
        val original = requireNotNull(shadowOf(app).nextStartedActivity)
        service.sendBroadcast(TimerService.updateStateIntent(TimerService.TIMER_RESTART, 101).putExtras(original))
        shadowOf(Looper.getMainLooper()).idle()

        service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, 101).putExtras(original))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(WatchState.RUNNING, service.timerObjects.single().state.value)
        assertTrue(service.timerObjects.single().currentPosition.value > 0)
        service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, 101))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.timerObjects.isEmpty())
    }

    @Test
    fun deliveringTheSamePersonalPhaseAgainKeepsItsSessionAndPresentation() = runBlocking {
        val alarm = Alarm(time = 0L, soundEnabled = false, vibrate = false, timeoutMinutes = 5)
        alarm.id = app.container.alarmRepository.addAlarm(alarm)
        val delivery = Intent(app, AlarmService::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
            .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, System.currentTimeMillis())
        val service = alarmController.get()
        service.onStartCommand(delivery, 0, 1)
        val session = requireNotNull(RingingCoordinator.selected)
        assertNotNull(shadowOf(app).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        service.onStartCommand(Intent(delivery), 0, 2)

        assertSame(session, RingingCoordinator.selected)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun anOldAlarmNotificationCannotAnswerANewerPhaseOfTheSameAlarm() = runBlocking {
        val alarm = Alarm(time = 0L, soundEnabled = false, vibrate = false, timeoutMinutes = 5)
        alarm.id = app.container.alarmRepository.addAlarm(alarm)
        val service = alarmController.get()
        service.onStartCommand(Intent(app, AlarmService::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
            .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, System.currentTimeMillis()), 0, 1)
        val original = requireNotNull(shadowOf(service).lastForegroundNotification)
        val oldAnswer = Intent(shadowOf(original.actions.single {
            it.title == app.getString(R.string.dismiss)
        }.actionIntent).savedIntent)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        service.onStartCommand(Intent(app, AlarmService::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
            .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, System.currentTimeMillis()), 0, 2)
        val nextSession = requireNotNull(RingingCoordinator.selected)

        service.sendBroadcast(oldAnswer)
        shadowOf(Looper.getMainLooper()).idle()

        assertSame(nextSession, RingingCoordinator.selected)
        assertFalse(shadowOf(service).isStoppedBySelf)
        requireNotNull(shadowOf(service).lastForegroundNotification).actions.single {
            it.title == app.getString(R.string.dismiss)
        }.actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(RingingCoordinator.selected)
    }

    @Test
    fun editingATimerKeepsItsCurrentRingSettingsAndDeadlineTogether() {
        timerController = Robolectric.buildService(TimerService::class.java).create()
        val service = timerController!!.get()
        val original = TimerSettings(seconds = 1, soundEnabled = false, vibrate = true, timeoutMinutes = 1)
        service.onStartCommand(Intent(app, TimerService::class.java)
            .putExtra(TimerService.INITIAL_TIMER_EXTRA_KEY, TimerDescriptor(101, original)), 0, 1)
        service.onStartCommand(Intent(app, TimerService::class.java)
            .setAction(TimerService.ACTION_TIMER_EXPIRED).putExtra(TimerService.ID_EXTRA_KEY, 101), 0, 2)
        val session = requireNotNull(RingingCoordinator.selected)

        service.updateTimer(101, original.copy(vibrate = false, timeoutMinutes = 5))
        service.onStartCommand(Intent(app, TimerService::class.java)
            .putExtra(TimerService.INITIAL_TIMER_EXTRA_KEY, TimerDescriptor(102,
                TimerSettings(seconds = 1, soundEnabled = false, vibrate = false, timeoutMinutes = 5))), 0, 3)
        service.onStartCommand(Intent(app, TimerService::class.java)
            .setAction(TimerService.ACTION_TIMER_EXPIRED).putExtra(TimerService.ID_EXTRA_KEY, 102), 0, 4)

        assertSame(session, RingingCoordinator.selected)
        assertTrue(session.vibrate)
        assertEquals(60_000L, session.timeoutMillis)
        assertFalse(service.timerObjects.single { it.id == 101 }.vibrate)
        assertEquals(5, service.timerObjects.single { it.id == 101 }.timeoutMinutes)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertFalse(requireNotNull(RingingCoordinator.selected).vibrate)
        assertEquals(300_000L, RingingCoordinator.selected?.timeoutMillis)
    }

    @Test
    fun volumeSnoozeIsAppliedByTheServiceToThePhaseShownByTheActivity() = runBlocking {
        val alarm = Alarm(time = 0L, soundEnabled = false, vibrate = false, snoozeMinutes = 3)
        alarm.id = app.container.alarmRepository.addAlarm(alarm)
        val triggerTime = System.currentTimeMillis() / 60_000L * 60_000L
        alarmController.get().onStartCommand(Intent(app, AlarmService::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, triggerTime), 0, 1)
        val alert = requireNotNull(shadowOf(app).nextStartedActivity)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val activityController = Robolectric.buildActivity(AlarmActivity::class.java, alert).create()
        try {
            assertTrue(activityController.get().onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, null))
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(triggerTime + 3 * 60_000L, app.container.alarmRepository.getAlarmById(alarm.id)?.snoozedUntil)
            assertNull(RingingCoordinator.selected)
            assertTrue(shadowOf(alarmController.get()).isStoppedBySelf)
        } finally {
            activityController.destroy()
        }
    }

    @Test
    fun aSuppressedAlarmStillSchedulesItsMissedRestart() = runBlocking {
        val quiet = Alarm(time = 0L, soundEnabled = false, vibrate = false, timeoutMinutes = 1,
            missedRestartEnabled = true, missedRestartMinutes = 10)
        quiet.id = app.container.alarmRepository.addAlarm(quiet)
        val important = Alarm(time = 0L, soundEnabled = false, vibrate = true, timeoutMinutes = 5)
        important.id = app.container.alarmRepository.addAlarm(important)
        val triggerTime = System.currentTimeMillis()
        listOf(quiet, important).forEachIndexed { index, alarm ->
            alarmController.get().onStartCommand(Intent(app, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, alarm.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, triggerTime), 0, index + 1)
        }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))

        assertEquals(triggerTime + 11 * 60_000L, app.container.alarmRepository.getAlarmById(quiet.id)?.snoozedUntil)
        assertTrue(requireNotNull(RingingCoordinator.selected).vibrate)
        assertFalse(shadowOf(alarmController.get()).isStoppedBySelf)
    }

    @Test
    fun sameKindTakeoverKeepsTheWindowAliveUntilTheServiceEndsItsLastRing() = runBlocking {
        Preferences.edit { putString(Preferences.volumeButtonActionKey, VolumeButtonAction.DISMISS.name) }
        val first = Alarm(time = 0L, soundEnabled = false, vibrate = false, timeoutMinutes = 5)
        first.id = app.container.alarmRepository.addAlarm(first)
        val second = Alarm(time = 0L, soundEnabled = false, vibrate = true, timeoutMinutes = 5)
        second.id = app.container.alarmRepository.addAlarm(second)
        val service = alarmController.get()
        val triggerTime = System.currentTimeMillis()
        service.onStartCommand(Intent(app, AlarmService::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, first.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, triggerTime), 0, 1)
        val initial = requireNotNull(shadowOf(app).nextStartedActivity)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val activityController = Robolectric.buildActivity(AlarmActivity::class.java, initial).create()
        try {
            service.onStartCommand(Intent(app, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, second.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, triggerTime), 0, 2)
            val takeover = requireNotNull(shadowOf(app).nextStartedActivity)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(activityController.get().isFinishing)

            activityController.newIntent(takeover)
            assertEquals(second.id, activityController.get().intent.getLongExtra(AlarmHelper.EXTRA_ID, -1L))
            activityController.get().onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, null)
            assertFalse(activityController.get().isFinishing)
            shadowOf(Looper.getMainLooper()).idle()
            activityController.newIntent(requireNotNull(shadowOf(app).nextStartedActivity))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(first.id, activityController.get().intent.getLongExtra(AlarmHelper.EXTRA_ID, -1L))
            assertFalse(activityController.get().isFinishing)

            activityController.get().onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, null)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activityController.get().isFinishing)
            assertNull(RingingCoordinator.selected)
        } finally {
            activityController.destroy()
        }
    }
}
