package com.bnyro.clock.presentation.screens.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.AddAlarm
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.TimerObject
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.navigation.TopBarScaffold
import com.bnyro.clock.presentation.components.ClickableIcon
import com.bnyro.clock.presentation.screens.settings.components.SettingsCategory
import com.bnyro.clock.presentation.screens.settings.model.SettingsModel
import com.bnyro.clock.presentation.screens.timer.components.SavedTimerItem
import com.bnyro.clock.presentation.screens.timer.components.TimerEditSheet
import com.bnyro.clock.presentation.screens.timer.components.TimerItem
import com.bnyro.clock.presentation.screens.timer.components.TimerPickerSelector
import com.bnyro.clock.presentation.screens.timer.model.TimerModel
import com.bnyro.clock.util.extensions.KeepScreenOn

@Composable
fun TimerScreen(
    onClickSettings: () -> Unit, timerModel: TimerModel, settingsModel: SettingsModel
) {
    val context = LocalContext.current
    val activeTimers by timerModel.scheduledObjects.collectAsState()

    var editedTimer by remember { mutableStateOf<TimerObject?>(null) }
    var editedSavedTimerIndex by remember { mutableStateOf<Int?>(null) }

    TopBarScaffold(
        title = stringResource(R.string.timer),
        onClickSettings = onClickSettings
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                TimerPickerSelector(
                    pickerStyle = settingsModel.timerPickerStyle,
                    seconds = timerModel.timePickerSeconds,
                    onSecondsChanged = { timerModel.timePickerSeconds = it }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    LargeFloatingActionButton(
                        shape = CircleShape,
                        onClick = {
                            timerModel.startTimer(
                                context,
                                TimerSettings(timerModel.timePickerSeconds)
                            )
                        }
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                    }
                }
            }

            if (activeTimers.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                        SettingsCategory(
                            pluralStringResource(R.plurals.active_timers, activeTimers.size)
                        )
                    }
                }
                items(activeTimers, key = { it.id }) { timer ->
                    TimerItem(timer, timerModel, onEdit = { editedTimer = timer })
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    )
                    SettingsCategory(
                        pluralStringResource(R.plurals.saved_timers, timerModel.savedTimers.size)
                    ) {
                        ClickableIcon(
                            imageVector = Icons.Rounded.AddAlarm,
                            contentDescription = stringResource(R.string.add_saved_timer)
                        ) {
                            timerModel.addSavedTimer(timerModel.timePickerSeconds)
                        }
                    }
                }
            }
            itemsIndexed(timerModel.savedTimers) { index, timer ->
                SavedTimerItem(
                    timer = timer,
                    onStart = { timerModel.startTimer(context, timer) },
                    onEdit = { editedSavedTimerIndex = index }
                )
            }
        }
    }

    if (activeTimers.isNotEmpty()) {
        KeepScreenOn()
    }

    editedTimer?.let { timer ->
        TimerEditSheet(
            currentTimer = timer.settings,
            pickerStyle = settingsModel.timerPickerStyle,
            onSave = { settings ->
                timerModel.updateTimer(timer.id, settings)
                editedTimer = null
            },
            onDismiss = { editedTimer = null }
        )
    }

    editedSavedTimerIndex?.let { index ->
        TimerEditSheet(
            currentTimer = timerModel.savedTimers[index],
            pickerStyle = settingsModel.timerPickerStyle,
            onSave = { settings ->
                timerModel.updateSavedTimer(index, settings)
                editedSavedTimerIndex = null
            },
            onDelete = {
                timerModel.removeSavedTimer(index)
                editedSavedTimerIndex = null
            },
            onDismiss = { editedSavedTimerIndex = null }
        )
    }
}
