package com.bnyro.clock

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.ui.theme.LabelColor
import com.bnyro.clock.util.Preferences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AlarmSnoozeMigrationTest {
    @Test
    fun version15AlarmsKeepTheirSettingsAndCanPersistSnoozes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Preferences.init(context)
        val originalTimeout = Preferences.instance.all["alarmTimeoutMinutes"] as Int?
        Preferences.edit { putInt("alarmTimeoutMinutes", 5) }
        try {
            for (schemaName in listOf("15", "15-canary")) {
                val name = "alarm-snooze-migration-$schemaName"
                val pendingSnooze = if (schemaName == "15-canary") 1_800_000_420_000L else null
                val schema = requireNotNull(javaClass.getResourceAsStream(
                    "/schemas/com.bnyro.clock.data.database.AppDatabase/$schemaName.json"
                )).bufferedReader().use {
                    Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject
                }
                context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
                    schema.getValue("entities").jsonArray.forEach { entity ->
                        val table = entity.jsonObject
                        legacy.execSQL(table.getValue("createSql").jsonPrimitive.content.replace(
                            "\${TABLE_NAME}", table.getValue("tableName").jsonPrimitive.content
                        ))
                    }
                    schema.getValue("setupQueries").jsonArray.forEach {
                        legacy.execSQL(it.jsonPrimitive.content)
                    }
                    legacy.execSQL(
                        "INSERT INTO alarms (id, time, label, labelColor, enabled, days, vibrate, snoozeMinutes, startDate, repeatInterval, advanced) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any>(1, 25_200_000L, "Weekdays", LabelColor.Cocoa.argb, 1, "1,2,3,4,5", 1, 7, 20_000, 2, 1)
                    )
                    pendingSnooze?.let {
                        legacy.execSQL("UPDATE alarms SET snoozedUntil = ? WHERE id = 1", arrayOf<Any>(it))
                    }
                    legacy.version = schema.getValue("version").jsonPrimitive.int
                }
                val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name)
                    .addMigrations(
                        AppDatabase.MIGRATION_15_16,
                        ReflectionHelpers.getStaticField<Migration>(AppDatabase::class.java, "MIGRATION_16_17"),
                        AppDatabase.MIGRATION_17_18
                    )
                    .build()
                val alarm = Alarm(
                    id = 1, time = 25_200_000L, label = "Weekdays", labelColor = LabelColor.Cocoa.argb,
                    enabled = true, days = listOf(1, 2, 3, 4, 5), snoozeMinutes = 7,
                    snoozedUntil = pendingSnooze, startDate = 20_000, repeatInterval = 2, advanced = true
                )
                try {
                    assertEquals(alarm, upgraded.alarmsDao().findById(alarm.id))
                    val snoozed = alarm.copy(snoozedUntil = 1_800_000_840_000L)
                    upgraded.alarmsDao().update(snoozed)
                    upgraded.close()
                    val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
                    try {
                        assertEquals(snoozed, reopened.alarmsDao().findById(alarm.id))
                    } finally {
                        reopened.close()
                    }
                } finally {
                    upgraded.close()
                    context.deleteDatabase(name)
                }
            }
        } finally {
            Preferences.edit {
                if (originalTimeout == null) remove("alarmTimeoutMinutes")
                else putInt("alarmTimeoutMinutes", originalTimeout)
            }
        }
    }
}
