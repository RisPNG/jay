package com.bnyro.clock.social.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.social.domain.AlarmPermission
import com.bnyro.clock.social.domain.DismissedSharedTimer
import com.bnyro.clock.social.domain.MemberRole
import com.bnyro.clock.social.domain.PendingOperation
import com.bnyro.clock.social.domain.SocialGroup
import com.bnyro.clock.social.domain.SyncClockState
import com.bnyro.clock.social.domain.SyncedResource
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SharedTimerMigrationTest {
    private data class LegacyStop(
        val name: String,
        val preserved: Boolean,
        val runId: String? = null,
        val repeatIndex: Int = 0,
        val cachedExpiryDelta: Long = 0,
        val savedAtDelta: Long = -60_000,
        val joinedAtDelta: Long = -86_400_000,
        val expiryKnown: Boolean = true,
        val timerCached: Boolean = true,
        val memberCached: Boolean = true,
        val savedAtKnown: Boolean = true,
        val pendingKind: String? = null,
        val serverOffset: Long = 0,
        val cachedAtDelta: Long = 0
    )

    @Test
    fun upgradePreservesOnlyStopsWithKnownMembershipAndScheduleLineage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val timerId = "5e6a0058-1cb8-461f-8e49-85b691457b03"
        val memberId = "58e4a63d-8a8e-4e7a-b73a-5d903d139d82"
        val groupId = "dcf03764-f1d0-47f7-bdb2-0e59277d4a74"
        val scopeId = "cf647a01-cd05-4799-82b5-2c8791510507"
        val knownRun = "f76124d7-bde2-4426-bd4b-166f781d6511"
        val cases = listOf(
            LegacyStop("future counting stop before the server upgrade", true),
            LegacyStop("initial generation already received", true, runId = timerId),
            LegacyStop("initial generation republished after the stop", true, runId = timerId, cachedAtDelta = 1),
            LegacyStop("known generation and copy already received", true, runId = knownRun, repeatIndex = 3),
            LegacyStop("server and device clock offset", true, serverOffset = 300_000),
            LegacyStop("reset since the stop even with the same expiry", false, runId = knownRun, savedAtDelta = 1),
            LegacyStop("offline reset accepted after the stop with an older saved time", false, runId = knownRun, cachedAtDelta = 1),
            LegacyStop("membership rejoined since the stop", false, joinedAtDelta = 1),
            LegacyStop("expiry changed", false, cachedExpiryDelta = 1),
            LegacyStop("old answer did not record an expiry", false, expiryKnown = false),
            LegacyStop("timer no longer cached", false, timerCached = false),
            LegacyStop("membership provenance missing", false, memberCached = false),
            LegacyStop("schedule provenance missing", false, savedAtKnown = false),
            LegacyStop("timer reset still queued", false, pendingKind = "timer"),
            LegacyStop("group leave still queued", false, pendingKind = "group")
        )
        for (case in cases) {
            val name = "legacy-timer-upgrade-${UUID.randomUUID()}"
            val original = Room.databaseBuilder(context, SocialDatabase::class.java, name).build()
            var upgraded: SocialDatabase? = null
            val stoppedAt = System.currentTimeMillis()
            val retentionExpiry = stoppedAt + 30 * 60_000L
            val timerExpiry = stoppedAt + 20 * 60_000L
            val serverStoppedAt = stoppedAt + case.serverOffset
            try {
                val dao = original.socialDao()
                dao.putGroups(listOf(SocialGroup(
                    groupId, "Household", AlarmPermission.LEADERS,
                    true, true, true, true, true, true, MemberRole.MEMBER,
                    membershipId = memberId
                )))
                val resources = mutableListOf<SyncedResource>()
                if (case.timerCached) {
                    val timer = mutableMapOf(
                        "id" to JsonPrimitive(timerId), "group_id" to JsonPrimitive(groupId),
                        "expires_at" to JsonPrimitive(Instant.ofEpochMilli(timerExpiry + case.cachedExpiryDelta).toString())
                    )
                    if (case.savedAtKnown) timer["saved_at"] = JsonPrimitive(Instant.ofEpochMilli(serverStoppedAt + case.savedAtDelta).toString())
                    if (case.runId != null) {
                        timer["run_id"] = JsonPrimitive(case.runId)
                        timer["repeat_index"] = JsonPrimitive(case.repeatIndex)
                    }
                    resources += SyncedResource(scopeId, "timer", timerId, JsonObject(timer).toString())
                }
                if (case.memberCached) resources += SyncedResource(scopeId, "member", memberId, JsonObject(mapOf(
                    "id" to JsonPrimitive(memberId), "group_id" to JsonPrimitive(groupId),
                    "joined_at" to JsonPrimitive(Instant.ofEpochMilli(serverStoppedAt + case.joinedAtDelta).toString())
                )).toString())
                dao.putResources(resources)
                dao.putClock(SyncClockState(
                    serverMillis = stoppedAt + case.cachedAtDelta + case.serverOffset,
                    wallMillis = stoppedAt + case.cachedAtDelta, elapsedMillis = 0, bootCount = 0, lastSavedMillis = 0
                ))
                val operation = case.pendingKind?.let { kind ->
                    PendingOperation(
                        operationId = UUID.randomUUID().toString(), scopeId = groupId,
                        kind = kind, targetId = if (kind == "timer") timerId else groupId,
                        method = if (kind == "timer") "PUT" else "DELETE", path = "/v1/$kind/$timerId",
                        payload = null, optimisticState = null,
                        rawSavedAt = stoppedAt + 1, effectiveSavedAt = serverStoppedAt + 1
                    ).let { it.copy(sequence = dao.enqueueOperation(it)) }
                }
                val sqlite = original.openHelper.writableDatabase
                sqlite.execSQL("DROP TABLE dismissed_shared_timers")
                sqlite.execSQL("CREATE TABLE dismissed_shared_timers (timerId TEXT NOT NULL PRIMARY KEY, expiresAt INTEGER NOT NULL, timerExpiresAt INTEGER NOT NULL DEFAULT 0)")
                sqlite.execSQL("INSERT INTO dismissed_shared_timers VALUES (?, ?, ?)", arrayOf<Any>(timerId, retentionExpiry, if (case.expiryKnown) timerExpiry else 0L))
                sqlite.version = 1
                original.close()
                upgraded = Room.databaseBuilder(context, SocialDatabase::class.java, name)
                    .addMigrations(SocialDatabase.MIGRATION_1_2).build()
                val expected = if (case.preserved) listOf(DismissedSharedTimer(
                    timerId, memberId, case.runId ?: timerId, case.repeatIndex, retentionExpiry
                )) else emptyList()
                assertEquals(case.name, expected, upgraded.socialDao().getDismissedTimers())
                assertEquals(case.name, resources.toSet(), upgraded.socialDao().getResources().toSet())
                if (operation != null) assertEquals(case.name, operation, upgraded.socialDao().getOperation(operation.operationId))
                assertEquals(case.name, 2, upgraded.openHelper.writableDatabase.version)
                upgraded.close()
                upgraded = Room.databaseBuilder(context, SocialDatabase::class.java, name).build()
                assertEquals(case.name, expected, upgraded.socialDao().getDismissedTimers())
            } finally {
                upgraded?.close()
                original.close()
                context.deleteDatabase(name)
            }
        }
    }
}
