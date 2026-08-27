package com.bnyro.clock.presentation.screens.timer.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.util.extensions.addZero

@Composable
fun SavedTimerItem(timer: TimerSettings, onStart: () -> Unit, onEdit: () -> Unit) {
    val hours = timer.seconds / 3600
    val minutes = timer.seconds % 3600 / 60
    val seconds = timer.seconds % 60

    ElevatedCard(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onEdit),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = timer.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "$hours:${minutes.addZero()}:${seconds.addZero()}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible
                )
            }

            FilledIconButton(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(48.dp),
                onClick = onStart
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
            }
        }
    }
}
