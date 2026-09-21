package com.bnyro.clock.social.presentation

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.bnyro.clock.App
import com.bnyro.clock.navigation.HomeRoutes
import com.bnyro.clock.social.data.SocialLink
import com.bnyro.clock.social.data.SocialPreferences
import com.bnyro.clock.util.Preferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SocialActivityCoordinator(private val activity: ComponentActivity) {
    private var liveSyncJob: Job? = null

    fun receiveLink(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val value = intent.getStringExtra(SocialLink.EXTRA_LINK) ?: intent.dataString ?: return false
        val link = SocialLink.parse(value) ?: return false
        if (activity.packageName == "com.rispng.jay.lite") {
            val fullIntent = Intent(Intent.ACTION_VIEW, intent.data).apply {
                setClassName("com.rispng.jay", "com.bnyro.clock.ui.MainActivity")
                putExtra(SocialLink.EXTRA_LINK, value)
            }
            if (activity.packageManager.resolveActivity(fullIntent, 0) != null) {
                activity.startActivity(fullIntent)
                intent.data = null
                intent.removeExtra(SocialLink.EXTRA_LINK)
                activity.finish()
                return true
            }
        }
        val key = when (link.destination) {
            "join" -> SocialPreferences.pendingInvitationKey
            "profile" -> SocialPreferences.pendingProfileKey
            else -> return false
        }
        Preferences.edit { putString(key, value) }
        intent.data = null
        intent.removeExtra(SocialLink.EXTRA_LINK)
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
                    (activity.application as App).container.socialRepository.followLiveChanges()
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
