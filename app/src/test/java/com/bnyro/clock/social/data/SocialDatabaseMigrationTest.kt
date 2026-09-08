package com.bnyro.clock.social.data

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SocialDatabaseMigrationTest {
    @Test
    fun migrationPreservesExistingDismissalsWithoutInventingAnExpiry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val schema = JSONObject(File("schemas/com.bnyro.clock.social.data.SocialDatabase/5.json").readText())
            .getJSONObject("database").getJSONArray("entities")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("jay_social")
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        repeat(schema.length()) { index ->
                            val entity = schema.getJSONObject(index)
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                            repeat(indices.length()) { index ->
                                db.execSQL(indices.getJSONObject(index).getString("createSql")
                                    .replace("\${TABLE_NAME}", entity.getString("tableName")))
                            }
                        }
                        db.execSQL("INSERT INTO dismissed_shared_timers VALUES ('timer', 123456)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        helper.writableDatabase
        helper.close()
        val database = SocialDatabase.getDatabase(context)
        try {
            val dismissal = database.socialDao().getDismissedTimers().single()
            assertEquals("timer", dismissal.timerId)
            assertEquals(123456L, dismissal.expiresAt)
            assertEquals(0L, dismissal.timerExpiresAt)
        } finally {
            database.close()
        }
    }
}
