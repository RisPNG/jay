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

class GroupRingPlayback(private val context: Context) : RingingSession {
    override val soundEnabled = true
    override val vibrate = false
    override val timeoutMillis = 0L
    override val isAlarm = false

    private val handler = Handler(Looper.getMainLooper())
    private val leases = mutableMapOf<String, Long>()
    private var player: MediaPlayer? = null
    private val expiry = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            leases.entries.removeAll { it.value <= now }
            if (leases.isEmpty()) {
                RingingCoordinator.stop(this@GroupRingPlayback)
            } else {
                handler.postDelayed(this, leases.values.min() - now)
            }
        }
    }

    fun receive(event: JsonObject?) {
        handler.removeCallbacks(expiry)
        if (event == null) {
            leases.clear()
        } else {
            val session = requireNotNull(event["session_id"]).jsonPrimitive.content
            if (requireNotNull(event["active"]).jsonPrimitive.boolean) {
                leases[session] = SystemClock.elapsedRealtime() + requireNotNull(event["lease_seconds"]).jsonPrimitive.long * 1_000
            } else {
                leases.remove(session)
            }
        }
        if (leases.isEmpty()) {
            RingingCoordinator.stop(this)
        } else {
            RingingCoordinator.start(this)
            handler.postDelayed(expiry, leases.values.min() - SystemClock.elapsedRealtime())
        }
    }

    override fun onSelected() {
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        mediaPlayer.apply {
            setAudioAttributes(NotificationHelper.audioAttributes)
            isLooping = true
            setOnPreparedListener { if (RingingCoordinator.selected === this@GroupRingPlayback) it.start() }
            setOnErrorListener { _, _, _ ->
                onSuppressed()
                true
            }
            runCatching {
                setDataSource(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                prepareAsync()
            }.onFailure { onSuppressed() }
        }
    }

    override fun onSuppressed() {
        player?.release()
        player = null
    }
}
