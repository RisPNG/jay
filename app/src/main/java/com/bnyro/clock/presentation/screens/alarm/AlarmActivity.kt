package com.bnyro.clock.presentation.screens.alarm

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bnyro.clock.App
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.presentation.screens.ringing.RingingActivity
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.services.AlarmService
import kotlinx.coroutines.runBlocking

class AlarmActivity : RingingActivity() {
    private var alarm by mutableStateOf(Alarm(0, 0))

    override val closeAction = ALARM_ALERT_CLOSE_ACTION

    override val snoozeAvailable get() = alarm.snoozeEnabled

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlarmAlertScreen(
                onDismiss = this@AlarmActivity::dismiss,
                onSnooze = this@AlarmActivity::snooze,
                label = alarm.label,
                snoozeEnabled = alarm.snoozeEnabled,
                snoozeTime = alarm.snoozeMinutes
            )
        }

        handleIntent(intent)
    }
    override fun dismiss() {
        stopService(
            Intent(
                this@AlarmActivity.applicationContext,
                AlarmService::class.java
            )
        )
        this@AlarmActivity.finish()
    }

    override fun snooze() = snooze(alarm.snoozeMinutes)

    private fun snooze(minutes: Int) {
        stopService(
            Intent(
                this@AlarmActivity.applicationContext,
                AlarmService::class.java
            )
        )
        AlarmHelper.snooze(this@AlarmActivity, alarm, minutes)
        this@AlarmActivity.finish()
    }

    override fun onNewIntent(intent: Intent) {
        handleIntent(intent)
        super.onNewIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val id = intent.getLongExtra(AlarmHelper.EXTRA_ID, -1).takeIf { it != -1L } ?: return
        val alarmRepository = (application as App).container.alarmRepository
        this.alarm = runBlocking {
            alarmRepository.getAlarmById(id)
        } ?: return
    }

    companion object {
        const val ALARM_ALERT_CLOSE_ACTION = "com.bnyro.clock.ALARM_ALERT_CLOSE_ACTION"
    }
}
