package com.bnyro.clock.social.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bnyro.clock.BuildConfig
import com.bnyro.clock.R
import com.bnyro.clock.presentation.components.DialogButton
import com.bnyro.clock.presentation.components.DialogButtonStyle
import com.bnyro.clock.presentation.screens.settings.components.IconPreference
import com.bnyro.clock.presentation.screens.settings.components.PreferenceItem
import com.bnyro.clock.social.data.PlayInstallationStore
import com.bnyro.clock.social.data.SocialPreferences
import com.bnyro.clock.util.Preferences

private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.rispng.jay"

@Composable
fun JaySupportDialog() {
    if (BuildConfig.FLAVOR != "lite") return

    var showDialog by rememberSaveable {
        mutableStateOf(!Preferences.instance.getBoolean(SocialPreferences.supportPromptShownKey, false))
    }
    val uriHandler = LocalUriHandler.current
    if (showDialog) {
        LaunchedEffect(Unit) {
            Preferences.edit { putBoolean(SocialPreferences.supportPromptShownKey, true) }
        }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            icon = { Icon(Icons.Rounded.VolunteerActivism, contentDescription = null) },
            title = { Text(stringResource(R.string.jay_support_title)) },
            text = { Text(stringResource(R.string.jay_support_description)) },
            confirmButton = {
                DialogButton(R.string.buy_jay, DialogButtonStyle.PRIMARY) {
                    showDialog = false
                    uriHandler.openUri(PLAY_STORE_URL)
                }
            },
            dismissButton = {
                DialogButton(R.string.maybe_later, DialogButtonStyle.SECONDARY) {
                    showDialog = false
                }
            }
        )
    }
}

@Composable
fun JaySupportPreference() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    if (BuildConfig.FLAVOR == "lite") {
        IconPreference(
            title = stringResource(R.string.jay_support_us),
            summary = stringResource(R.string.jay_support_summary),
            imageVector = Icons.Rounded.VolunteerActivism
        ) {
            uriHandler.openUri(PLAY_STORE_URL)
        }
    } else if (PlayInstallationStore.isEligible(context)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PreferenceItem(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.jay_support_thanks),
                summary = stringResource(R.string.jay_support_thanks_summary)
            )
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
