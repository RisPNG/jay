package com.bnyro.clock

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.model.Alarm
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AlarmSnoozeMigrationTest {
    @Test
    fun version15AlarmsKeepTheirSettingsAndCanPersistSnoozes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "alarm-snooze-migration"
        val schema = requireNotNull(javaClass.getResourceAsStream(
            "/schemas/com.bnyro.clock.data.database.AppDatabase/15.json"
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
                "INSERT INTO alarms (id, time, label, enabled, days, vibrate, snoozeMinutes, startDate, repeatInterval, advanced) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(1, 25_200_000L, "Weekdays", 1, "1,2,3,4,5", 1, 7, 20_000, 2, 1)
            )
            legacy.version = schema.getValue("version").jsonPrimitive.int
        }
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val alarm = Alarm(
            id = 1, time = 25_200_000L, label = "Weekdays", enabled = true,
            days = listOf(1, 2, 3, 4, 5), snoozeMinutes = 7, startDate = 20_000,
            repeatInterval = 2, advanced = true
        )
        try {
            assertEquals(alarm, upgraded.alarmsDao().findById(alarm.id))
            val snoozed = alarm.copy(snoozedUntil = 1_800_000_420_000L)
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
}
