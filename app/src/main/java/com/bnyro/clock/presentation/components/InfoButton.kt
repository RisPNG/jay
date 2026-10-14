package com.bnyro.clock.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.bnyro.clock.R

@Composable
fun InfoButton(title: String, explanation: String) {
    var showInformation by rememberSaveable { mutableStateOf(false) }

    ClickableIcon(
        imageVector = Icons.Outlined.Info,
        contentDescription = stringResource(R.string.option_information, title),
        onClick = { showInformation = true }
    )

    if (showInformation) {
        AlertDialog(
            onDismissRequest = { showInformation = false },
            title = { Text(text = title) },
            text = { Text(text = explanation) },
            confirmButton = {
                DialogButton(label = R.string.close, style = DialogButtonStyle.PRIMARY) {
                    showInformation = false
                }
            }
        )
    }
}
