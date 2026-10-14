package com.bnyro.clock.domain.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.bnyro.clock.ui.theme.DefaultLabelColor
import com.bnyro.clock.util.Preferences
import kotlin.math.ceil

data class TimerObject(
    var id: Int = 0,
    var label: MutableState<String> = mutableStateOf(""),
    var labelColor: MutableState<Int> = mutableStateOf(DefaultLabelColor),
    var currentPosition: MutableState<Int> = mutableStateOf(0),
    var initialPosition: MutableState<Int> = mutableStateOf(currentPosition.value),
    var state: MutableState<WatchState> = mutableStateOf(WatchState.IDLE),
    var soundName: String? = null,
    var soundUri: String? = null,
    var soundEnabled: Boolean = true,
    var vibrate: Boolean = true,
    var vibrationPattern: List<Int> = listOf(0, 1000, 1000, 1000, 1000),
    var vibrationPatternName: String = "Default",
    var incrementSeconds: Int? = null,
    var timeoutMinutes: Int = TimerSettings.DEFAULT_TIMEOUT_MINUTES,
    var repeatEnabled: Boolean = false,
    var repeatCount: Int = 0,
    var repeatIndex: Int = 0,
    var repeatDurationMillis: Int = initialPosition.value,
    var hasSuccessor: Boolean = false,
    var expiresAt: Long = 0L,
    var elapsedRealtimeDeadline: Long = 0L,
    var sharedTimerId: String? = null,
    var sharedGroupId: String? = null,
    var sharedMembershipId: String? = null,
    var sharedRunId: String? = null,
    var sharedAnsweredIndices: Set<Int> = emptySet(),
    var sharedGroupName: String? = null,
    var sharedCanEdit: Boolean = true
) {
    val secondsLeft: Int
        get() = ceil(currentPosition.value / 1000.0).toInt()

    val effectiveIncrementSeconds: Int
        get() = incrementSeconds ?: Preferences.instance.getInt(
            Preferences.timerIncrementSecondsKey,
            60
        )

    val settings: TimerSettings
        get() = TimerSettings(
            seconds = (if (repeatEnabled) repeatDurationMillis else initialPosition.value) / 1000,
            label = label.value,
            labelColor = labelColor.value,
            soundName = soundName,
            soundUri = soundUri,
            soundEnabled = soundEnabled,
            vibrate = vibrate,
            vibrationPattern = vibrationPattern,
            vibrationPatternName = vibrationPatternName,
            incrementSeconds = incrementSeconds,
            timeoutMinutes = timeoutMinutes,
            repeatEnabled = repeatEnabled,
            repeatCount = repeatCount
        )
}
