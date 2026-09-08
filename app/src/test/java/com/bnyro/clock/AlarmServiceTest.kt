package com.bnyro.clock

import android.content.Intent
import android.os.Looper
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.AlarmService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmServiceTest {
    @Test
    fun replacingAnAlarmRestartsItsTimeoutAndKeepsATapIntent(): Unit = runBlocking {
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        val service = controller.get()
        Preferences.instance.edit().putInt(Preferences.alarmTimeoutMinutesKey, 1).commit()
        val repository = (service.application as App).container.alarmRepository
        val first = repository.addAlarm(Alarm(time = 0, label = "First", soundEnabled = false, vibrate = false))
        val second = repository.addAlarm(Alarm(time = 0, label = "Second", soundEnabled = false, vibrate = false))
        service.onStartCommand(Intent(service, AlarmService::class.java).putExtra(AlarmHelper.EXTRA_ID, first), 0, 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        service.onStartCommand(Intent(service, AlarmService::class.java).putExtra(AlarmHelper.EXTRA_ID, second), 0, 2)
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull(notification.contentIntent)
        assertEquals(second, shadowOf(notification.contentIntent).savedIntent.getLongExtra(AlarmHelper.EXTRA_ID, -1))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(35))
        assertFalse(shadowOf(service).isStoppedBySelf)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25))
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }
}
