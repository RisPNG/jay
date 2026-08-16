package com.bnyro.clock.presentation.features

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.presentation.screens.alarmpicker.components.AlarmPicker
import com.bnyro.clock.presentation.screens.alarmpicker.model.AlarmPickerModel
import com.bnyro.clock.util.AlarmHelper

@Composable
fun AlarmReceiverDialog(context: Context, alarm: Alarm) {
    var showDialog by rememberSaveable {
        mutableStateOf(true)
    }
    val alarmModel: AlarmPickerModel = viewModel()

    LaunchedEffect(alarmModel.createdAlarm) {
        alarmModel.createdAlarm?.let {
            AlarmHelper.showAlarmScheduledToast(context, it)
            showDialog = false
        }
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            AlarmPicker(
                onCancel = { showDialog = false },
                currentAlarm = alarm,
                groups = emptyList(),
                currentGroupId = null,
                busy = alarmModel.busy,
                onSave = { savedAlarm, _ ->
                    alarmModel.createAlarm(savedAlarm, null) {}
                }
            )
        }
    }
}
