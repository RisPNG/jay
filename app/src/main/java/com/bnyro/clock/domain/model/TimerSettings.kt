package com.bnyro.clock.domain.model

import android.os.Parcelable
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.TimeHelper
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything a timer is started with, and everything a saved timer keeps between runs.
 *
 * @property seconds The duration the timer counts down from.
 * @property label The name of the timer, which falls back to the duration it was set to.
 */
@Parcelize
@Serializable
data class TimerSettings(
    val seconds: Int,
    val label: String = TimeHelper.durationToName(seconds),
    val soundName: String? = null,
    val soundUri: String? = null,
    val soundEnabled: Boolean = true,
    val vibrate: Boolean = true,
    val vibrationPattern: List<Int> = List(5) { 1000 },
    val vibrationPatternName: String = "Default"
) : Parcelable {
    companion object {
        private val exampleTimers = listOf(
            60 * 10,
            60 * 15,
            60 * 30,
            60 * 60
        ).map { TimerSettings(it) }

        fun setSavedTimers(timers: List<TimerSettings>) {
            Preferences.edit {
                putString(Preferences.savedTimersKey, Json.encodeToString(timers))
            }
        }

        fun getSavedTimers(): List<TimerSettings> {
            val savedTimers = Preferences.instance.getString(Preferences.savedTimersKey, null)
                ?: return exampleTimers
            return Json.decodeFromString(savedTimers)
        }
    }
}
