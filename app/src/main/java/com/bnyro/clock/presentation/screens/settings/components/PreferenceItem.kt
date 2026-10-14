package com.bnyro.clock.presentation.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnyro.clock.presentation.components.InfoButton

@Composable
fun PreferenceItem(
    modifier: Modifier = Modifier,
    title: String,
    summary: String? = null,
    info: String? = null
) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = modifier
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            info?.let { InfoButton(title = title, explanation = it) }
        }
        if (summary != null) {
            Spacer(Modifier.height(2.dp))
            Text(summary, fontSize = 12.sp)
        }
    }
}
