package com.bnyro.clock.social.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bnyro.clock.R
import com.bnyro.clock.presentation.components.DialogButton
import com.bnyro.clock.presentation.components.DialogButtonStyle

@Composable
fun ProfileImportDialog(
    socialModel: SocialModel,
    initialProfile: String = "",
    onDismiss: () -> Unit
) {
    var profile by remember(initialProfile) { mutableStateOf(initialProfile) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = profile,
                    onValueChange = { profile = it },
                    label = { Text(stringResource(R.string.import_profile_link)) }
                )
                Text(stringResource(R.string.import_profile_confirmation))
            }
        },
        confirmButton = {
            DialogButton(
                label = R.string.import_profile_confirm,
                style = DialogButtonStyle.PRIMARY
            ) {
                socialModel.importProfile(profile.trim())
                onDismiss()
            }
        },
        dismissButton = {
            DialogButton(
                label = R.string.cancel,
                style = DialogButtonStyle.SECONDARY
            ) {
                onDismiss()
            }
        }
    )
}
