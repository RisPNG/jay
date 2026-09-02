package com.bnyro.clock.presentation.screens.timer

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.AddAlarm
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.bnyro.clock.domain.model.TimerPickerBehaviour
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.navigation.TopBarScaffold
import com.bnyro.clock.presentation.components.ClickableIcon
import com.bnyro.clock.presentation.components.DialogButton
import com.bnyro.clock.presentation.components.DialogButtonStyle
import com.bnyro.clock.presentation.screens.settings.components.SettingsCategory
import com.bnyro.clock.presentation.screens.settings.model.SettingsModel
import com.bnyro.clock.presentation.screens.timer.components.SavedTimerItem
import com.bnyro.clock.presentation.screens.timer.components.TimerEditSheet
import com.bnyro.clock.presentation.screens.timer.components.TimerItem
import com.bnyro.clock.presentation.screens.timer.components.TimerPickerSelector
import com.bnyro.clock.presentation.screens.timer.model.TimerModel
import com.bnyro.clock.social.domain.canEditAlarms
import com.bnyro.clock.social.presentation.SocialModel
import com.bnyro.clock.ui.theme.ItemFade
import com.bnyro.clock.ui.theme.ItemFadeDurationMillis
import com.bnyro.clock.ui.theme.ItemSlide
import com.bnyro.clock.util.extensions.KeepScreenOn
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(
    onClickSettings: () -> Unit,
    timerModel: TimerModel,
    settingsModel: SettingsModel,
    socialModel: SocialModel
) {
    val context = LocalContext.current
    val activeTimers by timerModel.scheduledObjects.collectAsState()
    val groups by socialModel.groups.collectAsState()

    var editedTimer by remember { mutableStateOf<TimerObject?>(null) }
    var editedSavedTimerId by remember { mutableStateOf<Int?>(null) }
    var showGroupTimerDialog by remember { mutableStateOf(false) }
    var selectedGroupTimerId by remember { mutableStateOf<String?>(null) }
    val editableGroups = groups.filter { it.canEditAlarms }

    // the picker has nowhere to go until something is running, and how it starts out
    // once something is is what the setting decides rather than where it was left
    var showPicker by remember(settingsModel.timerPickerBehaviour) {
        mutableStateOf(
            activeTimers.isEmpty() ||
                settingsModel.timerPickerBehaviour == TimerPickerBehaviour.KEEP_OPEN
        )
    }
    LaunchedEffect(activeTimers.isEmpty(), settingsModel.timerPickerBehaviour) {
        if (activeTimers.isEmpty()) {
            showPicker = true
        } else if (settingsModel.timerPickerBehaviour == TimerPickerBehaviour.HIDE) {
            delay(ItemFadeDurationMillis.toLong())
            showPicker = false
        }
    }

    val timerPageState = rememberLazyListState()
    LaunchedEffect(showPicker) {
        if (showPicker) timerPageState.animateScrollToItem(0)
    }

    val selectedSavedTimerIds = remember { mutableStateListOf<Int>() }
    val isSelectionMode = selectedSavedTimerIds.isNotEmpty()
    var showDeletionDialog by remember { mutableStateOf(false) }

    TopBarScaffold(
        title = if (isSelectionMode) {
            stringResource(R.string.selected_count, selectedSavedTimerIds.size)
        } else {
            stringResource(R.string.timer)
        },
        onClickSettings = if (isSelectionMode) {
            { selectedSavedTimerIds.clear() }
        } else {
            onClickSettings
        },
        actions = {
            if (isSelectionMode) {
                ClickableIcon(imageVector = Icons.Default.ContentCopy) {
                    timerModel.savedTimers
                        .filter { selectedSavedTimerIds.contains(it.id) }
                        .forEach { timerModel.copySavedTimer(it) }
                    selectedSavedTimerIds.clear()
                }
                ClickableIcon(imageVector = Icons.Default.Delete) {
                    showDeletionDialog = true
                }
                ClickableIcon(imageVector = Icons.Default.Close) {
                    selectedSavedTimerIds.clear()
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = timerPageState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (showPicker) {
                item(key = "picker") {
                    Column(modifier = Modifier.animateItem(ItemFade, ItemSlide, ItemFade)) {
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
                            val startTimer = {
                                timerModel.startTimer(
                                    context,
                                    TimerSettings(seconds = timerModel.timePickerSeconds)
                                )
                            }
                            val askForGroup = {
                                selectedGroupTimerId = editableGroups.firstOrNull()?.id
                                showGroupTimerDialog = true
                            }
                            if (settingsModel.timerBigStartButton) {
                                LargeFloatingActionButton(
                                    shape = CircleShape,
                                    onClick = startTimer,
                                    modifier = Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClick = askForGroup
                                    )
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                }
                            } else {
                                FilledIconButton(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .combinedClickable(
                                            onClick = {},
                                            onLongClick = askForGroup
                                        ),
                                    onClick = startTimer
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }

            if (activeTimers.isNotEmpty()) {
                item(key = "activeTimers") {
                    Column(
                        modifier = Modifier
                            .animateItem(ItemFade, ItemSlide, ItemFade)
                            .padding(horizontal = 16.dp)
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                        SettingsCategory(
                            pluralStringResource(R.plurals.active_timers, activeTimers.size)
                        ) {
                            ClickableIcon(
                                imageVector = if (showPicker) {
                                    Icons.Rounded.ExpandLess
                                } else {
                                    Icons.Rounded.ExpandMore
                                },
                                contentDescription = stringResource(
                                    if (showPicker) {
                                        R.string.hide_timer_picker
                                    } else {
                                        R.string.show_timer_picker
                                    }
                                )
                            ) {
                                showPicker = !showPicker
                            }
                        }
                    }
                }
                items(activeTimers, key = { it.id }) { timer ->
                    TimerItem(
                        obj = timer,
                        timerModel = timerModel,
                        onEdit = { editedTimer = timer },
                        modifier = Modifier.animateItem(ItemFade, ItemSlide, ItemFade)
                    )
                }
            }

            item(key = "savedTimers") {
                Column(
                    modifier = Modifier
                        .animateItem(ItemFade, ItemSlide, ItemFade)
                        .padding(horizontal = 16.dp)
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    )
                    SettingsCategory(
                        pluralStringResource(R.plurals.saved_timers, timerModel.savedTimers.size)
                    ) {
                        if (!isSelectionMode) {
                            ClickableIcon(
                                imageVector = Icons.Rounded.AddAlarm,
                                contentDescription = stringResource(R.string.add_saved_timer)
                            ) {
                                timerModel.addSavedTimer(timerModel.timePickerSeconds)
                            }
                        }
                    }
                }
            }
            items(timerModel.savedTimers, key = { it.id }) { timer ->
                val isSelected = selectedSavedTimerIds.contains(timer.id)

                SavedTimerItem(
                    timer = timer,
                    isSelected = isSelected,
                    onStart = {
                        if (!isSelectionMode) {
                            val groupId = timer.groupId
                            if (groupId == null) {
                                timerModel.startTimer(context, timer)
                            } else {
                                val group = groups.firstOrNull { it.id == groupId }
                                if (group == null || !group.canEditAlarms) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.group_timer_requires_leader),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    socialModel.startGroupTimer(groupId, timer.label, timer)
                                }
                            }
                        }
                    },
                    onClick = {
                        if (isSelectionMode) {
                            if (isSelected) {
                                selectedSavedTimerIds.remove(timer.id)
                            } else {
                                selectedSavedTimerIds.add(timer.id)
                            }
                        } else {
                            editedSavedTimerId = timer.id
                        }
                    },
                    onLongClick = {
                        if (!isSelectionMode) selectedSavedTimerIds.add(timer.id)
                    },
                    modifier = Modifier.animateItem(ItemFade, ItemSlide, ItemFade)
                )
            }
        }
    }

    if (activeTimers.isNotEmpty()) {
        KeepScreenOn()
    }

    if (showGroupTimerDialog) {
        AlertDialog(
            onDismissRequest = { showGroupTimerDialog = false },
            title = { Text(text = stringResource(R.string.start_group_timer)) },
            text = {
                if (editableGroups.isEmpty()) {
                    Text(text = stringResource(R.string.group_timer_requires_leader))
                } else {
                    var groupMenuExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = groupMenuExpanded,
                        onExpandedChange = { groupMenuExpanded = !groupMenuExpanded }
                    ) {
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            value = editableGroups.firstOrNull { it.id == selectedGroupTimerId }
                                ?.name ?: editableGroups.first().name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.alarm_group)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(groupMenuExpanded)
                            }
                        )
                        ExposedDropdownMenu(
                            expanded = groupMenuExpanded,
                            onDismissRequest = { groupMenuExpanded = false }
                        ) {
                            editableGroups.forEach { group ->
                                DropdownMenuItem(
                                    text = { Text(group.name) },
                                    onClick = {
                                        selectedGroupTimerId = group.id
                                        groupMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (editableGroups.isNotEmpty()) {
                    DialogButton(label = R.string.start, style = DialogButtonStyle.PRIMARY) {
                        val groupId = selectedGroupTimerId ?: editableGroups.first().id
                        showGroupTimerDialog = false
                        socialModel.startGroupTimer(
                            groupId,
                            null,
                            TimerSettings(seconds = timerModel.timePickerSeconds)
                        )
                    }
                }
            },
            dismissButton = {
                DialogButton(label = android.R.string.cancel, style = DialogButtonStyle.SECONDARY) {
                    showGroupTimerDialog = false
                }
            }
        )
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

    if (showDeletionDialog) {
        AlertDialog(
            onDismissRequest = { showDeletionDialog = false },
            title = { Text(text = stringResource(R.string.delete_timers)) },
            text = { Text(text = stringResource(R.string.irreversible)) },
            confirmButton = {
                DialogButton(label = R.string.delete, style = DialogButtonStyle.DESTRUCTIVE) {
                    selectedSavedTimerIds.forEach { timerModel.removeSavedTimer(it) }
                    selectedSavedTimerIds.clear()
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

    editedSavedTimerId?.let { id ->
        TimerEditSheet(
            currentTimer = timerModel.savedTimers.first { it.id == id },
            pickerStyle = settingsModel.timerPickerStyle,
            groups = editableGroups,
            onSave = { settings ->
                timerModel.updateSavedTimer(settings)
                editedSavedTimerId = null
            },
            onDelete = {
                timerModel.removeSavedTimer(id)
                editedSavedTimerId = null
            },
            onDismiss = { editedSavedTimerId = null }
        )
    }
}
