package com.bnyro.clock.social.data

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.bnyro.clock.util.NotificationHelper
import com.bnyro.clock.util.RingingCoordinator
import com.bnyro.clock.util.RingingSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant

class GroupRingPlayback(
    private val context: Context,
    private val onChanged: (List<JsonObject>) -> Unit = {}
) : RingingSession {
    override val soundEnabled = true
    override val vibrate = false
    override val timeoutMillis = 0L
    override val isAlarm = false

    private val handler = Handler(Looper.getMainLooper())
    private val leases = linkedMapOf<String, Pair<Long, JsonObject>>()
    private var player: MediaPlayer? = null
    private val expiry = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            leases.entries.removeAll { it.value.first <= now }
            if (leases.isEmpty()) {
                RingingCoordinator.stop(this@GroupRingPlayback)
            } else {
                handler.postDelayed(this, leases.values.minOf { it.first } - now)
            }
            onChanged(leases.values.map { it.second })
        }
    }

    fun receive(event: JsonObject?) {
        handler.removeCallbacks(expiry)
        val elapsed = SystemClock.elapsedRealtime()
        leases.entries.removeAll { it.value.first <= elapsed }
        if (event == null) {
            leases.clear()
        } else {
            val session = requireNotNull(event["session_id"]).jsonPrimitive.content
            if (requireNotNull(event["active"]).jsonPrimitive.boolean) {
                val leaseMillis = requireNotNull(event["lease_seconds"]).jsonPrimitive.long * 1_000
                val remaining = event["expires_at"]?.jsonPrimitive?.content?.let { expiresAt ->
                    val now = event["server_time"]?.jsonPrimitive?.content?.let { Instant.parse(it).toEpochMilli() }
                        ?: System.currentTimeMillis()
                    (Instant.parse(expiresAt).toEpochMilli() - now).coerceAtMost(leaseMillis)
                } ?: leaseMillis
                if (remaining > 0) {
                    leases[session] = (elapsed + remaining) to event
                }
            } else {
                leases.remove(session)
            }
        }
        if (leases.isEmpty()) {
            RingingCoordinator.stop(this)
        } else {
            RingingCoordinator.start(this)
            handler.postDelayed(expiry, leases.values.minOf { it.first } - SystemClock.elapsedRealtime())
        }
        onChanged(leases.values.map { it.second })
    }

    override fun onSelected() {
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        mediaPlayer.apply {
            setAudioAttributes(NotificationHelper.audioAttributes)
            isLooping = true
            setOnPreparedListener { if (RingingCoordinator.selected === this@GroupRingPlayback) it.start() }
            setOnErrorListener { _, _, _ ->
                RingingCoordinator.stop(this@GroupRingPlayback)
                true
            }
            runCatching {
                setDataSource(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                prepareAsync()
            }.onFailure { RingingCoordinator.stop(this@GroupRingPlayback) }
        }
    }

    override fun onSuppressed() {
        player?.release()
        player = null
    }
}
