package com.bnyro.clock

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.BackupTimer
import com.bnyro.clock.domain.model.TimerDescriptor
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.domain.model.WatchState
import com.bnyro.clock.util.NotificationHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.RingingCoordinator
import com.bnyro.clock.util.services.TimerService
import org.junit.Assert.assertEquals
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
                ShadowSystemClock.advanceBy(Duration.ofMillis(seconds * 1000L - 1))
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
