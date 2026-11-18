package com.bnyro.clock.util

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri

internal class RingingAudio(
    private val context: Context,
    private val requestFocus: Boolean = true
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var playback: Playback? = null

    fun start(soundUri: String?, rampSeconds: Int, deadline: Long) {
        stop()
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) return

        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val sound = soundUri?.toUri() ?: defaultSound
        val next = Playback(rampSeconds, SystemClock.uptimeMillis() + remaining, defaultSound.takeUnless { it == sound })
        playback = next
        next.start(sound)
    }

    fun stop() {
        val ending = playback ?: return
        playback = null
        ending.release()
    }

    private inner class Playback(
        private val rampSeconds: Int,
        private val deadlineUptime: Long,
        private var fallbackSound: Uri?
    ) {
        private var player: MediaPlayer? = null
        private var ramp: VolumeRamp? = null
        private var started = false
        private var audible = false
        private var focus = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        private var focusRequest: AudioFocusRequest? = null
        private var modeListener: AudioManager.OnModeChangedListener? = null
        private val active get() = playback === this && SystemClock.uptimeMillis() < deadlineUptime
        private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            if (active) {
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        focus = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                        updatePlayback()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        focus = AudioManager.AUDIOFOCUS_REQUEST_FAILED
                        updatePlayback()
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> stop()
                }
            }
        }

        fun start(sound: Uri) {
            prepare(sound)
            if (!active) return

            val gain = if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            } else {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            }
            focus = when {
                !requestFocus -> AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    val request = AudioFocusRequest.Builder(gain)
                        .setAudioAttributes(NotificationHelper.audioAttributes)
                        .setAcceptsDelayedFocusGain(true)
                        .setOnAudioFocusChangeListener(focusListener, handler)
                        .build()
                    focusRequest = request
                    audioManager.requestAudioFocus(request)
                }
                else -> {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_ALARM, gain)
                }
            }
            if (focus == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                stop()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modeListener = AudioManager.OnModeChangedListener { updatePlayback() }.also {
                    audioManager.addOnModeChangedListener(context.mainExecutor, it)
                }
            }
            updatePlayback()
        }

        private fun prepare(sound: Uri) {
            ramp?.cancel()
            player?.release()
            started = false
            audible = false
            val source = MediaPlayer()
            player = source
            ramp = VolumeRamp(source, rampSeconds)
            source.setOnErrorListener { failed, what, extra ->
                if (active && player === failed) {
                    Log.e("RingingAudio", "Could not play the ringtone ($what/$extra)")
                    recover()
                }
                true
            }
            try {
                source.setDataSource(context, sound)
                source.setAudioAttributes(NotificationHelper.audioAttributes)
                source.isLooping = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    val speaker = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE }
                    if (speaker != null) source.setPreferredDevice(speaker)
                    source.addOnRoutingChangedListener({
                        if (player === source) updatePlayback()
                    }, handler)
                }
                source.setVolume(0f, 0f)
                source.prepare()
            } catch (error: Exception) {
                Log.e("RingingAudio", "Could not open the ringtone", error)
                recover()
            }
        }

        private fun recover() {
            val fallback = fallbackSound
            fallbackSound = null
            if (fallback == null) {
                stop()
            } else {
                prepare(fallback)
                updatePlayback()
            }
        }

        private fun updatePlayback() {
            if (!active) return
            val source = player ?: return
            val mode = audioManager.mode
            val verifySpeaker = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION)
            val canPlay = focus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ||
                (verifySpeaker && focus == AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
            if (canPlay != started) {
                try {
                    if (canPlay) source.start() else source.pause()
                    started = canPlay
                } catch (error: IllegalStateException) {
                    Log.e("RingingAudio", "Could not change ringtone playback", error)
                    recover()
                    return
                }
            }

            val shouldSound = started && (!verifySpeaker || isOnSpeaker(source))
            if (shouldSound == audible) return
            audible = shouldSound
            if (!audible) {
                ramp?.cancel()
                source.setVolume(0f, 0f)
            } else if (rampSeconds > 0) {
                ramp?.start()
            } else {
                source.setVolume(1f, 1f)
            }
        }

        private fun isOnSpeaker(source: MediaPlayer): Boolean {
            val outputs = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> source.routedDevices
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> listOfNotNull(source.routedDevice)
                else -> return false
            }
            return outputs.isNotEmpty() && outputs.all {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
            }
        }

        fun release() {
            ramp?.cancel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modeListener?.let(audioManager::removeOnModeChangedListener)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let(audioManager::abandonAudioFocusRequest)
            } else if (requestFocus) {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
            player?.release()
        }
    }
}
