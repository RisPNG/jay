package com.bnyro.clock

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.TimerDescriptor
import com.bnyro.clock.domain.model.TimerObject
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.domain.model.WatchState
import com.bnyro.clock.util.NotificationHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.TimerService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TimerAddedTimeTest {
    private lateinit var application: Application
    private lateinit var controller: ServiceController<TimerService>
    private lateinit var service: TimerService
    private lateinit var timer: TimerObject

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        Preferences.init(application)
        Preferences.instance.edit()
            .clear()
            .putInt(Preferences.timerIncrementSecondsKey, 120)
            .putBoolean(Preferences.timerFullScreenAlertKey, false)
            .commit()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createStaticNotificationChannels(application)
        controller = Robolectric.buildService(TimerService::class.java).create()
        service = controller.get()
        service.onStartCommand(
            Intent(application, TimerService::class.java).putExtra(
                TimerService.INITIAL_TIMER_EXTRA_KEY,
                TimerDescriptor(
                    id = 1,
                    settings = TimerSettings(
                        seconds = 900, label = "Tea", soundEnabled = false,
                        vibrate = false, incrementSeconds = 90
                    )
                )
            ),
            0,
            1
        )
        timer = service.timerObjects.single()
    }

    @After
    fun tearDown() {
        service.timerObjects.toList().forEach {
            service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, it.id))
        }
        shadowOf(Looper.getMainLooper()).idle()
        controller.destroy()
        Preferences.instance.edit().clear().commit()
    }

    @Test
    fun selectedAddedTimeOverridesTheTimerSettingForThisAction() {
        service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_PAUSE_RESUME, timer.id))
        shadowOf(Looper.getMainLooper()).idle()
        val remainingMillis = timer.currentPosition.value
        service.sendBroadcast(
            TimerService.updateStateIntent(TimerService.ACTION_ADD_TIME, timer.id)
                .putExtra(TimerService.INCREMENT_EXTRA_KEY, 15)
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(remainingMillis + 15_000, timer.currentPosition.value)
        assertEquals(915_000, timer.initialPosition.value)
        assertEquals(90, timer.incrementSeconds)
        assertEquals(WatchState.PAUSED, timer.state.value)
    }

    @Test
    fun notificationAddedTimeUsesTheTimerSettingWithoutAnOverride() {
        service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_PAUSE_RESUME, timer.id))
        shadowOf(Looper.getMainLooper()).idle()
        val remainingMillis = timer.currentPosition.value
        val manager = application.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.single { it.id == timer.id }
            .notification.actions[1].actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(remainingMillis + 90_000, timer.currentPosition.value)
        assertEquals(990_000, timer.initialPosition.value)
        assertEquals(90, timer.incrementSeconds)
    }

    @Test
    fun addedTimeWithoutATimerSettingUsesTheGlobalPreference() {
        service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_PAUSE_RESUME, timer.id))
        shadowOf(Looper.getMainLooper()).idle()
        val remainingMillis = timer.currentPosition.value
        service.updateTimer(timer.id, timer.settings.copy(incrementSeconds = null))
        service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_ADD_TIME, timer.id))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(remainingMillis + 120_000, timer.currentPosition.value)
        assertEquals(1_020_000, timer.initialPosition.value)
        assertNull(timer.incrementSeconds)
    }

    @Test
    fun selectedAddedTimeRestartsAFinishedTimerWithTheSelectedDuration() {
        service.onStartCommand(
            Intent(application, TimerService::class.java)
                .setAction(TimerService.ACTION_TIMER_EXPIRED)
                .putExtra(TimerService.ID_EXTRA_KEY, timer.id),
            0,
            2
        )
        assertEquals(0, timer.currentPosition.value)
        assertEquals(WatchState.PAUSED, timer.state.value)

        service.sendBroadcast(
            TimerService.updateStateIntent(TimerService.ACTION_ADD_TIME, timer.id)
                .putExtra(TimerService.INCREMENT_EXTRA_KEY, 15)
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(15, timer.secondsLeft)
        assertEquals(15_000, timer.initialPosition.value)
        assertEquals(90, timer.incrementSeconds)
        assertEquals(WatchState.RUNNING, timer.state.value)
        assertEquals(timer.id, shadowOf(service).lastForegroundNotificationId)
    }
}
