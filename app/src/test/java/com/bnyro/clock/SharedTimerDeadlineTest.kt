package com.bnyro.clock

import android.content.Intent
import com.bnyro.clock.util.services.TimerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedTimerDeadlineTest {
    @Test
    fun firstReceiptAtOrAfterTheDeadlineDoesNotStartATimer() {
        val controller = Robolectric.buildService(TimerService::class.java).create()
        val service = controller.get()
        try {
            for (lateBy in listOf(0L, 1L, 60_000L, 600_000L)) {
                val intent = Intent(service, TimerService::class.java)
                    .setAction(TimerService.SYNC_SHARED_TIMER_ACTION)
                    .putExtra(TimerService.SHARED_TIMER_ID_EXTRA_KEY, "missed-$lateBy")
                    .putExtra(TimerService.SHARED_TIMER_DURATION_EXTRA_KEY, 60)
                    .putExtra(TimerService.SHARED_TIMER_EXPIRES_EXTRA_KEY, System.currentTimeMillis() - lateBy)
                    .putExtra(TimerService.SHARED_TIMER_SOUND_ENABLED_EXTRA_KEY, false)
                    .putExtra(TimerService.SHARED_TIMER_VIBRATE_EXTRA_KEY, false)
                assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
                assertTrue(service.timerObjects.isEmpty())
                service.onStartCommand(intent, 0, 2)
                assertTrue(service.timerObjects.isEmpty())
            }
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun receiptBeforeTheDeadlineUsesOnlyTheRemainingTime() {
        val controller = Robolectric.buildService(TimerService::class.java).create()
        val service = controller.get()
        try {
            val expiry = System.currentTimeMillis() + 30_000L
            val intent = Intent(service, TimerService::class.java)
                .setAction(TimerService.SYNC_SHARED_TIMER_ACTION)
                .putExtra(TimerService.SHARED_TIMER_ID_EXTRA_KEY, "arrived-in-time")
                .putExtra(TimerService.SHARED_TIMER_DURATION_EXTRA_KEY, 60)
                .putExtra(TimerService.SHARED_TIMER_EXPIRES_EXTRA_KEY, expiry)
                .putExtra(TimerService.SHARED_TIMER_SOUND_ENABLED_EXTRA_KEY, false)
                .putExtra(TimerService.SHARED_TIMER_VIBRATE_EXTRA_KEY, false)
            service.onStartCommand(intent, 0, 1)
            val timer = service.timerObjects.single()
            assertEquals(expiry, timer.sharedExpiresAt)
            assertTrue(timer.currentPosition.value in 1..30_000)
            assertEquals(60_000, timer.initialPosition.value)
            service.onStartCommand(Intent(service, TimerService::class.java)
                .setAction(TimerService.ACTION_TIMER_EXPIRED)
                .putExtra(TimerService.ID_EXTRA_KEY, timer.id), 0, 2)
            assertEquals(0, timer.currentPosition.value)
            assertEquals(timer, service.timerObjects.single())
        } finally {
            controller.destroy()
        }
    }
}
