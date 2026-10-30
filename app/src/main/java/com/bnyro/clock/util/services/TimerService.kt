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
import android.os.SystemClock
import android.os.Vibrator
import android.provider.AlarmClock
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.BackupTimer
import com.bnyro.clock.domain.model.TimerDescriptor
import com.bnyro.clock.domain.model.TimerObject
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.domain.model.WatchState
import com.bnyro.clock.presentation.screens.ringing.RingingActivity
import com.bnyro.clock.presentation.screens.timer.TimerAlertActivity
import com.bnyro.clock.ui.MainActivity
import com.bnyro.clock.util.NotificationHelper
import com.bnyro.clock.ui.theme.DefaultLabelColor
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.social.data.SocialTimerWorker
import com.bnyro.clock.util.widgets.TextColor
import com.bnyro.clock.util.widgets.getColorValue
import com.bnyro.clock.util.TimeHelper
import com.bnyro.clock.util.VolumeRamp

import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs

class TimerService : Service() {
    private val timer = Timer()
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private val vibrator: Vibrator by lazy {
        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private lateinit var contentIntent: PendingIntent

    var onChangeTimers: (objects: Array<TimerObject>) -> Unit = {}
    var timerObjects = mutableListOf<TimerObject>()

    private var wakeLock: PowerManager.WakeLock? = null
    private var alertedTimerId: Int? = null
    private var ringingTimerId: Int? = null
    private var ringingSince = 0L
    private var lastRungSeconds = 0L
    private var ringTimeout: TimerTask? = null
    private var volumeRamp: VolumeRamp? = null

    @SuppressLint("ServiceCast", "ScheduleExactAlarm")
    private fun scheduleAlarm(
        timerObject: TimerObject,
        expiresAt: Long = System.currentTimeMillis() + timerObject.currentPosition.value
    ) {
        timerObject.expiresAt = expiresAt
        timerObject.currentPosition.value = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0).toInt()
        timerObject.elapsedRealtimeDeadline = SystemClock.elapsedRealtime() + timerObject.currentPosition.value
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(this, TimerAlarmReceiver::class.java).apply {
            putExtra(ID_EXTRA_KEY, timerObject.id)
            putExtra(EXPIRES_AT_EXTRA_KEY, expiresAt)
            action = ACTION_TIMER_EXPIRED
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            timerObject.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmInfo = AlarmManager.AlarmClockInfo(expiresAt, pendingIntent)

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

    private val fullScreenAlertEnabled
        get() = Preferences.instance.getBoolean(Preferences.timerFullScreenAlertKey, true)

    /** How long the timer that is ringing has been ringing for. */
    private val ringDuration get() = System.currentTimeMillis() - ringingSince

    private val receiver = object : BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.N)
        override fun onReceive(context: Context, intent: Intent) {
            Log.e("receive", intent.toString())
            if (intent.getStringExtra(ACTION_EXTRA_KEY) == PRUNE_SHARED_TIMERS_ACTION) {
                pruneSharedTimers(intent)
                return
            }
            val id = intent.getIntExtra(ID_EXTRA_KEY, 0)
            val obj = timerObjects.find { it.id == id } ?: return
            when (val action = intent.getStringExtra(ACTION_EXTRA_KEY)) {
                ACTION_STOP -> {
                    if (obj.sharedTimerId != null) {
                        SocialTimerWorker.enqueue(applicationContext, obj,
                            if (obj.currentPosition.value > 0 && obj.sharedCanEdit) SocialTimerWorker.ACTION_CANCEL
                            else SocialTimerWorker.ACTION_DISMISSED)
                    }
                    stop(obj)
                }

                ACTION_PAUSE_RESUME -> {
                    if (obj.sharedTimerId != null) return
                    if (obj.state.value == WatchState.PAUSED) resume(obj) else pause(obj)
                }

                ACTION_ALERT_SHOWN, ACTION_ALERT_HIDDEN -> {
                    alertedTimerId = obj.id.takeIf { action == ACTION_ALERT_SHOWN }
                    if (obj.id == ringingTimerId) {
                        promoteForeground(announcing = action == ACTION_ALERT_HIDDEN)
                    }
                }

                ACTION_ADD_TIME -> {
                    // a timer that has finished ringing has run out of time to add to, so the
                    // time added starts it running again rather than sitting on a finished timer
                    val finished = obj.currentPosition.value == 0
                    val addedSeconds = intent.getIntExtra(INCREMENT_EXTRA_KEY, obj.effectiveIncrementSeconds)
                    val addedMillis = addedSeconds * 1000
                    val expiresAt = if (finished) System.currentTimeMillis() + addedMillis
                    else obj.expiresAt + addedMillis

                    if (obj.sharedTimerId != null) {
                        if (!obj.sharedCanEdit) return
                        SocialTimerWorker.enqueue(applicationContext, obj, SocialTimerWorker.ACTION_ADJUST, expiresAt, if (finished) 0 else obj.repeatIndex,
                            adjustmentSeconds = addedSeconds)
                        if (finished) endRinging(obj)
                        return
                    }

                    if (finished) {
                        endRinging(obj)
                        obj.repeatIndex = 0
                        obj.hasSuccessor = false
                        obj.state.value = WatchState.RUNNING
                        obj.initialPosition.value = addedMillis
                    } else {
                        obj.initialPosition.value += addedMillis
                    }
                    obj.currentPosition.value += addedMillis

                    if (obj.state.value == WatchState.RUNNING) {
                        cancelAlarm(obj)
                        scheduleAlarm(obj, expiresAt)
                        if (finished) acquireWakeLock()
                    }

                    if (finished) {
                        NotificationManagerCompat.from(context)
                            .cancel(finishedNotificationId(obj))
                        promoteForeground()
                        invokeChangeListener()
                    }

                    updateNotification(obj)
                }

                TIMER_RESTART -> {
                    if (obj.sharedTimerId != null) {
                        if (!obj.sharedCanEdit) return
                        SocialTimerWorker.enqueue(applicationContext, obj, SocialTimerWorker.ACTION_ADJUST, System.currentTimeMillis() + obj.initialPosition.value, 0)
                        endRinging(obj)
                        return
                    }
                    endRinging(obj)

                    obj.repeatIndex = 0
                    obj.hasSuccessor = false

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

                    promoteForeground()
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
            mediaPlayer!!.setOnErrorListener { player, _, _ ->
                Log.e("Media Player", "Error occurred while playing audio.")
                player.release()
                mediaPlayer = null
                if (timerObject.soundUri != null) {
                    runCatching {
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(
                                this@TimerService,
                                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            )
                            startAlarm(this)
                        }
                    }
                }
                true
            }

            try {
                mediaPlayer!!.setDataSource(this, alert)
                startAlarm(mediaPlayer!!)
            } catch (e: Exception) {
                Log.e("failed to play ringtone", e.message, e)
                if (timerObject.soundUri != null) {
                    runCatching {
                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(
                                this@TimerService,
                                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            )
                            startAlarm(this)
                        }
                    }
                }
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
        volumeRamp?.cancel()
        volumeRamp = VolumeRamp(
            player,
            Preferences.instance.getInt(Preferences.timerVolumeRampSecondsKey, 0)
        ).apply { start() }
        player.start()
    }

    /**
     * Stops the audio and vibration
     */
    private fun stopAudio() {
        if (!isPlaying) return
        isPlaying = false

        volumeRamp?.cancel()
        volumeRamp = null

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
        if (intent?.action == SYNC_SHARED_TIMER_ACTION) {
            syncSharedTimer(intent)
            if (ringingTimerId == null && timerObjects.none { it.state.value == WatchState.RUNNING }) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_TIMER_EXPIRED) {
            val id = intent.getIntExtra(ID_EXTRA_KEY, 0)
            val obj = timerObjects.find { it.id == id }

            if (obj == null) {
                Log.e("TimerService", "error D:D:D:DD:D:D:D:D:D:")
                NotificationManagerCompat.from(this).cancel(id)
                if (timerObjects.isEmpty()) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_STICKY
            }

            val expiresAt = intent.getLongExtra(EXPIRES_AT_EXTRA_KEY, obj.expiresAt)
            if (obj.state.value != WatchState.RUNNING || expiresAt != obj.expiresAt) {
                return START_STICKY
            }

            cancelAlarm(obj)
            obj.currentPosition.value = 0
            obj.state.value = WatchState.PAUSED

            if (obj.repeatEnabled) {
                val now = System.currentTimeMillis()
                var elapsedRepeats = ((now - expiresAt).coerceAtLeast(0) / obj.repeatDurationMillis).toInt()
                var nextIndex = obj.repeatIndex + elapsedRepeats + 1
                while (nextIndex in obj.sharedAnsweredIndices) {
                    nextIndex++
                    elapsedRepeats++
                }
                if (obj.repeatCount == 0 || nextIndex <= obj.repeatCount) {
                    val nextExpiry = expiresAt + (elapsedRepeats + 1) * obj.repeatDurationMillis.toLong()
                    val existingSuccessor = obj.sharedTimerId?.let { sharedId ->
                        timerObjects.firstOrNull {
                            it.sharedTimerId == sharedId && it.sharedMembershipId == obj.sharedMembershipId &&
                                it.sharedRunId == obj.sharedRunId && it.repeatIndex == nextIndex
                        }
                    }
                    obj.hasSuccessor = true
                    if (existingSuccessor == null) {
                        val successor = obj.copy(
                            id = (timerObjects.maxOfOrNull { it.id } ?: 0) + 1,
                            label = mutableStateOf(obj.label.value),
                            labelColor = mutableStateOf(obj.labelColor.value),
                            currentPosition = mutableStateOf(obj.repeatDurationMillis),
                            initialPosition = mutableStateOf(obj.repeatDurationMillis),
                            state = mutableStateOf(WatchState.RUNNING),
                            repeatIndex = nextIndex, hasSuccessor = false,
                            expiresAt = nextExpiry, elapsedRealtimeDeadline = 0L
                        )
                        enqueueNew(successor)
                    }
                }
            }

            startRinging(obj)

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
            if (timerObjects.isNotEmpty()) return START_STICKY
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
        val shared = timerObject.sharedTimerId != null

        return NotificationCompat.Builder(
            this, NotificationHelper.TIMER_CHANNEL
        ).setContentTitle(timeLeft)
            .setShortCriticalText(timeLeft)
            .setRequestPromotedOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
            .apply {
                if (!shared) addAction(pauseResumeAction(timerObject))
                if (!shared || timerObject.sharedCanEdit) {
                    addAction(
                        if (timerObject.state.value == WatchState.RUNNING) {
                            addTimeAction(timerObject)
                        } else {
                            resetAction(timerObject)
                        }
                    )
                }
                addAction(stopAction(timerObject))
            }
            .setSmallIcon(R.drawable.ic_notification).setOngoing(true).build()
    }

    fun invokeChangeListener() {
        onChangeTimers.invoke(timerObjects.toTypedArray())
    }

    private fun updateState() {
        val now = SystemClock.elapsedRealtime()

        timerObjects.forEach {
            if (it.state.value == WatchState.RUNNING) {
                val before = it.secondsLeft
                it.currentPosition.value =
                    (it.elapsedRealtimeDeadline - now).coerceAtLeast(0).toInt()

                if (before != it.secondsLeft) {
                    updateNotification(it)
                }
            }
        }

        timerObjects.find { it.id == ringingTimerId }?.let {
            val rung = ringDuration / 1000
            if (rung != lastRungSeconds) {
                lastRungSeconds = rung
                showFinishedNotification(it)
            }
        }
    }

    fun restoreTimers(timers: List<BackupTimer>) {
        if (timers.isEmpty()) return
        timers.forEach { timer ->
            val id = (timerObjects.maxOfOrNull { it.id } ?: 0) + 1
            val restored = TimerDescriptor(id, timer.settings).asScheduledObject()
            restored.currentPosition.value = timer.remainingMillis
            restored.initialPosition.value = timer.initialMillis
            restored.repeatIndex = timer.repeatIndex
            restored.state.value = WatchState.PAUSED
            timerObjects.add(restored)
            updateNotification(restored)
        }
        startService(Intent(this, TimerService::class.java))
        invokeChangeListener()
    }

    fun enqueueNew(timerObject: TimerObject) {
        timerObject.state.value = WatchState.RUNNING
        timerObjects.add(timerObject)

        scheduleAlarm(
            timerObject,
            timerObject.expiresAt.takeIf { it > 0 } ?: System.currentTimeMillis() + timerObject.currentPosition.value
        )
        acquireWakeLock()

        invokeChangeListener()
        updateNotification(timerObject)
    }

    private fun syncSharedTimer(intent: Intent) {
        val sharedId = intent.getStringExtra(SHARED_TIMER_ID_EXTRA_KEY) ?: return
        val groupId = intent.getStringExtra(SHARED_TIMER_GROUP_ID_EXTRA_KEY) ?: return
        val membershipId = intent.getStringExtra(SHARED_TIMER_MEMBERSHIP_ID_EXTRA_KEY) ?: return
        val runId = intent.getStringExtra(SHARED_TIMER_RUN_ID_EXTRA_KEY) ?: return
        val groupName = intent.getStringExtra(SHARED_TIMER_GROUP_NAME_EXTRA_KEY)
        val label = intent.getStringExtra(SHARED_TIMER_LABEL_EXTRA_KEY)
        val labelColor = intent.getIntExtra(SHARED_TIMER_LABEL_COLOR_EXTRA_KEY, DefaultLabelColor)
        val durationMillis = intent.getIntExtra(SHARED_TIMER_DURATION_EXTRA_KEY, 0) * 1000
        if (durationMillis <= 0) return
        val incrementSeconds = intent.getIntExtra(SHARED_TIMER_INCREMENT_EXTRA_KEY, 60)
        val baseExpiry = intent.getLongExtra(SHARED_TIMER_EXPIRES_EXTRA_KEY, 0L)
        val baseIndex = intent.getIntExtra(SHARED_TIMER_REPEAT_INDEX_EXTRA_KEY, 0)
        val repeatEnabled = intent.getBooleanExtra(SHARED_TIMER_REPEAT_ENABLED_EXTRA_KEY, false)
        val repeatCount = intent.getIntExtra(SHARED_TIMER_REPEAT_COUNT_EXTRA_KEY, 0)
        val timeoutMinutes = intent.getIntExtra(SHARED_TIMER_TIMEOUT_EXTRA_KEY, TimerSettings.DEFAULT_TIMEOUT_MINUTES)
        val answeredIndices = (intent.getIntArrayExtra(SHARED_TIMER_ANSWERED_INDICES_EXTRA_KEY) ?: intArrayOf()).toSet()
        val canEdit = intent.getBooleanExtra(SHARED_TIMER_CAN_EDIT_EXTRA_KEY, false)
        val soundEnabled = intent.getBooleanExtra(SHARED_TIMER_SOUND_ENABLED_EXTRA_KEY, true)
        val soundName = intent.getStringExtra(SHARED_TIMER_SOUND_NAME_EXTRA_KEY)
        val soundUri = intent.getStringExtra(SHARED_TIMER_SOUND_URI_EXTRA_KEY)
        val vibrate = intent.getBooleanExtra(SHARED_TIMER_VIBRATE_EXTRA_KEY, true)
        val vibrationPattern = intent.getIntArrayExtra(SHARED_TIMER_VIBRATION_PATTERN_EXTRA_KEY)
            ?.toList() ?: listOf(0, 1000, 1000, 1000, 1000)
        val vibrationPatternName = intent.getStringExtra(SHARED_TIMER_VIBRATION_PATTERN_NAME_EXTRA_KEY) ?: "Default"
        timerObjects.filter {
            it.sharedTimerId == sharedId && (it.sharedMembershipId != membershipId || it.sharedRunId != runId || it.repeatIndex in answeredIndices)
        }.forEach { stop(it, keepService = true) }
        val now = System.currentTimeMillis()
        val skipped = if (repeatEnabled && baseExpiry <= now) (now - baseExpiry) / durationMillis + 1 else 0L
        var targetIndex = baseIndex + skipped
        var targetExpiry = baseExpiry + skipped * durationMillis
        while (repeatEnabled && targetIndex <= Int.MAX_VALUE && targetIndex.toInt() in answeredIndices) {
            targetIndex++
            targetExpiry += durationMillis
        }
        val hasFuture = targetExpiry > now && targetIndex <= Int.MAX_VALUE &&
            (!repeatEnabled && targetIndex == 0L || repeatEnabled && (repeatCount == 0 || targetIndex <= repeatCount)) &&
            targetIndex.toInt() !in answeredIndices
        val existing = timerObjects.firstOrNull {
            it.sharedTimerId == sharedId && it.sharedMembershipId == membershipId && it.sharedRunId == runId &&
                it.repeatIndex.toLong() == targetIndex
        }
        timerObjects.filter { it.sharedTimerId == sharedId }.forEach {
            it.label.value = label ?: TimeHelper.durationToName(durationMillis / 1000)
            it.labelColor.value = labelColor
            it.sharedAnsweredIndices = answeredIndices
            it.sharedGroupName = groupName
            it.sharedCanEdit = canEdit
            it.incrementSeconds = incrementSeconds
            it.timeoutMinutes = timeoutMinutes
            it.repeatEnabled = repeatEnabled
            it.repeatCount = repeatCount
            it.repeatDurationMillis = durationMillis
            it.soundEnabled = soundEnabled
            it.soundName = soundName
            it.soundUri = soundUri
            it.vibrate = vibrate
            it.vibrationPattern = vibrationPattern
            it.vibrationPatternName = vibrationPatternName
            if (hasFuture && it.repeatIndex < targetIndex) it.hasSuccessor = true
            updateNotification(it)
        }
        if (!hasFuture) {
            invokeChangeListener()
            promoteForeground()
            return
        }
        val remaining = (targetExpiry - now).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (existing == null) {
            val obj = TimerObject(
                id = "$sharedId/$membershipId/$runId/$targetIndex".hashCode(),
                label = mutableStateOf(label ?: TimeHelper.durationToName(durationMillis / 1000)),
                labelColor = mutableStateOf(labelColor),
                currentPosition = mutableStateOf(remaining), initialPosition = mutableStateOf(durationMillis),
                state = mutableStateOf(WatchState.RUNNING),
                incrementSeconds = incrementSeconds, timeoutMinutes = timeoutMinutes,
                repeatEnabled = repeatEnabled, repeatCount = repeatCount, repeatIndex = targetIndex.toInt(),
                repeatDurationMillis = durationMillis, expiresAt = targetExpiry,
                sharedTimerId = sharedId, sharedGroupId = groupId, sharedMembershipId = membershipId,
                sharedRunId = runId, sharedAnsweredIndices = answeredIndices, sharedGroupName = groupName, sharedCanEdit = canEdit,
                soundEnabled = soundEnabled, soundName = soundName, soundUri = soundUri,
                vibrate = vibrate, vibrationPattern = vibrationPattern, vibrationPatternName = vibrationPatternName
            )
            startForeground(obj.id, getNotification(obj))
            enqueueNew(obj)
            return
        }
        val drifted = abs(existing.currentPosition.value - remaining) > 1000
        if (existing.state.value != WatchState.RUNNING || existing.expiresAt != targetExpiry || drifted) {
            existing.state.value = WatchState.RUNNING
            existing.currentPosition.value = remaining
            cancelAlarm(existing)
            scheduleAlarm(existing, targetExpiry)
            acquireWakeLock()
        }
        invokeChangeListener()
        promoteForeground()
        updateNotification(existing)
    }

    private fun pruneSharedTimers(intent: Intent) {
        val active = intent.getStringArrayListExtra(ACTIVE_SHARED_TIMER_IDS_EXTRA_KEY)
            .orEmpty().toSet()
        timerObjects.filter { it.sharedTimerId != null && it.sharedTimerId !in active }.forEach {
            stop(it)
        }
    }

    private fun pause(timerObject: TimerObject) {
        timerObject.currentPosition.value =
            (timerObject.elapsedRealtimeDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0).toInt()
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
        promoteForeground()
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

    private fun stop(timerObject: TimerObject, keepService: Boolean = false) {
        cancelAlarm(timerObject)
        endRinging(timerObject)
        timerObjects.remove(timerObject)

        if (timerObjects.none { it.state.value == WatchState.RUNNING }) {
            releaseWakeLock()
        }

        invokeChangeListener()
        promoteForeground()
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.cancel(timerObject.id)
        notificationManager.cancel(finishedNotificationId(timerObject))

        if (timerObjects.isEmpty() && !keepService) {
            stopSelf()
        }
    }

    /** A timer that runs out takes over the ringing from the previous timer. Completed copies with a successor are removed when their ringing ends, while final completions retain their finished notification. The earlier alert is left for the new timer's intent to take over. */
    private fun startRinging(timerObject: TimerObject) {
        val silenced = timerObjects.find { it.id == ringingTimerId }
            ?.let { it to endRinging(it, keepAlert = true) }

        ringingTimerId = timerObject.id
        ringingSince = System.currentTimeMillis()
        lastRungSeconds = 0
        NotificationManagerCompat.from(this).cancel(timerObject.id)
        play(timerObject)

        ringTimeout = object : TimerTask() {
            override fun run() {
                handler.post {
                    if (timerObject.hasSuccessor) {
                        stop(timerObject)
                    } else {
                        val rangFor = endRinging(timerObject)
                        promoteForeground()
                        showFinishedNotification(timerObject, rangFor)
                    }
                }
            }
        }.also { timer.schedule(it, timerObject.timeoutMinutes * 60 * 1000L) }

        promoteForeground(announcing = true)
        // the foreground moves first so removing the earlier timer's notification cannot remove the new timer's announcement
        silenced?.let { (ringing, rangFor) ->
            if (ringing.hasSuccessor) stop(ringing)
            else showFinishedNotification(ringing, rangFor)
        }
        if (fullScreenAlertEnabled) startActivity(alertIntent(timerObject))
    }

    private fun endRinging(timerObject: TimerObject, keepAlert: Boolean = false): Long {
        if (ringingTimerId != timerObject.id) return 0

        val rangFor = ringDuration
        stopAudio()
        ringTimeout?.cancel()
        ringTimeout = null
        ringingTimerId = null
        if (!keepAlert) closeAlert(timerObject)
        return rangFor
    }

    /**
     * The notification the service is held in the foreground by is the one the reader most needs to
     * see: the timer that is ringing, or failing that whichever timer is still counting.
     */
    private fun promoteForeground(announcing: Boolean = false) {
        val ringing = timerObjects.find { it.id == ringingTimerId }
        if (ringing != null) {
            startForeground(
                finishedNotificationId(ringing),
                finishedNotification(ringing, announcing = announcing)
            )
            return
        }

        val counting = timerObjects.firstOrNull { it.state.value == WatchState.RUNNING }
        if (counting != null) {
            startForeground(counting.id, getNotification(counting))
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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
            .putExtra(GROUP_NAME_EXTRA_KEY, timerObject.sharedGroupName)
            .putExtra(RINGING_SINCE_EXTRA_KEY, ringingSince)
            .putExtra(INCREMENT_EXTRA_KEY, timerObject.effectiveIncrementSeconds)
            .putExtra(CAN_EDIT_EXTRA_KEY, timerObject.sharedTimerId == null || timerObject.sharedCanEdit)

    private fun closeAlert(timerObject: TimerObject) {
        if (alertedTimerId == timerObject.id) alertedTimerId = null
        sendBroadcast(
            Intent(TIMER_ALERT_CLOSE_ACTION)
                .putExtra(RingingActivity.ACTION_EXTRA_KEY, RingingActivity.CLOSE_ACTION)
                .putExtra(ID_EXTRA_KEY, timerObject.id)
                .setPackage(packageName)
        )
    }

    private fun showFinishedNotification(
        timerObject: TimerObject,
        rangFor: Long? = null,
        announcing: Boolean = false
    ) {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        NotificationManagerCompat.from(this).notify(
            finishedNotificationId(timerObject),
            finishedNotification(timerObject, rangFor, announcing)
        )
    }

    /**
     * What a finished timer says for itself. While it is ringing it goes on counting, past zero and
     * into the time it has been waiting to be answered; once the ringing is over it keeps the count
     * it stopped at as the length it rang for, and gives its title back to the app.
     */
    private fun finishedNotification(
        timerObject: TimerObject,
        rangFor: Long? = null,
        announcing: Boolean = false
    ): Notification {
        val notificationChannelId = NotificationHelper.TIMER_FINISHED_CHANNEL
        val finishedNotificationId = finishedNotificationId(timerObject)
        val ringing = rangFor == null
        val alertShowing = alertedTimerId == timerObject.id
        // the ring announces itself once, and again whenever the screen that was answering for it
        // goes away; the counting that follows is the same announcement wearing a newer number
        val announces = ringing && announcing && !alertShowing

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
            null, addTimeLabel(timerObject.effectiveIncrementSeconds), snoozePendingIntent
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

        return NotificationCompat.Builder(this, notificationChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (ringing) "-" + DateUtils.formatElapsedTime(ringDuration / 1000) else null
            )
            .setContentText(
                if (ringing) {
                    getString(R.string.finished_named_timer, timerObject.label.value)
                } else {
                    getString(
                        R.string.finished_named_timer_for,
                        timerObject.label.value,
                        TimeHelper.durationToName((rangFor / 1000).toInt())
                    )
                }
            )
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(deletePendingIntent)
            .setOngoing(false)
            .setSilent(!announces)
            .apply {
                if (ringing) {
                    setColorized(true)
                    setColor(TextColor.PrimaryDark.getColorValue(this@TimerService))
                }
                if (announces && fullScreenAlertEnabled) {
                    setFullScreenIntent(alertPendingIntent, true)
                }
            }
            .addAction(stopAction)
            .apply {
                if (timerObject.sharedTimerId == null || timerObject.sharedCanEdit) {
                    addAction(snoozeAction)
                    addAction(restartAction)
                }
            }
            .build()
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
        addTimeLabel(timerObject.effectiveIncrementSeconds),
        ACTION_ADD_TIME,
        6,
        timerObject.id
    )

    private fun addTimeLabel(seconds: Int) = if (seconds == 60) {
        getString(R.string.add_one_minute)
    } else {
        resources.getQuantityString(R.plurals.add_seconds, seconds, seconds)
    }

    fun updateTimer(id: Int, settings: TimerSettings) {
        timerObjects.firstOrNull { it.id == id }?.let {
            val duration = settings.seconds * 1000
            val previousDuration = if (it.repeatEnabled) it.repeatDurationMillis else it.initialPosition.value
            val running = it.state.value == WatchState.RUNNING
            val remaining = if (running) {
                (it.elapsedRealtimeDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            } else it.currentPosition.value.toLong()
            it.currentPosition.value = (remaining * duration / previousDuration).toInt()
            it.initialPosition.value =
                (it.initialPosition.value.toLong() * duration / previousDuration).toInt()
            if (it.repeatEnabled != settings.repeatEnabled || it.repeatCount != settings.repeatCount) {
                it.repeatIndex = 0
            }
            if (running) cancelAlarm(it)
            it.label.value = settings.label
            it.labelColor.value = settings.labelColor
            it.repeatDurationMillis = duration
            it.soundName = settings.soundName
            it.soundUri = settings.soundUri
            it.soundEnabled = settings.soundEnabled
            it.vibrate = settings.vibrate
            it.vibrationPattern = settings.vibrationPattern
            it.vibrationPatternName = settings.vibrationPatternName
            it.incrementSeconds = settings.incrementSeconds
            it.timeoutMinutes = settings.timeoutMinutes
            it.repeatEnabled = settings.repeatEnabled
            it.repeatCount = settings.repeatCount
            if (running) scheduleAlarm(it)
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
        const val EXPIRES_AT_EXTRA_KEY = "expires_at"
        const val INITIAL_TIMER_EXTRA_KEY = "timer"
        const val ACTION_PAUSE_RESUME = "pause_resume"
        const val ACTION_STOP = "stop"
        private const val UPDATE_DELAY = 100
        const val TIMER_RESTART = "timer_restart"
        const val ACTION_ADD_TIME = "add_time"
        const val UPDATE_STATE_SCHEME = "timer"
        const val ACTION_ALERT_SHOWN = "alert_shown"
        const val ACTION_ALERT_HIDDEN = "alert_hidden"
        const val LABEL_EXTRA_KEY = "label"
        const val GROUP_NAME_EXTRA_KEY = "group_name"
        const val RINGING_SINCE_EXTRA_KEY = "ringing_since"
        const val INCREMENT_EXTRA_KEY = "increment"
        const val TIMER_ALERT_CLOSE_ACTION = "com.bnyro.clock.TIMER_ALERT_CLOSE_ACTION"
        const val SYNC_SHARED_TIMER_ACTION = "com.bnyro.clock.SYNC_SHARED_TIMER"
        const val PRUNE_SHARED_TIMERS_ACTION = "com.bnyro.clock.PRUNE_SHARED_TIMERS"
        const val SHARED_TIMER_ID_EXTRA_KEY = "shared_timer_id"
        const val SHARED_TIMER_GROUP_NAME_EXTRA_KEY = "shared_timer_group_name"
        const val SHARED_TIMER_LABEL_EXTRA_KEY = "shared_timer_label"
        const val SHARED_TIMER_LABEL_COLOR_EXTRA_KEY = "shared_timer_label_color"
        const val SHARED_TIMER_DURATION_EXTRA_KEY = "shared_timer_duration"
        const val SHARED_TIMER_INCREMENT_EXTRA_KEY = "shared_timer_increment"
        const val SHARED_TIMER_EXPIRES_EXTRA_KEY = "shared_timer_expires"
        const val SHARED_TIMER_CAN_EDIT_EXTRA_KEY = "shared_timer_can_edit"
        const val SHARED_TIMER_GROUP_ID_EXTRA_KEY = "shared_timer_group_id"
        const val SHARED_TIMER_MEMBERSHIP_ID_EXTRA_KEY = "shared_timer_membership_id"
        const val SHARED_TIMER_RUN_ID_EXTRA_KEY = "shared_timer_run_id"
        const val SHARED_TIMER_TIMEOUT_EXTRA_KEY = "shared_timer_timeout"
        const val SHARED_TIMER_REPEAT_ENABLED_EXTRA_KEY = "shared_timer_repeat_enabled"
        const val SHARED_TIMER_REPEAT_COUNT_EXTRA_KEY = "shared_timer_repeat_count"
        const val SHARED_TIMER_REPEAT_INDEX_EXTRA_KEY = "shared_timer_repeat_index"
        const val SHARED_TIMER_ANSWERED_INDICES_EXTRA_KEY = "shared_timer_answered_indices"
        const val SHARED_TIMER_SOUND_ENABLED_EXTRA_KEY = "shared_timer_sound_enabled"
        const val SHARED_TIMER_SOUND_NAME_EXTRA_KEY = "shared_timer_sound_name"
        const val SHARED_TIMER_SOUND_URI_EXTRA_KEY = "shared_timer_sound_uri"
        const val SHARED_TIMER_VIBRATE_EXTRA_KEY = "shared_timer_vibrate"
        const val SHARED_TIMER_VIBRATION_PATTERN_EXTRA_KEY = "shared_timer_vibration_pattern"
        const val SHARED_TIMER_VIBRATION_PATTERN_NAME_EXTRA_KEY = "shared_timer_vibration_pattern_name"
        const val ACTIVE_SHARED_TIMER_IDS_EXTRA_KEY = "active_shared_timer_ids"
        const val CAN_EDIT_EXTRA_KEY = "can_edit"

        fun updateStateIntent(action: String, objectId: Int): Intent =
            Intent(UPDATE_STATE_ACTION)
                .setData("$UPDATE_STATE_SCHEME://$objectId/$action".toUri())
                .putExtra(ACTION_EXTRA_KEY, action)
                .putExtra(ID_EXTRA_KEY, objectId)
        const val ACTION_TIMER_EXPIRED = "com.bnyro.clock.TIMER_EXPIRED"
    }
}
