package com.bnyro.clock.social.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.social.domain.PendingOperation
import com.bnyro.clock.social.domain.DismissedSharedTimer
import com.bnyro.clock.social.domain.MemberRole
import com.bnyro.clock.social.domain.SocialMember
import com.bnyro.clock.social.domain.SyncedResource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SocialDatabaseTest {
    @Test
    fun freshSchemaPersistsQueuedChangesAcrossReopening() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "social-reopen-test"
        var database = Room.databaseBuilder(context, SocialDatabase::class.java, name).build()
        try {
            database.socialDao().putDismissedTimer(DismissedSharedTimer("timer", "member", "run", 0, 123456))
            database.socialDao().enqueueOperation(PendingOperation(
                operationId = "operation", scopeId = "group", kind = "timer", targetId = "timer",
                method = "PUT", path = "/v1/timers/timer", payload = "{}", optimisticState = "{}",
                rawSavedAt = 100, effectiveSavedAt = 101
            ))
            database.close()
            database = Room.databaseBuilder(context, SocialDatabase::class.java, name).build()
            assertEquals(123456L, database.socialDao().getDismissedTimers().single().expiresAt)
            val operation = database.socialDao().getPendingOperations().single()
            assertEquals("operation", operation.operationId)
            assertEquals(101L, operation.effectiveSavedAt)
            assertEquals("{}", operation.payload)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun upgradingCachedMembersKeepsTheirJoinOrderWithoutSyncing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "member-join-order-upgrade"
        var database = Room.databaseBuilder(context, SocialDatabase::class.java, name).build()
        try {
            val members = listOf(
                SocialMember("family", "newer", "Alice", MemberRole.LEADER) to "2026-11-19T00:00:00.123002Z",
                SocialMember("family", "older", "Zoe", MemberRole.MEMBER) to "2026-11-19T08:00:00.123001+08:00",
                SocialMember("family", "legacy-b", "Sam", MemberRole.MEMBER) to null,
                SocialMember("family", "legacy-a", "Sam", MemberRole.MEMBER) to "invalid"
            )
            val dao = database.socialDao()
            dao.putMembers(members.map { it.first })
            dao.putResources(members.map { (member, joinedAt) ->
                SyncedResource("family-scope", "member", "membership-${member.deviceId}", buildJsonObject {
                    put("group_id", member.groupId)
                    put("identity_id", member.deviceId)
                    put("name", member.name)
                    put("role", member.role.name.lowercase())
                    joinedAt?.let { put("joined_at", it) }
                }.toString())
            })
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL("ALTER TABLE social_members DROP COLUMN joinedAtMicros")
            sqlite.version = 2
            database.close()

            database = Room.databaseBuilder(context, SocialDatabase::class.java, name)
                .addMigrations(SocialDatabase.MIGRATION_2_3).build()
            val orderedMembers = database.socialDao().getMembersStream().first()
            assertEquals(listOf("older", "newer", "legacy-a", "legacy-b"), orderedMembers.map { it.deviceId })
            assertEquals(listOf(MemberRole.MEMBER, MemberRole.LEADER, MemberRole.MEMBER, MemberRole.MEMBER), orderedMembers.map { it.role })
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
