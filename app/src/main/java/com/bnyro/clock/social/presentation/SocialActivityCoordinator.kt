package com.bnyro.clock.social.presentation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import com.bnyro.clock.navigation.HomeRoutes
import com.bnyro.clock.social.data.GroupRingService
import com.bnyro.clock.social.data.SocialLink
import com.bnyro.clock.social.data.SocialPreferences
import com.bnyro.clock.util.Preferences

class SocialActivityCoordinator(private val activity: ComponentActivity) {
    private var liveSyncBound = false
    private val liveSyncConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    fun receiveLink(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val value = intent.getStringExtra(SocialLink.EXTRA_LINK) ?: intent.dataString ?: return false
        val link = SocialLink.parse(value) ?: return false
        if (activity.packageName == "com.rispng.jay.lite") {
            val fullIntent = Intent(Intent.ACTION_VIEW, value.toUri()).apply {
                setClassName("com.rispng.jay", "com.bnyro.clock.ui.MainActivity")
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
        if (!liveSyncBound) {
            liveSyncBound = activity.bindService(
                Intent(activity, GroupRingService::class.java),
                liveSyncConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    fun stopLiveSync() {
        if (liveSyncBound) {
            activity.unbindService(liveSyncConnection)
            liveSyncBound = false
        }
    }
}
