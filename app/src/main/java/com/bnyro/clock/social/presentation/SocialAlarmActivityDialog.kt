package com.bnyro.clock.social.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bnyro.clock.R
import com.bnyro.clock.presentation.screens.alarm.model.AlarmModel

@Composable
fun SocialAlarmActivityDialog(alarmModel: AlarmModel) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { alarmModel.selectedActivityAlarmId = null },
        title = { Text(stringResource(R.string.alarm_logs)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (alarmModel.alarmActivity.isEmpty()) {
                    Text(stringResource(R.string.no_activity))
                }
                alarmModel.alarmActivity.forEach { change ->
                    SocialLogEntry(change, change.alarmLogTitle(context))
                }
                if (alarmModel.alarmActivityNextBefore != null) {
                    OutlinedButton(onClick = {
                        alarmModel.loadAlarmActivity(
                            alarmModel.selectedActivityAlarmId!!,
                            more = true
                        )
                    }) { Text(stringResource(R.string.load_more)) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { alarmModel.selectedActivityAlarmId = null }) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
