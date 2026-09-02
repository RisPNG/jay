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
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.AlarmService
import com.bnyro.clock.social.data.SocialAlarmEvents
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AlarmActivity : RingingActivity() {
    private var alarm by mutableStateOf(Alarm(0, 0))
    private var groupName by mutableStateOf<String?>(null)

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
                groupName = groupName,
                snoozeEnabled = alarm.snoozeEnabled,
                snoozeTime = alarm.snoozeMinutes,
                alarmTimeMillis = alarm.time
            )
        }

        handleIntent(intent)
    }
    override fun dismiss() {
        SocialAlarmEvents.dismiss(
            this,
            alarm.id,
            intent.getStringExtra(AlarmService.EXTRA_OCCURRENCE_ID)
        )
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
        SocialAlarmEvents.snooze(
            this,
            alarm.id,
            minutes,
            intent.getStringExtra(AlarmService.EXTRA_OCCURRENCE_ID)
                ?: "${alarm.id}:${System.currentTimeMillis()}"
        )
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
        val socialRepository = (application as App).container.socialRepository
        this.alarm = runBlocking {
            alarmRepository.getAlarmById(id)
        } ?: return
        groupName = runBlocking {
            socialRepository.alarmGroupNames.first()
                .firstOrNull { it.localAlarmId == id }
                ?.groupName
        }
    }

    override fun onStart() {
        super.onStart()
        sendBroadcast(
            Intent(AlarmService.ALARM_INTENT_ACTION)
                .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.ALERT_SHOWN_ACTION)
                .setPackage(packageName)
        )
    }

    override fun onStop() {
        sendBroadcast(
            Intent(AlarmService.ALARM_INTENT_ACTION)
                .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.ALERT_HIDDEN_ACTION)
                .setPackage(packageName)
        )
        super.onStop()
    }

    companion object {
        const val ALARM_ALERT_CLOSE_ACTION = "com.bnyro.clock.ALARM_ALERT_CLOSE_ACTION"
    }
}
