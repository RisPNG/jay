package com.bnyro.clock.social.data

import com.bnyro.clock.util.Preferences
import java.time.ZoneId

object SocialAlarmSchedule {
    fun timeZone(alarmId: Long): ZoneId = runCatching {
        Preferences.instance.getString("${SocialPreferences.alarmTimeZonePrefix}$alarmId", null)
            ?.let(ZoneId::of)
    }.getOrNull() ?: ZoneId.systemDefault()

    fun setTimeZone(alarmId: Long, timeZone: String?) {
        Preferences.edit {
            if (timeZone == null) {
                remove("${SocialPreferences.alarmTimeZonePrefix}$alarmId")
            } else {
                putString("${SocialPreferences.alarmTimeZonePrefix}$alarmId", timeZone)
            }
        }
    }
}
