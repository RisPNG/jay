package com.bnyro.clock.social.data

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bnyro.clock.App
import com.bnyro.clock.R
import com.bnyro.clock.util.NotificationHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.widgets.TextColor
import com.bnyro.clock.util.widgets.getColorValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class GroupRingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val binder = Binder()
    private val pending = mutableMapOf<String, Long>()
    private var rings = emptyList<JsonObject>()
    private var liveJob: Job? = null
    private var foreground = false
    private var started = false
    private var destroying = false
    private lateinit var playback: GroupRingPlayback
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var identityPreferences: SharedPreferences
    private val pendingExpiry = Runnable { updateRingingState() }
    private val profileListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == SocialPreferences.serverUrlKey || key == SocialPreferences.deviceSecretKey) {
            pending.clear()
            playback.receive(null)
            restartLiveChanges()
        }
    }

    override fun onCreate() {
        super.onCreate()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GroupRingService::Lock").apply {
                setReferenceCounted(false)
            }
        playback = GroupRingPlayback(this) {
            rings = it
            if (!destroying) updateRingingState()
        }
        identityPreferences = getSharedPreferences("jay_identity", Context.MODE_PRIVATE)
        identityPreferences.registerOnSharedPreferenceChangeListener(profileListener)
        Preferences.instance.registerOnSharedPreferenceChangeListener(profileListener)
        restartLiveChanges()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        started = true
        if (intent?.action == ACTION_WAKE) {
            showRingingNotification()
            val session = requireNotNull(intent.getStringExtra(EXTRA_SESSION_ID))
            val now = SystemClock.elapsedRealtime()
            endedSessions.entries.removeAll { it.value <= now }
            val remaining = intent.getLongExtra(EXTRA_LEASE_MILLIS, 0L).coerceAtMost(5_000L)
            if (remaining > 0 && session !in endedSessions &&
                rings.none { it["session_id"]?.jsonPrimitive?.content == session }
            ) {
                pending.putIfAbsent(session, now + remaining)
            }
        }
        updateRingingState()
        return START_NOT_STICKY
    }

    fun receive(event: JsonObject?) {
        event?.let {
            val session = requireNotNull(it["session_id"]).jsonPrimitive.content
            pending.remove(session)
            if (!requireNotNull(it["active"]).jsonPrimitive.boolean) {
                val now = SystemClock.elapsedRealtime()
                endedSessions.entries.removeAll { ended -> ended.value <= now }
                endedSessions[session] = now + 10_000L
            }
        }
        playback.receive(event)
    }

    private fun restartLiveChanges() {
        val previous = liveJob
        liveJob = scope.launch {
            previous?.cancelAndJoin()
            while (isActive) {
                runCatching {
                    (application as App).container.socialRepository.followLiveChanges { receive(it) }
                }
                if (isActive) delay(2_000)
            }
        }
    }

    private fun updateRingingState() {
        handler.removeCallbacks(pendingExpiry)
        val now = SystemClock.elapsedRealtime()
        pending.entries.removeAll { it.value <= now }
        if (rings.isEmpty() && pending.isEmpty()) {
            if (wakeLock.isHeld) wakeLock.release()
            if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
            started = false
            stopSelf()
            return
        }
        if (!started) {
            started = true
            startService(Intent(this, GroupRingService::class.java))
        }
        wakeLock.acquire(10_000L)
        showRingingNotification()
        pending.values.minOrNull()?.let { handler.postDelayed(pendingExpiry, it - now) }
    }

    private fun showRingingNotification() {
        val text = rings.mapNotNull {
            val actor = it["actor_label"]?.jsonPrimitive?.contentOrNull
            val group = it["group_label"]?.jsonPrimitive?.contentOrNull
            if (actor != null && group != null) getString(R.string.group_ring_notification_text, actor, group) else null
        }.distinct().joinToString("\n")
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, NotificationHelper.ALARM_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(if (rings.isEmpty()) R.string.group_ring_waiting else R.string.group_ring_notification_title))
                .setContentText(text.takeIf { it.isNotEmpty() })
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setColorized(true)
                .setColor(TextColor.PrimaryDark.getColorValue(this))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(true)
                .build()
        )
        foreground = true
    }

    override fun onDestroy() {
        destroying = true
        identityPreferences.unregisterOnSharedPreferenceChangeListener(profileListener)
        Preferences.instance.unregisterOnSharedPreferenceChangeListener(profileListener)
        scope.cancel()
        handler.removeCallbacks(pendingExpiry)
        pending.clear()
        playback.receive(null)
        if (wakeLock.isHeld) wakeLock.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_WAKE = "com.bnyro.clock.GROUP_RING_WAKE"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_EXPIRES_AT = "expires_at"
        const val EXTRA_LEASE_MILLIS = "lease_millis"
        const val NOTIFICATION_ID = 600_000_000
        private val endedSessions = mutableMapOf<String, Long>()
    }
}
