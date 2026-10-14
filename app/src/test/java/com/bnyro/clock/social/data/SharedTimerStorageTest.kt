package com.bnyro.clock.social.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.social.domain.DismissedSharedTimer
import com.bnyro.clock.social.domain.SyncedResource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedTimerStorageTest {
    @Test
    fun suppressionKeysBindMembershipRunAndOccurrence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        try {
            val dao = database.socialDao()
            dao.putDismissedTimer(DismissedSharedTimer("timer", "member", "run", 0, 100))
            dao.putDismissedTimer(DismissedSharedTimer("timer", "member", "run", 1, 200))
            dao.putDismissedTimer(DismissedSharedTimer("timer", "member", "next-run", 0, 300))
            dao.putDismissedTimer(DismissedSharedTimer("timer", "rejoined", "run", 0, 400))
            dao.putDismissedTimer(DismissedSharedTimer("timer", "member", "run", 0, 150))
            assertEquals(4, dao.getDismissedTimers().size)
            dao.clearDismissedTimers(201)
            assertEquals(setOf("next-run", "run"), dao.getDismissedTimers().map { it.runId }.toSet())
            assertEquals(setOf("member", "rejoined"), dao.getDismissedTimers().map { it.membershipId }.toSet())
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationDropsUnscopedOldAnswersAndPreservesTheCanonicalQueueAndCache() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        try {
            val dao = database.socialDao()
            dao.putResources(listOf(SyncedResource("group", "timer", "timer", "{}")))
            val operation = SocialSynchronization(context, database).saveOperation("group", "timer", "timer", "DELETE", "/v1/timers/timer", null, null, ordered = false)
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL("DROP TABLE dismissed_shared_timers")
            sqlite.execSQL("CREATE TABLE dismissed_shared_timers (timerId TEXT NOT NULL PRIMARY KEY, expiresAt INTEGER NOT NULL, timerExpiresAt INTEGER NOT NULL DEFAULT 0)")
            sqlite.execSQL("INSERT INTO dismissed_shared_timers VALUES ('timer', 500, 100)")
            SocialDatabase.MIGRATION_1_2.migrate(sqlite)
            assertTrue(dao.getDismissedTimers().isEmpty())
            assertEquals("timer", dao.getResources().single().key)
            assertEquals(operation, dao.getOperation(operation.operationId))
            dao.putDismissedTimer(DismissedSharedTimer("timer", "member", "run", 0, 100))
            dao.putDismissedTimer(DismissedSharedTimer("timer", "member", "run", 1, 200))
            assertEquals(2, dao.getDismissedTimers().size)
        } finally {
            database.close()
        }
    }
}
