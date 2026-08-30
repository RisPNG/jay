package com.bnyro.clock.util.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Vibrator
import android.provider.AlarmClock
import android.text.format.DateUtils
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.TimerDescriptor
import com.bnyro.clock.domain.model.TimerObject
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.domain.model.WatchState
import com.bnyro.clock.presentation.screens.ringing.RingingActivity
import com.bnyro.clock.presentation.screens.timer.TimerAlertActivity
import com.bnyro.clock.ui.MainActivity
import com.bnyro.clock.util.NotificationHelper
import com.bnyro.clock.util.Preferences

import java.util.Timer
import java.util.TimerTask

class TimerService : Service() {
    private val timer = Timer()
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var oldnow = System.currentTimeMillis()
    private val vibrator: Vibrator by lazy {
        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private lateinit var contentIntent: PendingIntent

    var onChangeTimers: (objects: Array<TimerObject>) -> Unit = {}
    var timerObjects = mutableListOf<TimerObject>()

    private var wakeLock: PowerManager.WakeLock? = null
    private var alertedTimerId: Int? = null

    @SuppressLint("ServiceCast", "ScheduleExactAlarm")
    private fun scheduleAlarm(timerObject: TimerObject) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(this, TimerAlarmReceiver::class.java).apply {
            putExtra(ID_EXTRA_KEY, timerObject.id)
            action = ACTION_TIMER_EXPIRED
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            timerObject.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + timerObject.currentPosition.value
        val alarmInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)

        try {
            alarmManager.setAlarmClock(alarmInfo, pendingIntent)
        } catch (e: SecurityException) {
            Log.e("TimerService", "timer error!", e)
        }
    }

    private fun cancelAlarm(timerObject: TimerObject) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, TimerAlarmReceiver::class.java).apply {
            action = ACTION_TIMER_EXPIRED
            putExtra(ID_EXTRA_KEY, timerObject.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            timerObject.id,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    private val incrementSeconds
        get() = Preferences.instance.getInt(Preferences.timerIncrementSecondsKey, 60)

    private val fullScreenAlertEnabled
        get() = Preferences.instance.getBoolean(Preferences.timerFullScreenAlertKey, true)

    private val receiver = object : BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.N)
        override fun onReceive(context: Context, intent: Intent) {
            Log.e("receive", intent.toString())
            val id = intent.getIntExtra(ID_EXTRA_KEY, 0)
            val obj = timerObjects.find { it.id == id } ?: return
            when (val action = intent.getStringExtra(ACTION_EXTRA_KEY)) {
                ACTION_STOP -> {
                    stop(obj, cancelled = true)
                }

                ACTION_PAUSE_RESUME -> {
                    if (obj.state.value == WatchState.PAUSED) resume(obj) else pause(obj)
                }

                ACTION_ALERT_SHOWN, ACTION_ALERT_HIDDEN -> {
                    alertedTimerId = obj.id.takeIf { action == ACTION_ALERT_SHOWN }
                    if (obj.currentPosition.value == 0) showFinishedNotification(obj)
                }

                ACTION_ADD_TIME -> {
                    // a timer that has finished ringing has run out of time to add to, so the
                    // time added starts it running again rather than sitting on a finished timer
                    val finished = obj.currentPosition.value == 0
                    if (finished) {
                        stopAudio()
                        closeAlert(obj)
                        oldnow = System.currentTimeMillis()
                        obj.state.value = WatchState.RUNNING
                    }

                    obj.currentPosition.value += incrementSeconds * 1000

                    if (obj.state.value == WatchState.RUNNING) {
                        cancelAlarm(obj)
                        scheduleAlarm(obj)
                        if (finished) acquireWakeLock()
                    }

                    if (finished) {
                        val notificationManager = NotificationManagerCompat.from(context)
                        notificationManager.cancel(finishedNotificationId(obj))
                        invokeChangeListener()
                    }

                    updateNotification(obj)
                }

                TIMER_RESTART -> {
                    stopAudio()
                    closeAlert(obj)

                    oldnow = System.currentTimeMillis()

                    // a finished timer has no run left to return to, so it starts a new one
                    if (obj.currentPosition.value == 0) obj.state.value = WatchState.RUNNING
                    obj.currentPosition.value = obj.initialPosition.value

                    cancelAlarm(obj)
                    if (obj.state.value == WatchState.RUNNING) {
                        scheduleAlarm(obj)
                        acquireWakeLock()
                    }

                    val notificationManager = NotificationManagerCompat.from(context)
                    notificationManager.cancel(finishedNotificationId(obj))
                    notificationManager.cancel(obj.id)

                    invokeChangeListener()
                    updateNotification(obj)
                }
            }
        }
    }

    private fun play(timerObject: TimerObject) {
        stopAudio()
        if (timerObject.soundEnabled) {
            val alert: Uri = timerObject.soundUri?.toUri() ?: RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_ALARM
            )

            mediaPlayer = MediaPlayer()

            try {
                mediaPlayer!!.setDataSource(this, alert)
                startAlarm(mediaPlayer!!)
            } catch (e: Exception) {
                Log.e("failed to play ringtone", e.message, e)
            }
        }
        if (timerObject.vibrate) {
            vibrator.vibrate(timerObject.vibrationPattern.map(Int::toLong).toLongArray(), 0)
        } else {
            vibrator.cancel()
        }
        isPlaying = true
    }

    private fun startAlarm(player: MediaPlayer) {
        player.isLooping = true
        player.setAudioAttributes(NotificationHelper.audioAttributes)
        player.prepare()
        player.start()
    }

    /**
     * Stops the audio and vibration
     */
    private fun stopAudio() {
        if (!isPlaying) return
        isPlaying = false

        if (mediaPlayer != null) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        }

        vibrator.cancel()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()

        contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(AlarmClock.ACTION_SHOW_TIMERS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // PARTIAL_WAKE_LOCK should make it so that uhhhhh the screen still turns off???
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TimerService::Lock").apply {
            setReferenceCounted(false)
        }

        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    handler.post(this@TimerService::updateState)
                }
            }, 0, UPDATE_DELAY.toLong()
        )
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(UPDATE_STATE_ACTION).apply { addDataScheme(UPDATE_STATE_SCHEME) },
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TIMER_EXPIRED) {
            val id = intent.getIntExtra(ID_EXTRA_KEY, 0)
            val obj = timerObjects.find { it.id == id }

            if (obj == null) {
                Log.e("TimerService", "error D:D:D:DD:D:D:D:D:D:")
                NotificationManagerCompat.from(this).cancel(id)
                if (timerObjects.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_STICKY
            }

            play(obj)
            cancelAlarm(obj)

            val notificationManager = NotificationManagerCompat.from(this)
            notificationManager.cancel(obj.id)

            if (timerObjects.size <= 1) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }

            showFinishedNotification(obj)
            if (fullScreenAlertEnabled) startActivity(alertIntent(obj))

            obj.currentPosition.value = 0
            obj.state.value = WatchState.PAUSED

            if (timerObjects.none { t -> t.state.value == WatchState.RUNNING }) {
                releaseWakeLock()
            }

            invokeChangeListener()

            return START_STICKY
        }
        val timer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(INITIAL_TIMER_EXTRA_KEY, TimerDescriptor::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(INITIAL_TIMER_EXTRA_KEY) as TimerDescriptor?
        }
        if (timer == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val scheduledObject = timer.asScheduledObject()
        scheduledObject.state.value = WatchState.RUNNING
        startForeground(scheduledObject.id, getNotification(scheduledObject))
        enqueueNew(scheduledObject)
        return START_STICKY
    }

    private fun getNotification(timerObject: TimerObject): Notification {
        val timeLeft = DateUtils.formatElapsedTime(timerObject.secondsLeft.toLong())

        return NotificationCompat.Builder(
            this, NotificationHelper.TIMER_CHANNEL
        ).setContentTitle(timeLeft)
            .setShortCriticalText(timeLeft)
            .setRequestPromotedOngoing(true)
            .setContentText(
                if (timerObject.state.value == WatchState.RUNNING) {
                    timerObject.label.value
                } else {
                    getString(R.string.paused_timer_title, timerObject.label.value)
                }
            )
            .setContentIntent(contentIntent)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .addAction(pauseResumeAction(timerObject))
            .addAction(
                if (timerObject.state.value == WatchState.RUNNING) {
                    addTimeAction(timerObject)
                } else {
                    resetAction(timerObject)
                }
            )
            .addAction(stopAction(timerObject))
            .setSmallIcon(R.drawable.ic_timer).setOngoing(true).build()
    }

    fun invokeChangeListener() {
        onChangeTimers.invoke(timerObjects.toTypedArray())
    }

    private fun updateState() {
        val now = System.currentTimeMillis()
        val delta = now - oldnow
        oldnow = now

        timerObjects.forEach {
            if (it.state.value == WatchState.RUNNING) {
                val before = it.secondsLeft
                it.currentPosition.value =
                    (it.currentPosition.value - delta.toInt()).coerceAtLeast(0)

                if (before != it.secondsLeft) {
                    updateNotification(it)
                }
            }
        }
    }

    fun enqueueNew(timerObject: TimerObject) {
        timerObject.state.value = WatchState.RUNNING
        timerObjects.add(timerObject)

        scheduleAlarm(timerObject)
        acquireWakeLock()

        invokeChangeListener()
        updateNotification(timerObject)
    }

    private fun pause(timerObject: TimerObject) {
        timerObject.state.value = WatchState.PAUSED
        cancelAlarm(timerObject)
        updateNotification(timerObject)

        if (timerObjects.none { it.state.value == WatchState.RUNNING }) {
            releaseWakeLock()
        }
    }

    private fun resume(timerObject: TimerObject) {
        timerObject.state.value = WatchState.RUNNING
        scheduleAlarm(timerObject)
        acquireWakeLock()
        updateNotification(timerObject)
    }

    private fun updateNotification(timerObject: TimerObject) {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this)
                .notify(timerObject.id, getNotification(timerObject))
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun stop(timerObject: TimerObject, cancelled: Boolean) {
        cancelAlarm(timerObject)
        stopAudio()
        timerObjects.remove(timerObject)

        if (timerObjects.none { it.state.value == WatchState.RUNNING }) {
            releaseWakeLock()
        }

        closeAlert(timerObject)
        invokeChangeListener()
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.cancel(timerObject.id)
        notificationManager.cancel(finishedNotificationId(timerObject))

        if (timerObjects.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finishedNotificationId(timerObject: TimerObject) =
        (Integer.MAX_VALUE / 3) + timerObject.id * 10

    /**
     * The screen a finished timer takes over the phone with, named after the timer it belongs to so
     * that whichever timer is showing can be answered on its own.
     */
    private fun alertIntent(timerObject: TimerObject) =
        Intent(this, TimerAlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .putExtra(ID_EXTRA_KEY, timerObject.id)
            .putExtra(LABEL_EXTRA_KEY, timerObject.label.value)

    private fun closeAlert(timerObject: TimerObject) {
        if (alertedTimerId == timerObject.id) alertedTimerId = null
        sendBroadcast(
            Intent(TIMER_ALERT_CLOSE_ACTION)
                .putExtra(RingingActivity.ACTION_EXTRA_KEY, RingingActivity.CLOSE_ACTION)
                .putExtra(ID_EXTRA_KEY, timerObject.id)
                .setPackage(packageName)
        )
    }

    private fun showFinishedNotification(timerObject: TimerObject) {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val notificationChannelId = NotificationHelper.TIMER_FINISHED_CHANNEL
        val finishedNotificationId = finishedNotificationId(timerObject)
        val alertShowing = alertedTimerId == timerObject.id

        val stopIntent = updateStateIntent(ACTION_STOP, timerObject.id)
        val stopPendingIntent = PendingIntent.getBroadcast(
            this,
            finishedNotificationId,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = NotificationCompat.Action.Builder(
            null, getString(R.string.stop), stopPendingIntent
        ).build()

        val restartIntent = updateStateIntent(TIMER_RESTART, timerObject.id)
        val restartPendingIntent = PendingIntent.getBroadcast(
            this,
            finishedNotificationId + 2,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val restartAction = NotificationCompat.Action.Builder(
            null, getString(R.string.timer_reset), restartPendingIntent
        ).build()

        val snoozeIntent = updateStateIntent(ACTION_ADD_TIME, timerObject.id)
        val snoozePendingIntent = PendingIntent.getBroadcast(
            this,
            finishedNotificationId + 3,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeAction = NotificationCompat.Action.Builder(
            null, addTimeLabel(), snoozePendingIntent
        ).build()

        val alertPendingIntent = PendingIntent.getActivity(
            this,
            finishedNotificationId + 4,
            alertIntent(timerObject),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deleteIntent = updateStateIntent(ACTION_STOP, timerObject.id)
        val deletePendingIntent = PendingIntent.getBroadcast(
            this, finishedNotificationId + 1, deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, notificationChannelId)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(getString(R.string.finished_named_timer, timerObject.label.value))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDeleteIntent(deletePendingIntent)
            .setOngoing(false)
            .setSilent(alertShowing)
            .apply {
                if (fullScreenAlertEnabled && !alertShowing) {
                    setFullScreenIntent(alertPendingIntent, true)
                }
            }
            .addAction(stopAction)
            .addAction(snoozeAction)
            .addAction(restartAction)
            .build()

        NotificationManagerCompat.from(this).notify(finishedNotificationId, notification)
    }

    private fun pauseResumeAction(timerObject: TimerObject): NotificationCompat.Action {
        val text =
            if (timerObject.state.value == WatchState.PAUSED) R.string.resume else R.string.pause
        return getAction(getString(text), ACTION_PAUSE_RESUME, 5, timerObject.id)
    }

    private fun getAction(
        label: String, action: String, requestCode: Int, objectId: Int
    ): NotificationCompat.Action {
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode + objectId,
            updateStateIntent(action, objectId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(null, label, pendingIntent).build()
    }

    private fun stopAction(timerObject: TimerObject) = getAction(
        getString(R.string.stop), ACTION_STOP, 4, timerObject.id
    )

    private fun resetAction(timerObject: TimerObject) = getAction(
        getString(R.string.timer_reset), TIMER_RESTART, 7, timerObject.id
    )

    private fun addTimeAction(timerObject: TimerObject) = getAction(
        addTimeLabel(),
        ACTION_ADD_TIME,
        6,
        timerObject.id
    )

    private fun addTimeLabel() = incrementSeconds.let { seconds ->
        if (seconds == 60) {
            getString(R.string.add_one_minute)
        } else {
            resources.getQuantityString(R.plurals.add_seconds, seconds, seconds)
        }
    }

    fun updateTimer(id: Int, settings: TimerSettings) {
        timerObjects.firstOrNull { it.id == id }?.let {
            it.label.value = settings.label
            it.initialPosition.value = settings.seconds * 1000
            it.soundName = settings.soundName
            it.soundUri = settings.soundUri
            it.soundEnabled = settings.soundEnabled
            it.vibrate = settings.vibrate
            it.vibrationPattern = settings.vibrationPattern
            it.vibrationPatternName = settings.vibrationPatternName
            updateNotification(it)
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        runCatching {
            unregisterReceiver(receiver)
        }
        timer.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent) = binder

    inner class LocalBinder : Binder() {
        fun getService() = this@TimerService
    }

    companion object {
        const val UPDATE_STATE_ACTION = "com.bnyro.clock.UPDATE_STATE"
        const val ACTION_EXTRA_KEY = "action"
        const val ID_EXTRA_KEY = "id"
        const val INITIAL_TIMER_EXTRA_KEY = "timer"
        const val ACTION_PAUSE_RESUME = "pause_resume"
        const val ACTION_STOP = "stop"
        private const val UPDATE_DELAY = 100
        const val TIMER_RESTART = "timer_restart"
        const val ACTION_ADD_TIME = "add_time"
        const val UPDATE_STATE_SCHEME = "jaytimer"
        const val ACTION_ALERT_SHOWN = "alert_shown"
        const val ACTION_ALERT_HIDDEN = "alert_hidden"
        const val LABEL_EXTRA_KEY = "label"
        const val TIMER_ALERT_CLOSE_ACTION = "com.bnyro.clock.TIMER_ALERT_CLOSE_ACTION"

        fun updateStateIntent(action: String, objectId: Int): Intent =
            Intent(UPDATE_STATE_ACTION)
                .setData("$UPDATE_STATE_SCHEME://$objectId/$action".toUri())
                .putExtra(ACTION_EXTRA_KEY, action)
                .putExtra(ID_EXTRA_KEY, objectId)
        const val ACTION_TIMER_EXPIRED = "com.bnyro.clock.TIMER_EXPIRED"
    }
}
