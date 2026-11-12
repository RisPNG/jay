package com.bnyro.clock.social.data

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.bnyro.clock.util.Preferences
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.time.Instant

class SocialPushMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["kind"]) {
            "sync" -> SocialSyncWorker.enqueue(this, expedited = true)
            "ring" -> {
                if (message.priority != RemoteMessage.PRIORITY_HIGH) return
                val server = Preferences.instance.getString(
                    SocialPreferences.serverUrlKey,
                    SocialRepository.DEFAULT_SERVER_URL
                ) ?: SocialRepository.DEFAULT_SERVER_URL
                if (message.data["server_url"]?.trimEnd('/') != URI(server).normalize().toString().trimEnd('/')) return
                if (message.data["identity_id"] != DeviceIdentityStore.loadOrCreate(this, server).id) return
                val session = message.data["session_id"] ?: return
                val group = message.data["group_id"] ?: return
                val expiresAt = message.data["expires_at"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return
                val clock = runBlocking { SocialDatabase.getDatabase(this@SocialPushMessagingService).socialDao().getClock() }
                val serverTime = when {
                    clock == null -> System.currentTimeMillis()
                    clock.bootCount == Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0) ->
                        clock.serverMillis + SystemClock.elapsedRealtime() - clock.elapsedMillis
                    else -> System.currentTimeMillis() + clock.serverMillis - clock.wallMillis
                }
                val remaining = (expiresAt.toEpochMilli() - serverTime).coerceAtMost(5_000L)
                if (remaining <= 0) return
                val intent = Intent(this, GroupRingService::class.java)
                    .setAction(GroupRingService.ACTION_WAKE)
                    .putExtra(GroupRingService.EXTRA_SESSION_ID, session)
                    .putExtra(GroupRingService.EXTRA_GROUP_ID, group)
                    .putExtra(GroupRingService.EXTRA_EXPIRES_AT, expiresAt.toString())
                    .putExtra(GroupRingService.EXTRA_LEASE_MILLIS, 5_000L)
                try {
                    ContextCompat.startForegroundService(this, intent)
                } catch (error: IllegalStateException) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || error !is ForegroundServiceStartNotAllowedException) throw error
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        PushRegistrationWorker.enqueue(this, token)
    }
}
