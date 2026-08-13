package com.bnyro.clock.presentation.screens.alarm.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.presentation.components.DialogButton
import com.bnyro.clock.presentation.components.DialogButtonStyle
import com.bnyro.clock.util.AlarmHelper
import kotlinx.coroutines.delay

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
fun AlarmItem(
    alarm: Alarm,
    groupName: String? = null,
    canEdit: Boolean = true,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onClick: (Alarm) -> Unit,
    onLongClick: (Alarm) -> Unit,
    onUpdateAlarm: (Alarm) -> Unit,
    onDeleteAlarm: (Alarm) -> Unit,
    onDismissAlarm: (Alarm) -> Unit,
    onActivity: (() -> Unit)? = null
) {
    var showDeletionDialog by remember { mutableStateOf(false) }
    var isAlarmEnabled by remember { mutableStateOf(alarm.enabled) }
    val alarmTime = AlarmHelper.getAlarmTime(alarm)
    var canDismiss by remember(alarm.id, isAlarmEnabled, alarm.dismissedAt, alarmTime) {
        val timeUntilAlarm = alarmTime - System.currentTimeMillis()
        mutableStateOf(
            isAlarmEnabled && timeUntilAlarm in 1..AlarmHelper.PRE_ALARM_DELAY
        )
    }

    LaunchedEffect(alarm.id, isAlarmEnabled, alarm.dismissedAt, alarmTime) {
        if (isAlarmEnabled) {
            val timeUntilDismissWindow =
                alarmTime - AlarmHelper.PRE_ALARM_DELAY - System.currentTimeMillis()
            if (timeUntilDismissWindow > 0) delay(timeUntilDismissWindow)
            canDismiss = alarmTime > System.currentTimeMillis()
            val timeUntilAlarm = alarmTime - System.currentTimeMillis()
            if (timeUntilAlarm > 0) delay(timeUntilAlarm)
            canDismiss = false
        }
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            if (dismissValue == SwipeToDismissBoxValue.StartToEnd) {
                showDeletionDialog = true
            }
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !isSelectionMode && canEdit,
        enableDismissFromEndToStart = false,
        content = {
            val cardShape = RoundedCornerShape(20.dp)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(cardShape)
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = cardShape
                            )
                        } else {
                            Modifier
                        }
                    )
                    .combinedClickable(
                        onClick = {
                            if (!isSelectionMode || canEdit) onClick(alarm)
                        },
                        onLongClick = {
                            if (canEdit) onLongClick(alarm)
                        }
                    )
            ) {
                AlarmCard(
                    alarm = alarm,
                    groupName = groupName,
                    canEdit = canEdit,
                    onClick = {
                    },
                    isAlarmEnabled = isAlarmEnabled,
                    canDismiss = canDismiss && !isSelectionMode,
                    onDismiss = {
                        canDismiss = false
                        onDismissAlarm(alarm)
                    },
                    onEnable = { enabled ->
                        if (!isSelectionMode && canEdit) {
                            isAlarmEnabled = enabled
                            alarm.enabled = enabled
                            onUpdateAlarm.invoke(alarm)
                        }
                    }
                )
                onActivity?.let {
                    IconButton(
                        onClick = it,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(Icons.Rounded.History, stringResource(R.string.alarm_activity))
                    }
                }
            }
        },
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    )

    if (showDeletionDialog) {
        AlertDialog(
            onDismissRequest = { showDeletionDialog = false },
            title = { Text(text = stringResource(R.string.delete_alarm)) },
            text = { Text(text = stringResource(R.string.irreversible)) },
            confirmButton = {
                DialogButton(label = R.string.delete, style = DialogButtonStyle.DESTRUCTIVE) {
                    onDeleteAlarm(alarm)
                    showDeletionDialog = false
                }
            },
            dismissButton = {
                DialogButton(label = android.R.string.cancel, style = DialogButtonStyle.SECONDARY) {
                    showDeletionDialog = false
                }
            }
        )
    }
}
