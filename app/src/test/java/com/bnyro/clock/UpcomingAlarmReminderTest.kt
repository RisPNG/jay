package com.bnyro.clock

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.receivers.AlarmReceiver
import com.bnyro.clock.util.receivers.PreAlarmReceiver
import java.time.Instant
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = App::class)
class UpcomingAlarmReminderTest {
    private lateinit var context: App
    private lateinit var alarm: Alarm

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        assertTrue(SystemClock.setCurrentTimeMillis(Instant.parse("2026-10-24T00:00:00Z").toEpochMilli()))
        context.getSharedPreferences(Preferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        Preferences.init(context.createDeviceProtectedStorageContext())
        Preferences.instance.edit().clear().commit()
        alarm = Alarm(
            id = 41,
            time = 12 * 3_600_000L,
            enabled = true,
            startDate = LocalDate.now().plusDays(1).toEpochDay()
        )
    }

    @After
    fun tearDown() {
        AlarmHelper.cancel(context, alarm)
        context.getSharedPreferences(Preferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        Preferences.instance.edit().clear().commit()
    }

    @Test
    fun changingTheReminderLeadTimeKeepsTheScheduledAlarm() {
        context.getSharedPreferences(Preferences.FILE_NAME, Context.MODE_PRIVATE).edit()
            .putInt(Preferences.upcomingAlarmDuration, 240).commit()
        Preferences.edit { putInt(Preferences.upcomingAlarmDuration, 120) }
        AlarmHelper.enqueue(context, alarm)
        val manager = context.getSystemService(AlarmManager::class.java)
        val ringing = shadowOf(manager).scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.component?.className == AlarmReceiver::class.java.name
        }

        Preferences.edit { putInt(Preferences.upcomingAlarmDuration, 60) }
        AlarmHelper.reschedulePreAlarm(context, alarm, ringing.triggerAtTime)

        val scheduled = shadowOf(manager).scheduledAlarms
        val remainingRing = scheduled.single {
            shadowOf(it.operation).savedIntent.component?.className == AlarmReceiver::class.java.name
        }
        val reminder = scheduled.single {
            shadowOf(it.operation).savedIntent.component?.className == PreAlarmReceiver::class.java.name
        }
        assertEquals(ringing.operation, remainingRing.operation)
        assertEquals(ringing.triggerAtTime, remainingRing.triggerAtTime)
        assertEquals(ringing.triggerAtTime, manager.nextAlarmClock.triggerTime)
        assertEquals(ringing.triggerAtTime - 60 * 60_000L, reminder.triggerAtTime)
        assertEquals(60 * 60_000L, AlarmHelper.getPreAlarmDelayMillis())
    }

    @Test
    fun migratingPreferencesFillsMissingValuesWithoutReplacingProtectedChoices() {
        val credentialPreferences = context.getSharedPreferences(Preferences.FILE_NAME, Context.MODE_PRIVATE)
        credentialPreferences.edit()
            .putString(Preferences.themeKey, "LIGHT")
            .putInt(Preferences.upcomingAlarmDuration, 45)
            .commit()
        Preferences.edit { putString(Preferences.themeKey, "DARK") }

        context.migrateToDeviceProtectedStorage()

        assertEquals("DARK", Preferences.instance.getString(Preferences.themeKey, null))
        assertEquals(45, Preferences.instance.getInt(Preferences.upcomingAlarmDuration, -1))
        assertTrue(context.getSharedPreferences(Preferences.FILE_NAME, Context.MODE_PRIVATE).all.isEmpty())
    }
}
