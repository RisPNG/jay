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
import com.bnyro.clock.social.domain.PendingOperation
import com.bnyro.clock.social.domain.PendingSocialActivity
import com.bnyro.clock.social.domain.StagedResource
import com.bnyro.clock.social.domain.SyncClockState
import com.bnyro.clock.social.domain.SyncedResource
import com.bnyro.clock.social.domain.SyncScopeState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime

@Database(
    entities = [
        SocialGroup::class,
        SocialMember::class,
        SharedAlarmLink::class,
        DismissedSharedTimer::class,
        PendingOperation::class,
        PendingSocialActivity::class,
        StagedResource::class,
        SyncClockState::class,
        SyncedResource::class,
        SyncScopeState::class
    ],
    version = 2
)
abstract class SocialDatabase : RoomDatabase() {
    abstract fun socialDao(): SocialDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE dismissed_shared_timers RENAME TO dismissed_shared_timers_legacy")
                database.execSQL("CREATE TABLE dismissed_shared_timers (timerId TEXT NOT NULL, membershipId TEXT NOT NULL, runId TEXT NOT NULL, repeatIndex INTEGER NOT NULL, expiresAt INTEGER NOT NULL, PRIMARY KEY(timerId, membershipId, runId, repeatIndex))")
                val clock = database.query("SELECT serverMillis, wallMillis FROM sync_clock WHERE id = 1").use {
                    if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null
                }
                val serverOffset = clock?.let { it.first - it.second } ?: 0L
                database.query("SELECT answers.timerId, answers.expiresAt, answers.timerExpiresAt, timers.scopeId, timers.representation FROM dismissed_shared_timers_legacy answers JOIN sync_resources timers ON timers.kind = 'timer' AND timers.`key` = answers.timerId").use { answers ->
                    while (answers.moveToNext()) {
                        val timerId = answers.getString(0)
                        val retentionExpiry = answers.getLong(1)
                        val timerExpiry = answers.getLong(2)
                        if (timerExpiry == 0L) continue
                        val stoppedAt = retentionExpiry - 30 * 60_000L + serverOffset
                        val scopeId = answers.getString(3)
                        val timer = Json.parseToJsonElement(answers.getString(4)).jsonObject
                        val groupId = timer["group_id"]?.jsonPrimitive?.content ?: continue
                        val expiresAt = timer["expires_at"]?.jsonPrimitive?.content ?: continue
                        val savedAt = timer["saved_at"]?.jsonPrimitive?.content ?: continue
                        if (OffsetDateTime.parse(expiresAt).toInstant().toEpochMilli() != timerExpiry ||
                            OffsetDateTime.parse(savedAt).toInstant().toEpochMilli() > stoppedAt
                        ) continue
                        val pendingMutation = database.query("SELECT 1 FROM pending_operations WHERE state IN ('pending', 'accepted') AND ((kind = 'timer' AND targetId = ?) OR (kind IN ('group', 'member') AND scopeId IN (?, ?))) LIMIT 1", arrayOf(timerId, groupId, scopeId)).use { it.moveToFirst() }
                        if (pendingMutation) continue
                        val runId = timer["run_id"]?.jsonPrimitive?.contentOrNull ?: timerId
                        if (runId != timerId && (clock == null || clock.second > retentionExpiry - 30 * 60_000L)) continue
                        database.query("SELECT groups.membershipId, members.representation FROM social_groups groups JOIN sync_resources members ON members.scopeId = ? AND members.kind = 'member' AND members.`key` = groups.membershipId WHERE groups.id = ?", arrayOf(scopeId, groupId)).use member@{ members ->
                            if (!members.moveToFirst()) return@member
                            val member = Json.parseToJsonElement(members.getString(1)).jsonObject
                            val joinedAt = member["joined_at"]?.jsonPrimitive?.content ?: return@member
                            if (OffsetDateTime.parse(joinedAt).toInstant().toEpochMilli() > stoppedAt) return@member
                            val repeatIndex = timer["repeat_index"]?.jsonPrimitive?.intOrNull ?: 0
                            database.execSQL("INSERT INTO dismissed_shared_timers (timerId, membershipId, runId, repeatIndex, expiresAt) VALUES (?, ?, ?, ?, ?)", arrayOf(timerId, members.getString(0), runId, repeatIndex, retentionExpiry))
                        }
                    }
                }
                database.execSQL("DROP TABLE dismissed_shared_timers_legacy")
            }
        }

        @Volatile
        private var instance: SocialDatabase? = null

        fun getDatabase(context: Context): SocialDatabase = instance ?: synchronized(this) {
            Room.databaseBuilder(context, SocialDatabase::class.java, "jay_social")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
