package com.bnyro.clock.presentation.screens.settings.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bnyro.clock.presentation.components.InfoButton
import com.bnyro.clock.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ButtonGroupPref(
    title: String,
    options: List<String>,
    values: List<T>,
    currentValue: T,
    preview: (@Composable (T) -> Unit)? = null,
    onChange: (T) -> Unit
) {
    Column(
        modifier = Modifier
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false)
            )
            preview?.let { content ->
                InfoButton(
                    title = title,
                    modifier = Modifier.height(with(LocalDensity.current) {
                        MaterialTheme.typography.labelLarge.lineHeight.toDp()
                    })
                ) {
                    var previewValue by remember { mutableStateOf(currentValue) }
                    Column {
                        ButtonGroupPref(
                            title = stringResource(R.string.preview),
                            options = options,
                            values = values,
                            currentValue = previewValue,
                            onChange = { previewValue = it }
                        )
                        Spacer(Modifier.height(16.dp))
                        content(previewValue)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        val scrollState = rememberScrollState()
        MultiChoiceSegmentedButtonRow(
            modifier = Modifier.horizontalScroll(scrollState)
        ) {
            values.forEachIndexed { index, value ->
                val isChecked = if (currentValue is List<*>) {
                    currentValue.contains(value)
                } else {
                    currentValue == value
                }
                SegmentedButton(
                    checked = isChecked,
                    onCheckedChange = { onChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = values.size),
                    modifier = Modifier.widthIn(min = 112.dp)
                ) {
                    Text(
                        text = options[index],
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
