package com.bnyro.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bnyro.clock.domain.model.VolumeButtonAction
import com.bnyro.clock.presentation.screens.timer.TimerAlertActivity
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.TimerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TimerAlertTakeoverTest {
    @Test
    fun recreatedAlertAnswersTheTimerThatTookOverTheActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        Preferences.init(context)
        val previousVolumeAction = Preferences.instance
            .getString(Preferences.timerVolumeButtonActionKey, null)
        val dismissedTimers = ArrayBlockingQueue<Int>(1)
        val takeoverShown = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val timerId = intent?.getIntExtra(TimerService.ID_EXTRA_KEY, 0) ?: return
                when (intent.getStringExtra(TimerService.ACTION_EXTRA_KEY)) {
                    TimerService.ACTION_STOP -> dismissedTimers.offer(timerId)
                    TimerService.ACTION_ALERT_SHOWN -> if (timerId == 202) takeoverShown.countDown()
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(TimerService.UPDATE_STATE_ACTION).apply {
                addDataScheme(TimerService.UPDATE_STATE_SCHEME)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        try {
            Preferences.edit {
                putString(Preferences.timerVolumeButtonActionKey, VolumeButtonAction.DISMISS.name)
            }
            val ringingSince = System.currentTimeMillis() - 60_000
            val firstTimer = Intent(context, TimerAlertActivity::class.java)
                .putExtra(TimerService.ID_EXTRA_KEY, 101)
                .putExtra(TimerService.LABEL_EXTRA_KEY, "First timer")
                .putExtra(TimerService.RINGING_SINCE_EXTRA_KEY, ringingSince)
                .putExtra(TimerService.INCREMENT_EXTRA_KEY, 30)
            ActivityScenario.launch<TimerAlertActivity>(firstTimer).use { scenario ->
                lateinit var originalActivity: TimerAlertActivity
                scenario.onActivity { activity ->
                    originalActivity = activity
                    activity.startActivity(
                        Intent(context, TimerAlertActivity::class.java)
                            .putExtra(TimerService.ID_EXTRA_KEY, 202)
                            .putExtra(TimerService.LABEL_EXTRA_KEY, "Second timer")
                            .putExtra(TimerService.RINGING_SINCE_EXTRA_KEY, ringingSince - 60_000)
                            .putExtra(TimerService.INCREMENT_EXTRA_KEY, 120)
                    )
                }
                assertTrue("The replacement timer must be shown", takeoverShown.await(5, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    assertSame(originalActivity, activity)
                    assertEquals(202, activity.intent.getIntExtra(TimerService.ID_EXTRA_KEY, 0))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(202, activity.intent.getIntExtra(TimerService.ID_EXTRA_KEY, 0))
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN))
                }
                assertEquals(202, dismissedTimers.poll(5, TimeUnit.SECONDS))
            }
        } finally {
            context.unregisterReceiver(receiver)
            Preferences.edit {
                putString(Preferences.timerVolumeButtonActionKey, previousVolumeAction)
            }
        }
    }
}
