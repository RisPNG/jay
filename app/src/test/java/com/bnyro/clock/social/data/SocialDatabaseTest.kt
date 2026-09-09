package com.bnyro.clock.social.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.social.domain.PendingOperation
import com.bnyro.clock.social.domain.DismissedSharedTimer
import kotlinx.coroutines.runBlocking
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
            database.socialDao().putDismissedTimer(DismissedSharedTimer("timer", 123456, 100000))
            database.socialDao().enqueueOperation(PendingOperation(
                operationId = "operation", scopeId = "group", kind = "timer", targetId = "timer",
                method = "PUT", path = "/v1/timers/timer", payload = "{}", optimisticState = "{}",
                rawSavedAt = 100, effectiveSavedAt = 101
            ))
            database.close()
            database = Room.databaseBuilder(context, SocialDatabase::class.java, name).build()
            assertEquals(100000L, database.socialDao().getDismissedTimers().single().timerExpiresAt)
            val operation = database.socialDao().getPendingOperations().single()
            assertEquals("operation", operation.operationId)
            assertEquals(101L, operation.effectiveSavedAt)
            assertEquals("{}", operation.payload)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
