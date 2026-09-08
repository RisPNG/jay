package com.bnyro.clock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.util.AlarmHelper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AlarmNotificationTest {
    @Test
    fun bothCancellationPathsRemoveUpcomingNotifications() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("test", "Test", NotificationManager.IMPORTANCE_LOW))
        val alarm = Alarm(id = 7, time = 0)
        val notification = NotificationCompat.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).build()
        manager.notify(7 + AlarmHelper.PRE_ALARM_ID_OFFSET, notification)
        AlarmHelper.cancel(context, alarm)
        assertTrue(manager.activeNotifications.isEmpty())
        manager.notify(7 + AlarmHelper.PRE_ALARM_ID_OFFSET, notification)
        AlarmHelper.cancel(context, 7L)
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
