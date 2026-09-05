package com.bnyro.clock.social.presentation

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.clock.BuildConfig
import com.bnyro.clock.R
import com.bnyro.clock.presentation.components.DialogButton
import com.bnyro.clock.presentation.components.DialogButtonStyle
import com.bnyro.clock.presentation.screens.settings.components.IconPreference
import com.bnyro.clock.presentation.screens.settings.components.SettingsCategory

@Composable
fun SocialSettingsSection() {
    val context = LocalContext.current
    val socialModel: SocialModel = viewModel()
    var showDeviceNameDialog by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    val shareProfileTitle = stringResource(R.string.share_profile)

    LaunchedEffect(socialModel.message) {
        socialModel.message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            socialModel.consumeMessage()
        }
    }
    LaunchedEffect(socialModel.profile) {
        socialModel.profile?.let {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, it)
                    },
                    shareProfileTitle
                )
            )
            socialModel.consumeProfile()
        }
    }

    SettingsCategory(stringResource(R.string.jay_social))
    IconPreference(
        title = stringResource(R.string.device_name),
        summary = socialModel.deviceName,
        imageVector = Icons.Default.Badge
    ) {
        showDeviceNameDialog = true
    }
    IconPreference(
        title = stringResource(R.string.jay_server),
        summary = socialModel.serverUrl,
        imageVector = Icons.Default.Cloud
    ) {
        showServerDialog = true
    }
    IconPreference(
        title = stringResource(R.string.export_profile),
        summary = stringResource(R.string.export_profile_summary),
        imageVector = Icons.Default.Share
    ) {
        socialModel.exportProfile()
    }
    IconPreference(
        title = stringResource(R.string.import_profile),
        summary = stringResource(R.string.import_profile_summary),
        imageVector = Icons.Default.Download
    ) {
        showImportDialog = true
    }
    IconPreference(
        title = stringResource(R.string.reset_identity),
        summary = stringResource(R.string.reset_identity_summary),
        imageVector = Icons.Default.RestartAlt
    ) {
        showResetDialog = true
    }
    HorizontalDivider(
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    )

    if (showDeviceNameDialog) {
        var name by remember(socialModel.deviceName) { mutableStateOf(socialModel.deviceName) }
        AlertDialog(
            onDismissRequest = { showDeviceNameDialog = false },
            title = { Text(stringResource(R.string.device_name)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        socialModel.renameDevice(name.trim())
                        showDeviceNameDialog = false
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeviceNameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (showServerDialog) {
        var server by remember(socialModel.serverUrl) { mutableStateOf(socialModel.serverUrl) }
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            title = { Text(stringResource(R.string.jay_server)) },
            text = {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        socialModel.changeServer(server.trim())
                        showServerDialog = false
                    },
                    enabled = server.startsWith("https://") ||
                        BuildConfig.DEBUG && server.startsWith("http://")
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showServerDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (showImportDialog) {
        ProfileImportDialog(socialModel = socialModel) { showImportDialog = false }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset_identity)) },
            text = { Text(stringResource(R.string.reset_identity_confirmation)) },
            confirmButton = {
                DialogButton(
                    label = R.string.reset_identity_confirm,
                    style = DialogButtonStyle.DESTRUCTIVE
                ) {
                    socialModel.resetIdentity()
                    showResetDialog = false
                }
            },
            dismissButton = {
                DialogButton(
                    label = R.string.cancel,
                    style = DialogButtonStyle.SECONDARY
                ) {
                    showResetDialog = false
                }
            }
        )
    }
}
