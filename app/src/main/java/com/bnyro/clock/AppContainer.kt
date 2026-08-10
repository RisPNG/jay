package com.bnyro.clock

import android.content.Context
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.domain.repository.TimezoneRepository
import com.bnyro.clock.social.data.SocialDatabase
import com.bnyro.clock.social.data.SocialRepository

class AppContainer(context: Context, database: AppDatabase) {
    val alarmRepository: AlarmRepository by lazy {
        AlarmRepository(database.alarmsDao())
    }
    val timezoneRepository: TimezoneRepository by lazy {
        TimezoneRepository(database.timeZonesDao())
    }
    val socialRepository: SocialRepository by lazy {
        SocialRepository(context, SocialDatabase.getDatabase(context), alarmRepository)
    }
}
