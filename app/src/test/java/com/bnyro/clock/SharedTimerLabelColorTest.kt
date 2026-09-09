package com.bnyro.clock

import android.content.Intent
import com.bnyro.clock.util.services.TimerService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedTimerLabelColorTest {
    @Test
    fun sharedTimerColorsApplyOnArrivalAndUpdate() {
        val controller = Robolectric.buildService(TimerService::class.java).create()
        val service = controller.get()
        try {
            val intent = Intent(service, TimerService::class.java)
                .setAction(TimerService.SYNC_SHARED_TIMER_ACTION)
                .putExtra(TimerService.SHARED_TIMER_ID_EXTRA_KEY, "shared-tea")
                .putExtra(TimerService.SHARED_TIMER_LABEL_EXTRA_KEY, "Tea")
                .putExtra(TimerService.SHARED_TIMER_LABEL_COLOR_EXTRA_KEY, -15584170)
                .putExtra(TimerService.SHARED_TIMER_DURATION_EXTRA_KEY, 600)
                .putExtra(TimerService.SHARED_TIMER_EXPIRES_EXTRA_KEY, System.currentTimeMillis() + 600_000L)
                .putExtra(TimerService.SHARED_TIMER_SOUND_ENABLED_EXTRA_KEY, false)
                .putExtra(TimerService.SHARED_TIMER_VIBRATE_EXTRA_KEY, false)
            service.onStartCommand(intent, 0, 1)
            assertEquals(-15584170, service.timerObjects.single().labelColor.value)

            intent.putExtra(TimerService.SHARED_TIMER_LABEL_COLOR_EXTRA_KEY, -16777216)
            service.onStartCommand(intent, 0, 2)
            assertEquals(-16777216, service.timerObjects.single().labelColor.value)
            assertEquals(-16777216, service.timerObjects.single().settings.labelColor)

            intent.removeExtra(TimerService.SHARED_TIMER_LABEL_COLOR_EXTRA_KEY)
            service.onStartCommand(intent, 0, 3)
            assertEquals(-1, service.timerObjects.single().labelColor.value)
        } finally {
            controller.destroy()
        }
    }
}
