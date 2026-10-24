package com.bnyro.clock

import android.app.Application
import android.os.UserManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.util.Preferences
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AlarmTimingMigrationTest {
    @Test
    fun lockedOfflineUpgradePreservesTheOldGlobalTimeoutAndNewAlarmDefaults() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val context = application.createDeviceProtectedStorageContext()
        val migration = AppDatabase.MIGRATION_15_16
        for (globalTimeout in listOf(null, 23)) {
            Preferences.init(context)
            Preferences.edit {
                clear()
                if (globalTimeout != null) putInt("alarmTimeoutMinutes", globalTimeout)
            }
            val name = "alarm-timing-upgrade-${UUID.randomUUID()}"
            val original = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            var upgraded: AppDatabase? = null
            try {
                val alarm = Alarm(
                    time = 25_200_000L, label = "Wake up", enabled = true, days = listOf(1, 2, 3, 4, 5),
                    snoozedUntil = System.currentTimeMillis() + 420_000L, timeoutMinutes = 3,
                    missedRestartEnabled = true, missedRestartMinutes = 7
                )
                alarm.id = original.alarmsDao().insert(alarm)
                val sqlite = original.openHelper.writableDatabase
                val legacySchema = sqlite.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'alarms'").use {
                    it.moveToFirst()
                    it.getString(0).replace(Regex(", `(timeoutMinutes|missedRestartEnabled|missedRestartMinutes)` INTEGER NOT NULL DEFAULT [0-9]+"), "")
                }
                assertFalse(legacySchema.contains("timeoutMinutes"))
                sqlite.execSQL("ALTER TABLE alarms RENAME TO alarms_before_timing")
                sqlite.execSQL(legacySchema)
                val columns = sqlite.query("PRAGMA table_info(alarms)").use { rows ->
                    buildList {
                        while (rows.moveToNext()) add("`${rows.getString(rows.getColumnIndexOrThrow("name"))}`")
                    }.joinToString(", ")
                }
                sqlite.execSQL("INSERT INTO alarms ($columns) SELECT $columns FROM alarms_before_timing")
                sqlite.execSQL("DROP TABLE alarms_before_timing")
                sqlite.version = migration.startVersion
                original.close()
                shadowOf(application.getSystemService(UserManager::class.java)).setUserUnlocked(false)
                Preferences.init(context)

                upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(migration).build()

                assertEquals(
                    alarm.copy(timeoutMinutes = globalTimeout ?: 10, missedRestartEnabled = false, missedRestartMinutes = 10),
                    upgraded.alarmsDao().findById(alarm.id)
                )
                val newId = upgraded.alarmsDao().insert(Alarm(time = 28_800_000L))
                assertEquals(5, upgraded.alarmsDao().findById(newId)?.timeoutMinutes)
            } finally {
                original.close()
                upgraded?.close()
                context.deleteDatabase(name)
                Preferences.edit { clear() }
                shadowOf(application.getSystemService(UserManager::class.java)).setUserUnlocked(true)
            }
        }
    }
}
