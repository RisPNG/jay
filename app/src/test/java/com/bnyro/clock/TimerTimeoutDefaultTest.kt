package com.bnyro.clock

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.TimerDescriptor
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.util.Preferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TimerTimeoutDefaultTest {
    @Before
    fun setUp() {
        Preferences.init(ApplicationProvider.getApplicationContext())
        Preferences.instance.edit().clear().commit()
    }

    @After
    fun tearDown() {
        Preferences.instance.edit().clear().commit()
    }

    @Test
    fun newAndLegacySavedTimersUseFiveMinuteRingingTimeouts() {
        assertEquals(5, TimerDescriptor(1, TimerSettings(seconds = 300)).asScheduledObject().timeoutMinutes)
        Preferences.edit { putString(Preferences.savedTimersKey, """[{"seconds":300}]""") }

        val restored = TimerSettings.getSavedTimers().single()

        assertEquals(5, restored.timeoutMinutes)
        assertEquals(5, TimerDescriptor(restored.id, restored).asScheduledObject().timeoutMinutes)
    }

    @Test
    fun savedCustomTimeoutsSurviveDecodingAndSaving() {
        Preferences.edit { putString(Preferences.savedTimersKey, """[{"seconds":300,"timeoutMinutes":10},{"seconds":600,"timeoutMinutes":25}]""") }
        val restored = TimerSettings.getSavedTimers()

        TimerSettings.setSavedTimers(restored)

        assertEquals(listOf(10, 25), TimerSettings.getSavedTimers().map { it.timeoutMinutes })
        assertEquals(listOf(10, 25), restored.map { TimerDescriptor(it.id, it).asScheduledObject().timeoutMinutes })
    }

}
