package com.bnyro.clock.presentation.screens.alarmpicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.clock.presentation.screens.alarmpicker.components.AlarmPicker
import com.bnyro.clock.presentation.screens.alarmpicker.model.AlarmPickerModel
import com.bnyro.clock.social.domain.canEditAlarms
import com.bnyro.clock.util.AlarmHelper

@Composable
fun AlarmPickerScreen(onNavigateBack: () -> Unit) {
    val viewModel: AlarmPickerModel = viewModel()
    val context = LocalContext.current
    val groups by viewModel.groups.collectAsState()
    val currentGroup = groups.firstOrNull { it.id == viewModel.groupId }
    AlarmPicker(
        onCancel = { onNavigateBack.invoke() },
        currentAlarm = viewModel.alarm,
        advanced = viewModel.advanced,
        groups = groups.filter {
            it.id == viewModel.groupId || it.canEditAlarms
        },
        currentGroupId = viewModel.groupId,
        canSave = currentGroup == null || currentGroup.canEditAlarms,
        busy = viewModel.busy,
        onDelete = { alarm ->
            viewModel.deleteAlarm(alarm) {
                onNavigateBack.invoke()
            }
        },
        onSave = { alarm, groupId ->
            if (alarm.id == 0L) {
                viewModel.createAlarm(alarm.copy(enabled = true), groupId) {
                    AlarmHelper.showAlarmScheduledToast(context, alarm)
                    onNavigateBack.invoke()
                }
            } else {
                viewModel.updateAlarm(alarm.copy(enabled = true)) {
                    AlarmHelper.showAlarmScheduledToast(context, alarm)
                    onNavigateBack.invoke()
                }
            }
        })
}
