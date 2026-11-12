package com.bnyro.clock.util.services

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.IBinder
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.bnyro.clock.App
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.Permission
import com.bnyro.clock.presentation.screens.alarm.AlarmActivity
import com.bnyro.clock.presentation.screens.ringing.RingingActivity
import com.bnyro.clock.ui.MainActivity
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.NotificationHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.TimeHelper
import com.bnyro.clock.util.RingingCoordinator
import com.bnyro.clock.util.RingingSession
import com.bnyro.clock.util.VolumeRamp
import com.bnyro.clock.util.widgets.TextColor
import com.bnyro.clock.util.widgets.getColorValue
import kotlinx.coroutines.runBlocking

import com.bnyro.clock.social.data.SocialAlarmEvents

import com.bnyro.clock.social.data.SocialActivityWorker

import com.bnyro.clock.social.data.SocialIgnoredAlarmWorker

import com.bnyro.clock.social.domain.AlarmActivityKind

import java.time.Instant

import com.bnyro.clock.social.data.SocialPreferences

class AlarmService : Service() {
    private var isPlaying = false
    private var vibrator: Vibrator? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val ringingAlarms = linkedMapOf<Long, AlarmRing>()
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var volumeRamp: VolumeRamp? = null

    private inner class AlarmRing(val alarm: Alarm, val ringTime: Long, val occurrenceId: String) : RingingSession {
        var alertScreenVisible = false
        var alertGeneration = 0L
        val notificationId = ALARM_NOTIFICATION_ID_OFFSET + alarm.id.toInt()
        val timeout = Runnable { handleTimeout(this) }
        override val soundEnabled get() = alarm.soundEnabled
        override val vibrate get() = alarm.vibrate
        override val timeoutMillis get() = alarm.timeoutMinutes * 60_000L
        override val isAlarm = true

        override fun onSelected() {
            alertGeneration++
            play(alarm)
            startForeground(notificationId, createNotification(this@AlarmService, this))
            startActivity(alertIntent(this))
        }

        override fun onSuppressed() {
            alertScreenVisible = false
            stopAudio()
            closeAlert(this)
            if (ringingAlarms[alarm.id] === this) {
                NotificationManagerCompat.from(this@AlarmService)
                    .notify(notificationId, createNotification(this@AlarmService, this))
            }
        }
    }

    private val alarmActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val ring = ringingAlarms[intent.getLongExtra(AlarmHelper.EXTRA_ID, -1L)] ?: return
            if (intent.action == CANCEL_SHARED_ALARM_INTENT_ACTION) {
                endRinging(ring)
                return
            }
            if (intent.getLongExtra(AlarmHelper.EXTRA_TRIGGER_TIME, -1L) != ring.ringTime) return
            val deliveredOccurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE_ID)
            if (deliveredOccurrenceId != null && deliveredOccurrenceId != ring.occurrenceId) return
            when (val action = intent.getStringExtra(ACTION_EXTRA_KEY)) {
                DISMISS_ACTION -> {
                    SocialAlarmEvents.dismiss(this@AlarmService, ring.alarm.id, ring.occurrenceId)
                    AlarmHelper.enqueue(this@AlarmService, ring.alarm, skipToday = true)
                    endRinging(ring)
                }
                SNOOZE_ACTION -> {
                    if (!ring.alarm.canSnooze) return
                    val occurredAt = System.currentTimeMillis()
                    val snoozedUntil = AlarmHelper.snooze(
                        this@AlarmService, ring.alarm, ring.ringTime,
                        intent.getIntExtra(EXTRA_SNOOZE_MINUTES, ring.alarm.snoozeMinutes)
                    ) ?: return
                    SocialAlarmEvents.snooze(
                        this@AlarmService, ring.alarm, snoozedUntil,
                        ring.occurrenceId, occurredAtMillis = occurredAt
                    )
                    endRinging(ring)
                }
                ALERT_SHOWN_ACTION, ALERT_HIDDEN_ACTION -> {
                    if (intent.getLongExtra(RingingActivity.ALERT_GENERATION_EXTRA_KEY, -1L) != ring.alertGeneration) return
                    ring.alertScreenVisible = action == ALERT_SHOWN_ACTION
                    NotificationManagerCompat.from(this@AlarmService)
                        .notify(ring.notificationId, createNotification(this@AlarmService, ring))
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        ContextCompat.registerReceiver(
            this,
            alarmActionReceiver,
            IntentFilter(ALARM_INTENT_ACTION).apply {
                addAction(CANCEL_SHARED_ALARM_INTENT_ACTION)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        super.onCreate()
    }

    override fun onDestroy() {
        val rings = ringingAlarms.values.toList()
        ringingAlarms.clear()
        RingingCoordinator.stop(*rings.toTypedArray())
        rings.forEach {
            timeoutHandler.removeCallbacks(it.timeout)
            closeAlert(it)
            NotificationManagerCompat.from(this).cancel(it.notificationId)
        }
        stopAudio()
        unregisterReceiver(alarmActionReceiver)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(AlarmHelper.EXTRA_ID, -1).takeIf { it != -1L }
            ?: return START_STICKY
        val alarmRepository = (application as App).container.alarmRepository
        val alarm = runBlocking {
            alarmRepository.getAlarmById(id)
        } ?: return START_STICKY
        val activeOccurrenceId = Preferences.instance.getString("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}", null)
        val deliveredOccurrenceId = intent?.getStringExtra(EXTRA_OCCURRENCE_ID)
        if (activeOccurrenceId != null && deliveredOccurrenceId != null && deliveredOccurrenceId != activeOccurrenceId) {
            if (ringingAlarms.isNotEmpty()) return START_STICKY
            stopSelf()
            return START_NOT_STICKY
        }
        var triggerTime = intent?.getLongExtra(
            AlarmHelper.EXTRA_TRIGGER_TIME,
            System.currentTimeMillis()
        ) ?: System.currentTimeMillis()
        val scheduledOccurrenceId = deliveredOccurrenceId ?: activeOccurrenceId ?: "${alarm.id}:$triggerTime"
        val timeoutMillis = alarm.timeoutMinutes * 60_000L
        var originalDeadlineAt = triggerTime + timeoutMillis
        val deadlineKey = "${SocialPreferences.alarmDeadlinePrefix}${alarm.id}"
        val activeDeadline = Preferences.instance.getLong(deadlineKey, -1L)
        if (Preferences.instance.contains(deadlineKey) && activeDeadline != originalDeadlineAt) {
            if (ringingAlarms[alarm.id]?.ringTime == activeDeadline - alarm.timeoutMinutes * 60_000L) {
                return START_STICKY
            }
            if (activeDeadline > System.currentTimeMillis()) {
                AlarmHelper.snooze(this, alarm, snoozedUntil = activeDeadline - timeoutMillis)
                ringingAlarms[alarm.id]?.let { endRinging(it, keepService = true) }
                if (ringingAlarms.isNotEmpty()) return START_STICKY
                stopSelf()
                return START_NOT_STICKY
            }
            if (activeDeadline > 0L && alarm.missedRestartEnabled) {
                triggerTime = activeDeadline - timeoutMillis
                originalDeadlineAt = activeDeadline
            } else {
                ringingAlarms[alarm.id]?.let { endRinging(it, keepService = true) }
                if (ringingAlarms.isNotEmpty()) return START_STICKY
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val now = System.currentTimeMillis()
        if (alarm.missedRestartEnabled && now >= originalDeadlineAt) {
            val restartDelayMillis = alarm.missedRestartMinutes * 60_000L
            val cycleMillis = timeoutMillis + restartDelayMillis
            triggerTime += ((now - triggerTime) / cycleMillis) * cycleMillis
            val phaseDeadline = triggerTime + timeoutMillis
            val shared = Preferences.instance.contains("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}")
            val lastSnoozedAt = if (now < phaseDeadline) triggerTime - restartDelayMillis else phaseDeadline
            if (shared) {
                SocialActivityWorker.enqueue(
                    this,
                    alarm.id,
                    AlarmActivityKind.SNOOZED,
                    scheduledOccurrenceId,
                    reason = "timed_out",
                    snoozedUntil = Instant.ofEpochMilli(originalDeadlineAt + restartDelayMillis).toString(),
                    occurredAt = Instant.ofEpochMilli(originalDeadlineAt).toString()
                )
                Preferences.edit {
                    putLong("${SocialPreferences.alarmSnoozedAtPrefix}${alarm.id}", lastSnoozedAt)
                    putLong(deadlineKey, phaseDeadline)
                }
            }
            if (now >= phaseDeadline) {
                val nextRing = AlarmHelper.snooze(this, alarm, snoozedUntil = triggerTime + cycleMillis)
                if (shared) {
                    SocialIgnoredAlarmWorker.schedule(this, alarm, nextRing, scheduledOccurrenceId, snoozedAtMillis = lastSnoozedAt)
                }
                ringingAlarms[alarm.id]?.let { endRinging(it, keepService = true) }
                if (ringingAlarms.isNotEmpty()) return START_STICKY
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val existing = ringingAlarms[id]
        if (existing?.ringTime == triggerTime && existing.occurrenceId == scheduledOccurrenceId) return START_STICKY
        existing?.let { endRinging(it, keepService = true) }
        val ring = AlarmRing(alarm.copy(), triggerTime, scheduledOccurrenceId)
        ringingAlarms[id] = ring
        val foreground = ringingAlarms.values.firstOrNull { RingingCoordinator.selected === it } ?: ring
        startForeground(foreground.notificationId, createNotification(this, foreground))
        if (foreground !== ring) {
            NotificationManagerCompat.from(this).notify(ring.notificationId, createNotification(this, ring))
        }
        timeoutHandler.postDelayed(
            ring.timeout,
            (triggerTime + timeoutMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        )
        RingingCoordinator.start(ring)
        return START_STICKY
    }

    private fun handleTimeout(ring: AlarmRing) {
        if (ringingAlarms[ring.alarm.id] !== ring) return
        val alarm = ring.alarm
        val deadlineAt = ring.ringTime + ring.timeoutMillis
        if (alarm.missedRestartEnabled) {
            val snoozedUntil = AlarmHelper.snooze(
                this, alarm, snoozedUntil = deadlineAt + alarm.missedRestartMinutes * 60_000L
            )
            SocialAlarmEvents.snooze(
                this, alarm, snoozedUntil, ring.occurrenceId,
                reason = "timed_out", occurredAtMillis = deadlineAt
            )
        } else {
            if (Permission.NotificationPermission.hasPermission(this)) {
                val formattedRingTime = TimeHelper.formatTime(
                    this, java.time.Instant.ofEpochMilli(ring.ringTime).atZone(java.time.ZoneId.systemDefault())
                )
                val contentIntent = PendingIntent.getActivity(
                    this, alarm.id.toInt(),
                    Intent(this, MainActivity::class.java)
                        .setAction(android.provider.AlarmClock.ACTION_SHOW_ALARMS),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                NotificationManagerCompat.from(this).notify(
                    alarm.id.toInt() + MISSED_ALARM_ID_OFFSET,
                    NotificationCompat.Builder(this, NotificationHelper.MISSED_ALARM_CHANNEL)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setSilent(true)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setContentTitle(
                            alarm.label?.takeIf { it.isNotBlank() }?.let {
                                getString(R.string.named_alarm_missed, it, formattedRingTime)
                            } ?: getString(R.string.alarm_missed, formattedRingTime)
                        )
                        .setContentText(resources.getQuantityString(
                            R.plurals.alarm_rang_for_minutes, alarm.timeoutMinutes, alarm.timeoutMinutes
                        ))
                        .setContentIntent(contentIntent)
                        .setAutoCancel(true)
                        .build()
                )
            }
            SocialAlarmEvents.ignore(this, alarm.id, ring.occurrenceId, "timed_out")
        }
        endRinging(ring)
    }

    private fun endRinging(ring: AlarmRing, keepService: Boolean = false) {
        ringingAlarms.remove(ring.alarm.id)
        timeoutHandler.removeCallbacks(ring.timeout)
        RingingCoordinator.stop(ring)
        closeAlert(ring)
        val remaining = ringingAlarms.values.firstOrNull { RingingCoordinator.selected === it }
            ?: ringingAlarms.values.firstOrNull()
        if (remaining != null) {
            startForeground(remaining.notificationId, createNotification(this, remaining))
        } else if (!keepService) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        NotificationManagerCompat.from(this).cancel(ring.notificationId)
    }

    private fun alertIntent(ring: AlarmRing) =
        Intent(this, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .putExtra(AlarmHelper.EXTRA_ID, ring.alarm.id)
            .putExtra(EXTRA_OCCURRENCE_ID, ring.occurrenceId)
            .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ring.ringTime)
            .putExtra(RingingActivity.ALERT_GENERATION_EXTRA_KEY, ring.alertGeneration)

    private fun closeAlert(ring: AlarmRing) {
        val selected = RingingCoordinator.selected as? AlarmRing
        if (selected != null) {
            if (ring.alertScreenVisible) startActivity(alertIntent(selected))
            return
        }
        sendBroadcast(Intent(AlarmActivity.ALARM_ALERT_CLOSE_ACTION)
            .putExtra(RingingActivity.ACTION_EXTRA_KEY, RingingActivity.CLOSE_ACTION)
            .putExtra(AlarmHelper.EXTRA_ID, ring.alarm.id)
            .putExtra(EXTRA_OCCURRENCE_ID, ring.occurrenceId)
            .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ring.ringTime)
            .putExtra(RingingActivity.ALERT_GENERATION_EXTRA_KEY, ring.alertGeneration)
            .setPackage(packageName))
    }

    private fun play(alarm: Alarm) {
        stopAudio()
        if (alarm.soundEnabled) {
            val alert: Uri? = alarm.soundUri?.toUri() ?: RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_ALARM
            )
            mediaPlayer = MediaPlayer()
            mediaPlayer!!.setOnErrorListener { mp, _, _ ->
                Log.e("Media Player", "Error occurred while playing audio.")
                mp.release()
                mediaPlayer = null
                if (alarm.soundUri != null) {
                    runCatching {
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(
                                this@AlarmService,
                                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            )
                            startAlarm(this)
                        }
                    }
                }
                true
            }
            try {
                mediaPlayer!!.setDataSource(this, alert!!)
                startAlarm(mediaPlayer!!)
            } catch (e: Exception) {
                Log.e("Failed to play ringtone", e.message, e)
                if (alarm.soundUri != null) {
                    runCatching {
                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(
                                this@AlarmService,
                                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            )
                            startAlarm(this)
                        }
                    }
                }
            }
        }
        /* Start the vibrator after everything is ok with the media player */
        if (alarm.vibrate) {
            val vibrationPattern = alarm.vibrationPattern.map(Int::toLong).toLongArray()
            vibrator!!.vibrate(vibrationPattern, 0, NotificationHelper.audioAttributes)
        } else {
            vibrator!!.cancel()
        }
        isPlaying = true
    }

    private fun startAlarm(player: MediaPlayer) {
        player.isLooping = true
        player.setAudioAttributes(NotificationHelper.audioAttributes)
        player.prepare()
        volumeRamp?.cancel()
        val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(NotificationHelper.audioAttributes)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        volumeRamp = VolumeRamp(
            player,
            Preferences.instance.getInt(Preferences.alarmVolumeRampSecondsKey, 0)
        ).apply { start() }
        player.start()
    }
    private fun stopAudio() {
        if (!isPlaying) return
        isPlaying = false

        volumeRamp?.cancel()
        volumeRamp = null
        // Stop audio playing
        if (mediaPlayer != null) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        // Stop vibrator
        vibrator?.cancel()

    }

    private fun createNotification(context: Context, ring: AlarmRing): Notification {
        val alarm = ring.alarm
        val selected = RingingCoordinator.selected === ring
        NotificationManagerCompat.from(context).cancel(
            alarm.id.toInt() + AlarmHelper.PRE_ALARM_ID_OFFSET
        )
        val alarmActivityIntent = alertIntent(ring)

        val pendingIntent = PendingIntent.getActivity(
            this@AlarmService,
            ring.notificationId,
            alarmActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                val backgroundActivityStartMode =
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    } else {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    }
                ActivityOptions.makeBasic().apply {
                    pendingIntentCreatorBackgroundActivityStartMode =
                        backgroundActivityStartMode
                }.toBundle()
            } else {
                null
            }
        )




        val dismissAlarmIntent = Intent(ALARM_INTENT_ACTION)
            .putExtra(ACTION_EXTRA_KEY, DISMISS_ACTION)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
            .putExtra(EXTRA_OCCURRENCE_ID, ring.occurrenceId)
            .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ring.ringTime)
        val onDeleteIntent = getPendingIntent(dismissAlarmIntent, 1)

        val dismissIntent = Intent(dismissAlarmIntent)
        val dismissAction = NotificationCompat.Action.Builder(
            null,
            getString(R.string.dismiss),
            getPendingIntent(dismissIntent, 2)
        )
        val targetAlarmTimeMs = ring.ringTime

        return NotificationCompat.Builder(context, NotificationHelper.ALARM_CHANNEL).apply {
            val formattedTime = TimeHelper.formatTime(
                context,
                java.time.Instant.ofEpochMilli(targetAlarmTimeMs)
                    .atZone(java.time.ZoneId.systemDefault())
            )

            setSmallIcon(R.drawable.ic_notification)
            setContentTitle(
                alarm.label?.takeIf { it.isNotBlank() }?.let { label ->
                    context.getString(
                        R.string.ringing_named_alarm,
                        label,
                        formattedTime
                    )
                } ?: context.getString(R.string.ringing_alarm, formattedTime)
            )
            setAutoCancel(true)
            setColorized(true)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setColor(TextColor.PrimaryDark.getColorValue(context))
            priority = NotificationCompat.PRIORITY_MAX
            foregroundServiceBehavior = FOREGROUND_SERVICE_IMMEDIATE
            setCategory(NotificationCompat.CATEGORY_ALARM)
            setContentIntent(pendingIntent)
            setSilent(ring.alertScreenVisible || !selected)
            if (selected && !ring.alertScreenVisible) setFullScreenIntent(pendingIntent, true)
            if (alarm.canSnooze) {
                val snoozeIntent = Intent(ALARM_INTENT_ACTION)
                    .putExtra(ACTION_EXTRA_KEY, SNOOZE_ACTION)
                    .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
                    .putExtra(EXTRA_OCCURRENCE_ID, ring.occurrenceId)
                    .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ring.ringTime)
                addAction(
                    NotificationCompat.Action.Builder(
                        null,
                        getString(R.string.snooze),
                        getPendingIntent(snoozeIntent, 3)
                    ).build()
                )
            }
            addAction(dismissAction.build())
            setDeleteIntent(onDeleteIntent)
            setOngoing(false)
        }.build()
    }
    private fun getPendingIntent(intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode + intent.getLongExtra(AlarmHelper.EXTRA_ID, -1L).toInt() * 10,
            intent.setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val ALARM_INTENT_ACTION = "com.bnyro.clock.ALARM_ACTION"
        const val ACTION_EXTRA_KEY = "action"
        const val DISMISS_ACTION = "DISMISS"
        const val SNOOZE_ACTION = "SNOOZE"
        const val ALERT_SHOWN_ACTION = "ALERT_SHOWN"
        const val ALERT_HIDDEN_ACTION = "ALERT_HIDDEN"
        const val CANCEL_SHARED_ALARM_INTENT_ACTION = "com.rispng.jay.CANCEL_SHARED_ALARM"
        const val EXTRA_OCCURRENCE_ID = "com.rispng.jay.ALARM_OCCURRENCE_ID"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
        private const val ALARM_NOTIFICATION_ID_OFFSET = 20_000
        private const val MISSED_ALARM_ID_OFFSET = 8000

    }
}
