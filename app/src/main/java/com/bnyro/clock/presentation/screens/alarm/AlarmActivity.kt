package com.bnyro.clock.presentation.screens.alarm

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bnyro.clock.App
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.presentation.screens.ringing.RingingActivity
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.AlarmService
import kotlinx.coroutines.runBlocking

class AlarmActivity : RingingActivity() {
    private var alarm by mutableStateOf(Alarm(0, 0))
    private var ringingSince by mutableLongStateOf(System.currentTimeMillis())

    override val closeAction = ALARM_ALERT_CLOSE_ACTION

    override val volumeButtonActionKey = Preferences.volumeButtonActionKey

    override val snoozeAvailable get() = alarm.snoozeEnabled

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlarmAlertScreen(
                onDismiss = this@AlarmActivity::dismiss,
                onSnooze = this@AlarmActivity::snooze,
                label = alarm.label,
                snoozeEnabled = alarm.snoozeEnabled,
                snoozeTime = alarm.snoozeMinutes,
                ringingSince = ringingSince
            )
        }

        handleIntent(intent)
    }
    override fun dismiss() {
        sendBroadcast(Intent(AlarmService.ALARM_INTENT_ACTION)
            .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.DISMISS_ACTION)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
            .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ringingSince)
            .setPackage(packageName))
    }

    override fun snooze() = snooze(alarm.snoozeMinutes)

    private fun snooze(minutes: Int) {
        if (!alarm.snoozeEnabled) return
        sendBroadcast(Intent(AlarmService.ALARM_INTENT_ACTION)
            .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.SNOOZE_ACTION)
            .putExtra(AlarmService.EXTRA_SNOOZE_MINUTES, minutes)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
            .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ringingSince)
            .setPackage(packageName))
    }

    override fun onNewIntent(intent: Intent) {
        setIntent(intent)
        handleIntent(intent)
        super.onNewIntent(intent)
        sendBroadcast(Intent(AlarmService.ALARM_INTENT_ACTION)
            .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.ALERT_SHOWN_ACTION)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
            .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ringingSince)
            .putExtra(ALERT_GENERATION_EXTRA_KEY, intent.getLongExtra(ALERT_GENERATION_EXTRA_KEY, 0L))
            .setPackage(packageName))
    }

    private fun handleIntent(intent: Intent) {
        ringingSince = intent.getLongExtra(AlarmHelper.EXTRA_TRIGGER_TIME, System.currentTimeMillis())
        val id = intent.getLongExtra(AlarmHelper.EXTRA_ID, -1).takeIf { it != -1L } ?: return
        val alarmRepository = (application as App).container.alarmRepository
        this.alarm = runBlocking {
            alarmRepository.getAlarmById(id)
        } ?: return
    }

    override fun closesThisAlert(intent: Intent) =
        intent.getLongExtra(AlarmHelper.EXTRA_ID, -1L) == alarm.id &&
            intent.getLongExtra(AlarmHelper.EXTRA_TRIGGER_TIME, -1L) == ringingSince

    override fun onStart() {
        super.onStart()
        sendBroadcast(
            Intent(AlarmService.ALARM_INTENT_ACTION)
                .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.ALERT_SHOWN_ACTION)
                .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
                .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ringingSince)
                .putExtra(ALERT_GENERATION_EXTRA_KEY, intent.getLongExtra(ALERT_GENERATION_EXTRA_KEY, 0L))
                .setPackage(packageName)
        )
    }

    override fun onStop() {
        sendBroadcast(
            Intent(AlarmService.ALARM_INTENT_ACTION)
                .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.ALERT_HIDDEN_ACTION)
                .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
                .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ringingSince)
                .putExtra(ALERT_GENERATION_EXTRA_KEY, intent.getLongExtra(ALERT_GENERATION_EXTRA_KEY, 0L))
                .setPackage(packageName)
        )
        super.onStop()
    }

    companion object {
        const val ALARM_ALERT_CLOSE_ACTION = "com.bnyro.clock.ALARM_ALERT_CLOSE_ACTION"
    }
}
