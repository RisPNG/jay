package com.bnyro.clock.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.bnyro.clock.R

@Composable
fun InfoButton(title: String, modifier: Modifier = Modifier, text: @Composable () -> Unit) {
    var showInformation by rememberSaveable { mutableStateOf(false) }

    ClickableIcon(
        modifier = modifier,
        imageVector = Icons.Outlined.Info,
        contentDescription = stringResource(R.string.option_information, title),
        onClick = { showInformation = true }
    )

    if (showInformation) {
        AlertDialog(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.95f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { showInformation = false },
            title = { Text(text = title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    text()
                }
            },
            confirmButton = {
                DialogButton(label = R.string.close, style = DialogButtonStyle.PRIMARY) {
                    showInformation = false
                }
            }
        )
    }
}
