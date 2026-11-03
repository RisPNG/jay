package com.bnyro.clock

import android.content.Intent
import android.os.SystemClock
import android.provider.AlarmClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.TimerDescriptor
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.presentation.screens.alarm.AlarmActivity
import com.bnyro.clock.presentation.screens.ringing.RingingActivity
import com.bnyro.clock.presentation.screens.timer.TimerAlertActivity
import com.bnyro.clock.ui.MainActivity
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.AlarmService
import com.bnyro.clock.util.services.TimerService
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RingingPriorityTest {
    private lateinit var app: App
    private var mainScenario: ActivityScenario<MainActivity>? = null
    private var previousFullScreen: Boolean? = null
    private val alarms = mutableListOf<Pair<Alarm, Long>>()
    private var testTimerId: Int? = null
    private val label = "Ringing priority test ${UUID.randomUUID()}"

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        previousFullScreen = Preferences.instance.all[Preferences.timerFullScreenAlertKey] as? Boolean
        Preferences.edit { putBoolean(Preferences.timerFullScreenAlertKey, true) }
        mainScenario = ActivityScenario.launch<MainActivity>(
            Intent(app, MainActivity::class.java).setAction(AlarmClock.ACTION_SHOW_ALARMS)
        )
    }

    @After
    fun tearDown() {
        try {
            alarms.forEach { (alarm, triggerTime) ->
                app.sendBroadcast(Intent(AlarmService.ALARM_INTENT_ACTION)
                    .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.DISMISS_ACTION)
                    .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
                    .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, triggerTime)
                    .setPackage(app.packageName))
            }
            testTimerId?.let { app.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, it)) }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                Stage.values().flatMap { monitor.getActivitiesInStage(it) }.toSet()
                    .filterIsInstance<RingingActivity>()
                    .filter { activity ->
                        when (activity) {
                            is AlarmActivity -> alarms.any { (alarm, _) ->
                                activity.intent.getLongExtra(AlarmHelper.EXTRA_ID, -1L) == alarm.id
                            }
                            is TimerAlertActivity -> testTimerId != null &&
                                activity.intent.getIntExtra(TimerService.ID_EXTRA_KEY, 0) == testTimerId
                            else -> false
                        }
                    }.forEach { it.finish() }
            }
            instrumentation.waitForIdleSync()
            alarms.forEach { (alarm, _) ->
                AlarmHelper.cancel(app, alarm)
                runBlocking { app.container.alarmRepository.deleteAlarm(alarm) }
            }
        } finally {
            Preferences.edit {
                previousFullScreen?.let { putBoolean(Preferences.timerFullScreenAlertKey, it) }
                    ?: remove(Preferences.timerFullScreenAlertKey)
            }
            mainScenario?.close()
        }
    }

    @Test
    fun aMoreImportantAlarmTakesOverAndTheFirstAlarmReturnsWhenItIsDismissed() {
        val first = Alarm(time = 0L, enabled = false, label = "$label first", soundEnabled = false,
            vibrate = false, timeoutMinutes = 5)
        first.id = runBlocking { app.container.alarmRepository.addAlarm(first) }
        val second = Alarm(time = 0L, enabled = false, label = "$label second", soundEnabled = false,
            vibrate = true, timeoutMinutes = 5)
        second.id = runBlocking { app.container.alarmRepository.addAlarm(second) }
        val triggerTime = System.currentTimeMillis()
        alarms.addAll(listOf(first to triggerTime, second to triggerTime))

        ContextCompat.startForegroundService(app, Intent(app, AlarmService::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, first.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, triggerTime))
        val initial = awaitRingingActivity(alarmId = first.id)
        ContextCompat.startForegroundService(app, Intent(app, AlarmService::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, second.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, triggerTime))
        val takeover = awaitRingingActivity(alarmId = second.id)

        app.sendBroadcast(Intent(AlarmService.ALARM_INTENT_ACTION).putExtras(takeover)
            .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.DISMISS_ACTION).setPackage(app.packageName))

        awaitRingingActivity(alarmId = first.id,
            minimumGeneration = initial.getLongExtra(RingingActivity.ALERT_GENERATION_EXTRA_KEY, 0L) + 1L)
    }

    @Test
    fun aVibratingTimerTakesOverASilentAlarmAndReturnsItWhenAnswered() {
        val alarm = Alarm(time = 0L, enabled = false, label = "$label alarm", soundEnabled = false,
            vibrate = false, timeoutMinutes = 5)
        alarm.id = runBlocking { app.container.alarmRepository.addAlarm(alarm) }
        val triggerTime = System.currentTimeMillis()
        alarms.add(alarm to triggerTime)
        ContextCompat.startForegroundService(app, Intent(app, AlarmService::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, triggerTime))
        val initial = awaitRingingActivity(alarmId = alarm.id)
        val timerId = -310_001
        testTimerId = timerId

        ContextCompat.startForegroundService(app, Intent(app, TimerService::class.java)
            .putExtra(TimerService.INITIAL_TIMER_EXTRA_KEY, TimerDescriptor(timerId,
                TimerSettings(seconds = 1, label = "$label timer", soundEnabled = false,
                    vibrate = true, timeoutMinutes = 5))))
        val takeover = awaitRingingActivity(timerId = timerId)
        app.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_STOP, timerId).putExtras(takeover))

        awaitRingingActivity(alarmId = alarm.id,
            minimumGeneration = initial.getLongExtra(RingingActivity.ALERT_GENERATION_EXTRA_KEY, 0L) + 1L)
    }

    private fun awaitRingingActivity(
        alarmId: Long? = null,
        timerId: Int? = null,
        minimumGeneration: Long = 1L
    ): Intent {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 15_000L
        var observed = "none"
        while (SystemClock.uptimeMillis() < deadline) {
            var found: Intent? = null
            instrumentation.runOnMainSync {
                val activities = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<RingingActivity>()
                observed = activities.joinToString { activity ->
                    "${activity.javaClass.simpleName}:${activity.intent.getLongExtra(AlarmHelper.EXTRA_ID, -1L)}:" +
                        "${activity.intent.getIntExtra(TimerService.ID_EXTRA_KEY, 0)}"
                }
                activities.firstOrNull { activity ->
                    val matches = when (activity) {
                        is AlarmActivity -> alarmId != null &&
                            activity.intent.getLongExtra(AlarmHelper.EXTRA_ID, -1L) == alarmId
                        is TimerAlertActivity -> timerId != null &&
                            activity.intent.getIntExtra(TimerService.ID_EXTRA_KEY, 0) == timerId
                        else -> false
                    }
                    matches && activity.intent.getLongExtra(RingingActivity.ALERT_GENERATION_EXTRA_KEY, 0L) >= minimumGeneration
                }?.let { found = Intent(it.intent) }
            }
            found?.let { return it }
            SystemClock.sleep(50L)
        }
        throw AssertionError("Expected resumed alarm=$alarmId timer=$timerId generation>=$minimumGeneration; observed $observed")
    }
}
