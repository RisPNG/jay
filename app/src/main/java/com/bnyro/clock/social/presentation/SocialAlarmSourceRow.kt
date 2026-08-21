package com.bnyro.clock.social.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bnyro.clock.R
import com.bnyro.clock.presentation.components.DialogButton
import com.bnyro.clock.presentation.components.DialogButtonStyle
import com.bnyro.clock.social.domain.PERSONAL_ALARM_SOURCE_ID
import com.bnyro.clock.social.domain.SocialGroup

@Composable
fun SocialAlarmSourceRow(
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
            DialogButton(R.string.select_all, DialogButtonStyle.SECONDARY) {
                onChangeSources(null)
            }
            DialogButton(R.string.deselect_all, DialogButtonStyle.SECONDARY) {
                onChangeSources(emptySet())
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
