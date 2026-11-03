package com.bnyro.clock

import com.bnyro.clock.util.RingingCoordinator
import com.bnyro.clock.util.RingingSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RingingCoordinatorTest {
    private val sessions = mutableListOf<TestRing>()

    private class TestRing(
        override var soundEnabled: Boolean = true,
        override var vibrate: Boolean = true,
        override var timeoutMillis: Long = 300_000L,
        override val isAlarm: Boolean = true
    ) : RingingSession {
        var playing = false
        var selections = 0

        override fun onSelected() {
            playing = true
            selections++
        }

        override fun onSuppressed() {
            playing = false
        }
    }

    @After
    fun tearDown() {
        sessions.forEach { RingingCoordinator.stop(it) }
        assertNull(RingingCoordinator.selected)
    }

    @Test
    fun soundThenVibrationThenDurationDetermineTheWinnerAndSuppressedRingsResume() {
        val silent = TestRing(soundEnabled = false, timeoutMillis = 900_000L)
        val audible = TestRing(vibrate = false)
        val vibrating = TestRing()
        val longer = TestRing(timeoutMillis = 600_000L)
        sessions.addAll(listOf(silent, audible, vibrating, longer))
        sessions.forEach { RingingCoordinator.start(it) }

        assertSame(longer, RingingCoordinator.selected)
        assertTrue(longer.playing)
        assertFalse(silent.playing)
        assertFalse(audible.playing)
        assertFalse(vibrating.playing)

        RingingCoordinator.stop(longer)
        assertSame(vibrating, RingingCoordinator.selected)
        RingingCoordinator.stop(vibrating)
        assertSame(audible, RingingCoordinator.selected)
        RingingCoordinator.stop(audible)
        assertSame(silent, RingingCoordinator.selected)
        assertEquals(2, silent.selections)
    }

    @Test
    fun alarmsWinEqualSettingsWhileAnAudibleTimerWinsOverASilentAlarm() {
        val timer = TestRing(isAlarm = false)
        val alarm = TestRing()
        sessions.addAll(listOf(timer, alarm))
        RingingCoordinator.start(timer)
        RingingCoordinator.start(alarm)
        assertSame(alarm, RingingCoordinator.selected)

        alarm.soundEnabled = false
        RingingCoordinator.start(alarm)
        assertSame(timer, RingingCoordinator.selected)
        assertFalse(alarm.playing)
    }

    @Test
    fun equalRingsKeepTheirArrivalOrderAndRemovingASuppressedRingDoesNotRestartTheWinner() {
        val first = TestRing()
        val second = TestRing()
        sessions.addAll(listOf(first, second))
        RingingCoordinator.start(first)
        RingingCoordinator.start(second)
        assertSame(first, RingingCoordinator.selected)
        assertEquals(1, first.selections)

        RingingCoordinator.stop(second)
        assertSame(first, RingingCoordinator.selected)
        assertEquals(1, first.selections)
    }

    @Test
    fun endingSeveralRingsTogetherDoesNotActivateOneThatIsAlsoEnding() {
        val first = TestRing()
        val second = TestRing()
        sessions.addAll(listOf(first, second))
        RingingCoordinator.start(first)
        RingingCoordinator.start(second)

        RingingCoordinator.stop(first, second)

        assertNull(RingingCoordinator.selected)
        assertFalse(first.playing)
        assertEquals(0, second.selections)
    }
}
