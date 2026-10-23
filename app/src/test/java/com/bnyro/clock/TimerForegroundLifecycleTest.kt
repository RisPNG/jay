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
import com.bnyro.clock.util.services.TimerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TimerForegroundLifecycleTest {
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
