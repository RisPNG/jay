package com.bnyro.clock.social.presentation

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.bnyro.clock.App
import com.bnyro.clock.navigation.HomeRoutes
import com.bnyro.clock.social.data.SocialPreferences
import com.bnyro.clock.util.Preferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SocialActivityCoordinator(private val activity: ComponentActivity) {
    private var liveSyncJob: Job? = null

    fun receiveInvitation(intent: Intent?): Boolean {
        val invitation = intent?.dataString?.takeIf {
            intent.action == Intent.ACTION_VIEW && it.startsWith("jay://join?")
        } ?: return false
        Preferences.edit { putString(SocialPreferences.pendingInvitationKey, invitation) }
        return true
    }

    fun homeRoute(intent: Intent?): HomeRoutes? = when (intent?.action) {
        Intent.ACTION_VIEW -> HomeRoutes.Groups
        SocialNotificationHelper.SHOW_SOCIAL_ACTIVITY_ACTION -> {
            if (
                intent.getStringExtra(SocialNotificationHelper.EXTRA_SOCIAL_ENTITY_TYPE) in
                setOf("alarm", "outcome")
            ) HomeRoutes.Alarm else HomeRoutes.Groups
        }
        else -> null
    }

    fun startLiveSync() {
        liveSyncJob = activity.lifecycleScope.launch {
            while (isActive) {
                runCatching {
                    (activity.application as App).container.socialRepository.followLiveChanges {
                        SocialNotificationHelper.notifySocialChanges(activity, it)
                    }
                }
                if (isActive) delay(2_000)
            }
        }
    }

    fun stopLiveSync() {
        liveSyncJob?.cancel()
        liveSyncJob = null
    }
}
