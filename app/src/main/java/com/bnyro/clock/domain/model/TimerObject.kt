package com.bnyro.clock.domain.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

data class TimerObject(
    var id: Int = 0,
    var label: MutableState<String> = mutableStateOf(""),
    var currentPosition: MutableState<Int> = mutableStateOf(0),
    var initialPosition: MutableState<Int> = mutableStateOf(currentPosition.value),
    var state: MutableState<WatchState> = mutableStateOf(WatchState.IDLE),
    var soundName: String? = null,
    var soundUri: String? = null,
    var soundEnabled: Boolean = true,
    var vibrate: Boolean = true,
    var vibrationPattern: List<Int> = List(5) { 1000 },
    var vibrationPatternName: String = "Default"
) {
    val settings: TimerSettings
        get() = TimerSettings(
            seconds = initialPosition.value / 1000,
            label = label.value,
            soundName = soundName,
            soundUri = soundUri,
            soundEnabled = soundEnabled,
            vibrate = vibrate,
            vibrationPattern = vibrationPattern,
            vibrationPatternName = vibrationPatternName
        )
}
