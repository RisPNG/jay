package com.bnyro.clock.presentation.screens.alarm.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.AccessTimeFilled
import androidx.compose.material.icons.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.AlarmFilters
import com.bnyro.clock.social.domain.PERSONAL_ALARM_SOURCE_ID
import com.bnyro.clock.social.domain.SocialGroup
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.TimeHelper

@Composable
fun AlarmFilterSection(
    filters: AlarmFilters,
    groups: List<SocialGroup>,
    selectedSourceIds: Set<String>?,
    onChangeLabel: (String) -> Unit,
    onClickWeekDay: (List<Int>) -> Unit,
    onChangeSources: (Set<String>?) -> Unit,
    onClickStartTime: (Long) -> Unit,
    onClickEndTime: (Long) -> Unit
) {

    var timeFromFilter by remember { mutableStateOf(false) }
    var timeToFilter by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        OutlinedTextField(
            value = filters.label,
            label = { Text(text = stringResource(id = R.string.alarm_name)) },
            leadingIcon = {
                Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
            },
            singleLine = true,
            shape = CircleShape,
            onValueChange = { onChangeLabel(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp)
        )

        WeekDayRow(weekDays = filters.weekDays, onClickWeekDay = onClickWeekDay)

        AlarmSourceRow(
            groups = groups,
            selectedSourceIds = selectedSourceIds,
            onChangeSources = onChangeSources
        )

        TimeRangeRow(
            startTime = filters.startTime,
            endTime = filters.endTime,
            onClickStartTime = { timeFromFilter = !timeFromFilter },
            onClickEndTime = { timeToFilter = !timeToFilter }
        )

        if (timeFromFilter) {
            TimePickerDialog(
                label = stringResource(R.string.from),
                onDismissRequest = { timeFromFilter = false }
            ) {
                onClickStartTime(it.toLong())
                timeFromFilter = false
            }
        }

        if (timeToFilter) {
            TimePickerDialog(
                label = stringResource(R.string.to),
                onDismissRequest = { timeToFilter = false }
            ) {
                onClickEndTime(it.toLong())
                timeToFilter = false
            }
        }
    }


}

@Composable
private fun AlarmSourceRow(
    groups: List<SocialGroup>,
    selectedSourceIds: Set<String>?,
    onChangeSources: (Set<String>?) -> Unit
) {
    val allSourceIds = remember(groups) {
        groups.mapTo(mutableSetOf(PERSONAL_ALARM_SOURCE_ID)) { it.id }
    }
    val chosenSourceIds = selectedSourceIds ?: allSourceIds

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Groups, null)
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.alarm_source),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onChangeSources(null) }) {
                Text(stringResource(R.string.select_all))
            }
            TextButton(onClick = { onChangeSources(emptySet()) }) {
                Text(stringResource(R.string.deselect_all))
            }
        }

        FlowRow(
            modifier = Modifier.padding(start = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = PERSONAL_ALARM_SOURCE_ID in chosenSourceIds,
                onClick = {
                    val updatedSourceIds = chosenSourceIds.toMutableSet()
                    if (PERSONAL_ALARM_SOURCE_ID in updatedSourceIds) {
                        updatedSourceIds.remove(PERSONAL_ALARM_SOURCE_ID)
                    } else {
                        updatedSourceIds.add(PERSONAL_ALARM_SOURCE_ID)
                    }
                    onChangeSources(updatedSourceIds)
                },
                label = { Text(stringResource(R.string.personal_alarm)) },
                shape = CircleShape
            )
            groups.forEach { group ->
                FilterChip(
                    selected = group.id in chosenSourceIds,
                    onClick = {
                        val updatedSourceIds = chosenSourceIds.toMutableSet()
                        if (group.id in updatedSourceIds) {
                            updatedSourceIds.remove(group.id)
                        } else {
                            updatedSourceIds.add(group.id)
                        }
                        onChangeSources(updatedSourceIds)
                    },
                    label = { Text(group.name) },
                    shape = CircleShape
                )
            }
        }
    }
}

@Composable
fun WeekDayRow(weekDays: List<Int>, onClickWeekDay: (List<Int>) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        val daysOfWeek = remember { AlarmHelper.getDaysOfWeekByLocale(context) }
        val chosenDays = remember { weekDays.toMutableList() }

        Icon(
            imageVector = Icons.Default.CalendarToday,
            contentDescription = null
        )

        Spacer(modifier = Modifier.width(16.dp))

        daysOfWeek.forEach { (day, index) ->
            val enabled = chosenDays.contains(index)
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(30.dp)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary else Color.Transparent,
                        CircleShape
                    )
                    .clip(CircleShape)
                    .border(
                        if (enabled) 0.dp else 1.dp,
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
                    .clickable {
                        if (enabled) {
                            if (chosenDays.size > 1) chosenDays.remove(index)
                        } else {
                            chosenDays.add(
                                index
                            )
                        }
                        onClickWeekDay(chosenDays.toList())
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = day,
                    color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

        }
    }
}

@Composable
fun TimeRangeRow(
    startTime: Long,
    endTime: Long,
    onClickStartTime: () -> Unit,
    onClickEndTime: () -> Unit
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.AccessTimeFilled,
            contentDescription = null
        )

        Spacer(modifier = Modifier.width(16.dp))

        Button(onClick = onClickStartTime, modifier = Modifier.weight(1f)) {
            Text(text = TimeHelper.millisToFormatted(context, startTime))
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowRightAlt,
            contentDescription = null,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp)
        )

        Button(onClick = onClickEndTime, modifier = Modifier.weight(1f)) {
            Text(text = TimeHelper.millisToFormatted(context, endTime))
        }

    }
}
