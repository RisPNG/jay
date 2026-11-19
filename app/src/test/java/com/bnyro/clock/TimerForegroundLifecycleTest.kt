package com.bnyro.clock

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.BackupTimer
import com.bnyro.clock.domain.model.TimerDescriptor
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.domain.model.WatchState
import com.bnyro.clock.presentation.screens.timer.TimerAlertActivity
import com.bnyro.clock.util.NotificationHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.RingingCoordinator
import com.bnyro.clock.util.services.TimerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TimerForegroundLifecycleTest {
    @Test
    fun shortCountdownsRingAtTheirDeadlineAndIgnoreDelayedAlarmDelivery() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        Preferences.init(application)
        Preferences.instance.edit().clear()
            .putBoolean(Preferences.timerFullScreenAlertKey, false).commit()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createStaticNotificationChannels(application)
        val controller = Robolectric.buildService(TimerService::class.java).create()
        val service = controller.get()
        val updateState = TimerService::class.java.getDeclaredMethod("updateState").apply {
            isAccessible = true
        }
        try {
            for (seconds in 1..4) {
                service.onStartCommand(
                    Intent(application, TimerService::class.java).putExtra(
                        TimerService.INITIAL_TIMER_EXTRA_KEY,
                        TimerDescriptor(seconds, TimerSettings(seconds = seconds, soundEnabled = false, vibrate = false))
                    ), 0, seconds
                )
                val timer = service.timerObjects.single()
                val expiresAt = timer.expiresAt
                val remaining = timer.elapsedRealtimeDeadline - SystemClock.elapsedRealtime()
                ShadowSystemClock.advanceBy(Duration.ofMillis(remaining - 1))
                updateState.invoke(service)
                assertEquals(WatchState.RUNNING, timer.state.value)
                assertNull(RingingCoordinator.selected)

                ShadowSystemClock.advanceBy(Duration.ofMillis(1))
                updateState.invoke(service)
                assertEquals(WatchState.PAUSED, timer.state.value)
                assertEquals(0, timer.currentPosition.value)
                val ring = RingingCoordinator.selected
                assertNotNull(ring)
                val notification = shadowOf(service).lastForegroundNotificationId

                service.onStartCommand(
                    Intent(application, TimerService::class.java).setAction(TimerService.ACTION_TIMER_EXPIRED)
                        .putExtra(TimerService.ID_EXTRA_KEY, timer.id)
                        .putExtra(TimerService.EXPIRES_AT_EXTRA_KEY, expiresAt), 0, seconds + 4
                )
                assertSame(ring, RingingCoordinator.selected)
                assertEquals(notification, shadowOf(service).lastForegroundNotificationId)
                assertEquals(1, service.timerObjects.size)

                service.sendBroadcast(TimerService.updateStateIntent(TimerService.TIMER_RESTART, timer.id))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(WatchState.RUNNING, timer.state.value)
                assertNull(RingingCoordinator.selected)
                service.onStartCommand(
                    Intent(application, TimerService::class.java).setAction(TimerService.ACTION_TIMER_EXPIRED)
                        .putExtra(TimerService.ID_EXTRA_KEY, timer.id)
                        .putExtra(TimerService.EXPIRES_AT_EXTRA_KEY, expiresAt), 0, seconds + 8
                )
                assertEquals(WatchState.RUNNING, timer.state.value)
                assertNull(RingingCoordinator.selected)

                service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, timer.id))
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(service.timerObjects.isEmpty())
            }
        } finally {
            service.timerObjects.toList().forEach { timer ->
                service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, timer.id))
            }
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
            Preferences.instance.edit().clear().commit()
        }
    }

    @Test
    fun ringingTimerKeepsItsHeadsUpEligibilityThroughElapsedTimeUpdates() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        Preferences.init(application)
        Preferences.instance.edit().clear()
            .putBoolean(Preferences.timerFullScreenAlertKey, false).commit()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createStaticNotificationChannels(application)
        val controller = Robolectric.buildService(TimerService::class.java).create()
        val service = controller.get()
        val updateState = TimerService::class.java.getDeclaredMethod("updateState").apply {
            isAccessible = true
        }
        try {
            val settings = TimerSettings(seconds = 1, soundEnabled = false, vibrate = false, timeoutMinutes = 1)
            service.onStartCommand(
                Intent(application, TimerService::class.java).putExtra(
                    TimerService.INITIAL_TIMER_EXTRA_KEY, TimerDescriptor(1, settings)
                ), 0, 1
            )
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            updateState.invoke(service)

            val notificationId = shadowOf(service).lastForegroundNotificationId
            val announcement = requireNotNull(shadowOf(service).lastForegroundNotification)
            assertEquals(NotificationHelper.TIMER_FINISHED_CHANNEL, announcement.channelId)
            assertEquals(Notification.GROUP_ALERT_ALL, announcement.groupAlertBehavior)
            assertFalse(announcement.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertNull(announcement.fullScreenIntent)

            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            updateState.invoke(service)

            val manager = application.getSystemService(NotificationManager::class.java)
            val elapsedUpdate = manager.activeNotifications.single { it.id == notificationId }.notification
            assertEquals(Notification.GROUP_ALERT_ALL, elapsedUpdate.groupAlertBehavior)
            assertTrue(elapsedUpdate.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertNull(elapsedUpdate.fullScreenIntent)

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))

            val finished = manager.activeNotifications.single { it.id == notificationId }.notification
            assertNotNull(finished.group)
            assertEquals(Notification.GROUP_ALERT_SUMMARY, finished.groupAlertBehavior)
            assertNull(finished.fullScreenIntent)
        } finally {
            service.timerObjects.toList().forEach { timer ->
                service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, timer.id))
            }
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
            Preferences.instance.edit().clear().commit()
        }
    }

    @Test
    fun countdownExpiryCreatesEachRepeatOnceWhileTheTimerListChanges() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        Preferences.init(application)
        Preferences.instance.edit().clear()
            .putBoolean(Preferences.timerFullScreenAlertKey, false).commit()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createStaticNotificationChannels(application)
        val controller = Robolectric.buildService(TimerService::class.java).create()
        val service = controller.get()
        val updateState = TimerService::class.java.getDeclaredMethod("updateState").apply {
            isAccessible = true
        }
        try {
            service.onStartCommand(
                Intent(application, TimerService::class.java).putExtra(
                    TimerService.INITIAL_TIMER_EXTRA_KEY,
                    TimerDescriptor(1, TimerSettings(seconds = 2, soundEnabled = false, vibrate = false, repeatEnabled = true, repeatCount = 1))
                ), 0, 1
            )
            val first = service.timerObjects.single()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            updateState.invoke(service)

            val successor = service.timerObjects.single { it !== first }
            assertEquals(WatchState.PAUSED, first.state.value)
            assertEquals(WatchState.RUNNING, successor.state.value)
            assertEquals(1, successor.repeatIndex)
            assertEquals(first.expiresAt + 2000L, successor.expiresAt)
            val ring = RingingCoordinator.selected
            assertNotNull(ring)

            service.onStartCommand(
                Intent(application, TimerService::class.java).setAction(TimerService.ACTION_TIMER_EXPIRED)
                    .putExtra(TimerService.ID_EXTRA_KEY, first.id)
                    .putExtra(TimerService.EXPIRES_AT_EXTRA_KEY, first.expiresAt), 0, 2
            )
            assertSame(ring, RingingCoordinator.selected)
            assertEquals(2, service.timerObjects.size)

            val remaining = successor.elapsedRealtimeDeadline - SystemClock.elapsedRealtime()
            assertTrue(remaining > 0)
            ShadowSystemClock.advanceBy(Duration.ofMillis(remaining - 1))
            updateState.invoke(service)
            assertEquals(WatchState.RUNNING, successor.state.value)
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            updateState.invoke(service)
            assertEquals(WatchState.PAUSED, successor.state.value)
            assertEquals(0, successor.currentPosition.value)
            assertEquals(2, service.timerObjects.size)
            updateState.invoke(service)
            assertEquals(2, service.timerObjects.size)
        } finally {
            service.timerObjects.toList().forEach { timer ->
                service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, timer.id))
            }
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
            Preferences.instance.edit().clear().commit()
        }
    }

    @Test
    fun openingAnAlertTargetsOnlyTheRequestedLiveRing() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        Preferences.init(application)
        Preferences.instance.edit().clear()
            .putBoolean(Preferences.timerFullScreenAlertKey, false).commit()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createStaticNotificationChannels(application)
        val controller = Robolectric.buildService(TimerService::class.java).create()
        val service = controller.get()
        val updateState = TimerService::class.java.getDeclaredMethod("updateState").apply {
            isAccessible = true
        }
        try {
            val firstSettings = TimerSettings(seconds = 1, soundEnabled = false, vibrate = false, timeoutMinutes = 5)
            val secondSettings = firstSettings.copy(seconds = 2, timeoutMinutes = 1)
            listOf(TimerDescriptor(1, firstSettings), TimerDescriptor(2, secondSettings)).forEach { descriptor ->
                service.onStartCommand(
                    Intent(application, TimerService::class.java).putExtra(
                        TimerService.INITIAL_TIMER_EXTRA_KEY, descriptor
                    ), 0, descriptor.id
                )
            }
            val (first, second) = service.timerObjects
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            updateState.invoke(service)
            val firstRing = RingingCoordinator.selected
            assertNotNull(firstRing)

            service.openRingingTimer(second.id)
            assertNull(shadowOf(application).nextStartedActivity)

            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            updateState.invoke(service)
            assertTrue(first.isRinging.value)
            assertTrue(second.isRinging.value)

            service.openRingingTimer(second.id)

            val alert = requireNotNull(shadowOf(application).nextStartedActivity)
            assertEquals(TimerAlertActivity::class.java.name, alert.component?.className)
            assertEquals(second.id, alert.getIntExtra(TimerService.ID_EXTRA_KEY, 0))
            assertSame(firstRing, RingingCoordinator.selected)

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
            service.openRingingTimer(second.id)

            assertTrue(second in service.timerObjects)
            assertFalse(second.isRinging.value)
            assertNull(shadowOf(application).nextStartedActivity)
            assertSame(firstRing, RingingCoordinator.selected)
        } finally {
            service.timerObjects.toList().forEach { timer ->
                service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, timer.id))
            }
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
            Preferences.instance.edit().clear().commit()
        }
    }

    @Test
    fun resumingAnImportedTimerFromItsNotificationStartsForegroundService() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        Preferences.init(application)
        Preferences.instance.edit().clear().commit()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createStaticNotificationChannels(application)
        val controller = Robolectric.buildService(TimerService::class.java).create()
        val service = controller.get()
        try {
            service.restoreTimers(listOf(BackupTimer(TimerSettings(seconds = 900, label = "Tea"), 600_000)))
            val timer = service.timerObjects.single()
            assertEquals(WatchState.PAUSED, timer.state.value)
            assertNull(shadowOf(service).lastForegroundNotification)

            val manager = application.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.single().notification.actions.first().actionIntent.send()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(WatchState.RUNNING, timer.state.value)
            assertEquals(timer.id, shadowOf(service).lastForegroundNotificationId)
            assertNotNull(shadowOf(service).lastForegroundNotification)
        } finally {
            service.timerObjects.toList().forEach { timer ->
                service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, timer.id))
            }
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
            Preferences.instance.edit().clear().commit()
        }
    }
}
