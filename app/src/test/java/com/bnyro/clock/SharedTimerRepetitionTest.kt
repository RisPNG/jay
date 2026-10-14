package com.bnyro.clock

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.TimerObject
import com.bnyro.clock.domain.model.WatchState
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.TimerService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedTimerRepetitionTest {
    private lateinit var controller: ServiceController<TimerService>
    private lateinit var service: TimerService

    @Before
    fun setUp() {
        Preferences.init(ApplicationProvider.getApplicationContext<Context>())
        Preferences.instance.edit().clear().putBoolean(Preferences.timerFullScreenAlertKey, false).commit()
        controller = Robolectric.buildService(TimerService::class.java).create()
        service = controller.get()
    }

    @After
    fun tearDown() {
        controller.destroy()
        Preferences.instance.edit().clear().commit()
    }

    @Test
    fun successorRetainsSharedIdentityAndUsesIndependentMutableState() {
        sync(expiry = System.currentTimeMillis() + 30_000)
        val first = service.timerObjects.single()
        expire(first)
        val second = service.timerObjects.single { it !== first }
        assertEquals("timer", second.sharedTimerId)
        assertEquals("group", second.sharedGroupId)
        assertEquals("member", second.sharedMembershipId)
        assertEquals("run", second.sharedRunId)
        assertEquals(first.expiresAt + 60_000, second.expiresAt)
        assertEquals(1, second.repeatIndex)
        assertEquals(7, second.timeoutMinutes)
        assertEquals(WatchState.PAUSED, first.state.value)
        assertEquals(WatchState.RUNNING, second.state.value)
        second.label.value = "Changed"
        assertEquals("Tea", first.label.value)
    }

    @Test
    fun synchronizingDuringTheFirstRingKeepsItAndReusesTheSuccessor() {
        val expiry = System.currentTimeMillis() + 30_000
        sync(expiry = expiry)
        val first = service.timerObjects.single()
        expire(first)
        val second = service.timerObjects.single { it !== first }
        sync(expiry = expiry - 31_000)
        sync(expiry = expiry - 31_000)
        assertEquals(2, service.timerObjects.size)
        assertTrue(service.timerObjects.contains(first))
        assertTrue(service.timerObjects.contains(second))
        assertEquals(0, first.currentPosition.value)
    }

    @Test
    fun answeredOccurrenceRemovesOnlyItsCopy() {
        val expiry = System.currentTimeMillis() + 30_000
        sync(expiry = expiry)
        val first = service.timerObjects.single()
        expire(first)
        val second = service.timerObjects.single { it !== first }
        sync(expiry = expiry - 31_000, answered = intArrayOf(0))
        assertEquals(listOf(second), service.timerObjects)
        assertEquals(1, second.repeatIndex)
    }

    @Test
    fun aNewGenerationInvalidatesOldRingsAndCountingCopies() {
        sync(expiry = System.currentTimeMillis() + 30_000)
        expire(service.timerObjects.single())
        sync(expiry = System.currentTimeMillis() + 90_000, run = "next-run", baseIndex = 1)
        val updated = service.timerObjects.single()
        assertEquals("next-run", updated.sharedRunId)
        assertEquals(1, updated.repeatIndex)
        assertEquals(60_000, updated.repeatDurationMillis)
        assertEquals(2, updated.repeatCount)
    }

    @Test
    fun aNewMembershipDoesNotInheritPriorOccurrenceAnswers() {
        sync(expiry = System.currentTimeMillis() + 30_000, answered = intArrayOf(0))
        assertEquals(1, service.timerObjects.single().repeatIndex)
        sync(expiry = System.currentTimeMillis() + 30_000, membership = "rejoined")
        val updated = service.timerObjects.single()
        assertEquals("rejoined", updated.sharedMembershipId)
        assertEquals(0, updated.repeatIndex)
    }

    @Test
    fun lateReceiptSkipsMissedCopiesAndRetainsFiniteBudget() {
        val expiry = System.currentTimeMillis() - 61_000
        sync(expiry = expiry)
        val timer = service.timerObjects.single()
        assertEquals(2, timer.repeatIndex)
        assertEquals(expiry + 120_000, timer.expiresAt)
        assertTrue(timer.currentPosition.value in 1..60_000)
        expire(timer)
        assertEquals(listOf(timer), service.timerObjects)
        assertFalse(timer.hasSuccessor)
    }

    @Test
    fun exhaustedFiniteFirstReceiptDoesNotCreateAnOverdueRing() {
        sync(expiry = System.currentTimeMillis() - 121_000)
        assertTrue(service.timerObjects.isEmpty())
    }

    @Test
    fun indefiniteSeriesSurvivesOldBaseExpiry() {
        val expiry = System.currentTimeMillis() - 3_600_000
        sync(expiry = expiry, repeatCount = 0)
        val timer = service.timerObjects.single()
        assertEquals(61, timer.repeatIndex)
        assertEquals(expiry + 61 * 60_000, timer.expiresAt)
    }

    @Test
    fun suppressedFutureIndexIsSkippedByTheSameExpiryScheduler() {
        val expiry = System.currentTimeMillis() + 30_000
        sync(expiry = expiry)
        val first = service.timerObjects.single()
        sync(expiry = expiry, answered = intArrayOf(1))
        expire(first)
        val second = service.timerObjects.single { it !== first }
        assertEquals(2, second.repeatIndex)
        assertEquals(expiry + 120_000, second.expiresAt)
    }

    @Test
    fun staleAlarmAfterGenerationReplacementCannotRingTheNewRun() {
        sync(expiry = System.currentTimeMillis() + 30_000)
        val old = service.timerObjects.single()
        sync(expiry = System.currentTimeMillis() + 90_000, run = "replacement")
        expire(old)
        assertEquals(WatchState.RUNNING, service.timerObjects.single().state.value)
        assertEquals("replacement", service.timerObjects.single().sharedRunId)
    }

    private fun sync(
        expiry: Long,
        run: String = "run",
        membership: String = "member",
        baseIndex: Int = 0,
        repeatCount: Int = 2,
        answered: IntArray = intArrayOf()
    ) {
        service.onStartCommand(Intent(service, TimerService::class.java)
            .setAction(TimerService.SYNC_SHARED_TIMER_ACTION)
            .putExtra(TimerService.SHARED_TIMER_ID_EXTRA_KEY, "timer")
            .putExtra(TimerService.SHARED_TIMER_GROUP_ID_EXTRA_KEY, "group")
            .putExtra(TimerService.SHARED_TIMER_MEMBERSHIP_ID_EXTRA_KEY, membership)
            .putExtra(TimerService.SHARED_TIMER_RUN_ID_EXTRA_KEY, run)
            .putExtra(TimerService.SHARED_TIMER_LABEL_EXTRA_KEY, "Tea")
            .putExtra(TimerService.SHARED_TIMER_DURATION_EXTRA_KEY, 60)
            .putExtra(TimerService.SHARED_TIMER_EXPIRES_EXTRA_KEY, expiry)
            .putExtra(TimerService.SHARED_TIMER_REPEAT_ENABLED_EXTRA_KEY, true)
            .putExtra(TimerService.SHARED_TIMER_REPEAT_COUNT_EXTRA_KEY, repeatCount)
            .putExtra(TimerService.SHARED_TIMER_REPEAT_INDEX_EXTRA_KEY, baseIndex)
            .putExtra(TimerService.SHARED_TIMER_TIMEOUT_EXTRA_KEY, 7)
            .putExtra(TimerService.SHARED_TIMER_ANSWERED_INDICES_EXTRA_KEY, answered)
            .putExtra(TimerService.SHARED_TIMER_SOUND_ENABLED_EXTRA_KEY, false)
            .putExtra(TimerService.SHARED_TIMER_VIBRATE_EXTRA_KEY, false), 0, 1)
    }

    private fun expire(timer: TimerObject) {
        service.onStartCommand(Intent(service, TimerService::class.java)
            .setAction(TimerService.ACTION_TIMER_EXPIRED)
            .putExtra(TimerService.ID_EXTRA_KEY, timer.id)
            .putExtra(TimerService.EXPIRES_AT_EXTRA_KEY, timer.expiresAt), 0, 2)
    }
}
