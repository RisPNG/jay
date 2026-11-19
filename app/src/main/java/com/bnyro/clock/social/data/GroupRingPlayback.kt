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
    private val onChanged: (active: List<JsonObject>, completed: List<JsonObject>) -> Unit = { _, _ -> }
) : RingingSession {
    override val soundEnabled = true
    override val vibrate = false
    override val timeoutMillis = 0L
    override val isAlarm = false

    private val handler = Handler(Looper.getMainLooper())
    private val leases = linkedMapOf<String, Pair<Long, JsonObject>>()
    private var player: MediaPlayer? = null
    private val expiry = Runnable { updateLeases() }

    fun receive(event: JsonObject?) {
        var released: JsonObject? = null
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
                    leases[session] = (SystemClock.elapsedRealtime() + remaining) to event
                }
            } else {
                released = leases.remove(session)?.second
            }
        }
        updateLeases(released)
    }

    private fun updateLeases(released: JsonObject? = null) {
        handler.removeCallbacks(expiry)
        val now = SystemClock.elapsedRealtime()
        val expired = mutableListOf<Pair<Long, JsonObject>>()
        leases.values.removeAll { lease ->
            if (lease.first > now) return@removeAll false
            expired += lease
            true
        }
        if (leases.isEmpty()) {
            RingingCoordinator.stop(this)
        } else {
            RingingCoordinator.start(this)
            handler.postDelayed(expiry, leases.values.minOf { it.first } - SystemClock.elapsedRealtime())
        }
        onChanged(leases.values.map { it.second }, expired.sortedBy { it.first }.map { it.second } + listOfNotNull(released))
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
