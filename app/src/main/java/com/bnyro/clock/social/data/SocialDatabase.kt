package com.bnyro.clock.social.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bnyro.clock.social.domain.DismissedSharedTimer
import com.bnyro.clock.social.domain.SharedAlarmLink
import com.bnyro.clock.social.domain.SocialGroup
import com.bnyro.clock.social.domain.SocialMember

@Database(
    entities = [
        SocialGroup::class,
        SocialMember::class,
        SharedAlarmLink::class,
        DismissedSharedTimer::class
    ],
    version = 5
)
abstract class SocialDatabase : RoomDatabase() {
    abstract fun socialDao(): SocialDao

    companion object {
        @Volatile
        private var instance: SocialDatabase? = null

        fun getDatabase(context: Context): SocialDatabase = instance ?: synchronized(this) {
            Room.databaseBuilder(context, SocialDatabase::class.java, "jay_social")
                .addMigrations(
                    object : Migration(1, 2) {
                        override fun migrate(db: SupportSQLiteDatabase) {
                            db.execSQL("DROP TABLE social_activity")
                            db.execSQL("DROP TABLE shared_alarm_deliveries")
                            db.execSQL(
                                "ALTER TABLE social_groups ADD COLUMN notifyAlarmChanges " +
                                    "INTEGER NOT NULL DEFAULT 1"
                            )
                            db.execSQL(
                                "ALTER TABLE social_groups ADD COLUMN notifyIgnored " +
                                    "INTEGER NOT NULL DEFAULT 1"
                            )
                            db.execSQL(
                                "ALTER TABLE social_groups ADD COLUMN notifyMembership " +
                                    "INTEGER NOT NULL DEFAULT 1"
                            )
                            db.execSQL(
                                "ALTER TABLE social_groups ADD COLUMN notifyAdministrative " +
                                    "INTEGER NOT NULL DEFAULT 1"
                            )
                        }
                    },
                    object : Migration(2, 3) {
                        override fun migrate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS dismissed_shared_timers (" +
                                    "timerId TEXT NOT NULL PRIMARY KEY, " +
                                    "expiresAt INTEGER NOT NULL)"
                            )
                        }
                    },
                    object : Migration(3, 4) {
                        override fun migrate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "ALTER TABLE social_groups ADD COLUMN alarmTimeBasis " +
                                    "TEXT NOT NULL DEFAULT 'MEMBER_LOCAL'"
                            )
                            db.execSQL(
                                "ALTER TABLE social_groups ADD COLUMN alarmTimeZone " +
                                    "TEXT NOT NULL DEFAULT 'UTC'"
                            )
                            db.execSQL(
                                "ALTER TABLE shared_alarm_links ADD COLUMN soundMode " +
                                    "TEXT NOT NULL DEFAULT 'MEMBER_DEFAULT'"
                            )
                            db.execSQL("ALTER TABLE shared_alarm_links ADD COLUMN soundId TEXT")
                            db.execSQL("ALTER TABLE shared_alarm_links ADD COLUMN soundTitle TEXT")
                            db.execSQL("ALTER TABLE shared_alarm_links ADD COLUMN timeZone TEXT")
                        }
                    },
                    object : Migration(4, 5) {
                        override fun migrate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "ALTER TABLE social_groups ADD COLUMN sharedAnswers " +
                                    "INTEGER NOT NULL DEFAULT 0"
                            )
                        }
                    }
                )
                .build()
                .also { instance = it }
        }
    }
}
