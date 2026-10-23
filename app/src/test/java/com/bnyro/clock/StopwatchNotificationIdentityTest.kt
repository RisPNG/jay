package com.bnyro.clock

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.BackupTimer
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.domain.model.WatchState
import com.bnyro.clock.util.NotificationHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.StopwatchService
import com.bnyro.clock.util.services.TimerService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StopwatchNotificationIdentityTest {
    @Test
    fun destroyingAnIdleBoundStopwatchKeepsTheImportedTimerResumeNotification() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        Preferences.init(application)
        Preferences.instance.edit().clear().commit()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createStaticNotificationChannels(application)
        val manager = application.getSystemService(NotificationManager::class.java)
        val timerController = Robolectric.buildService(TimerService::class.java).create()
        val timerService = timerController.get()
        try {
            val stopwatchController = Robolectric.buildService(StopwatchService::class.java).create().bind()
            try {
                timerService.restoreTimers(listOf(BackupTimer(TimerSettings(seconds = 480, label = "Imported timer"), 480_000)))
                assertEquals(1, manager.activeNotifications.single().id)
                assertEquals(WatchState.IDLE, stopwatchController.get().state)
            } finally {
                stopwatchController.unbind().destroy()
            }

            val notification = manager.activeNotifications.single()
            assertEquals(1, notification.id)
            assertEquals(NotificationHelper.TIMER_CHANNEL, notification.notification.channelId)
            val resume = notification.notification.actions.first()
            assertEquals(application.getString(R.string.resume), resume.title.toString())
            resume.actionIntent.send()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(WatchState.RUNNING, timerService.timerObjects.single().state.value)
        } finally {
            timerService.timerObjects.toList().forEach { timer ->
                timerService.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, timer.id))
            }
            shadowOf(Looper.getMainLooper()).idle()
            timerController.destroy()
            Preferences.instance.edit().clear().commit()
        }
    }
}
