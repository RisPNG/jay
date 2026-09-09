package com.bnyro.clock.social.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.TimerService
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SharedTimerSyncTest {
    @Test
    fun restartingADismissedRunReturnsItAndRejectedStartsRemainDurablyRecoverable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Preferences.init(context)
        Preferences.instance.edit().clear().commit()
        var expiresAt = System.currentTimeMillis() + 600_000L
        var cursor = 1L
        var registrations = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path == "/v1/identities/register") registrations++
            val items = when (exchange.requestURI.path) {
                "/v1/sync" -> """[{"kind":"membership","key":"membership","action":"upsert","revision":$cursor,"ordinal":0,"data":{"id":"membership","group_id":"group","scope_id":"group","role":"leader","notify_membership":true,"notify_administrative":true}}]"""
                "/v1/groups/group/sync" -> """[{"kind":"group","key":"group","action":"upsert","revision":$cursor,"ordinal":0,"data":{"id":"group","name":"Kitchen","alarm_permission":"everyone","notify_alarm_changes":true,"notify_snoozed":true,"notify_dismissed":true,"notify_ignored":true}},{"kind":"timer","key":"timer","action":"upsert","revision":$cursor,"ordinal":1,"data":{"id":"timer","group_id":"group","label":"Pasta","label_color":-15584170,"duration_seconds":600,"increment_seconds":60,"expires_at":"${Instant.ofEpochMilli(expiresAt)}","sound_mode":"off"}}]"""
                else -> null
            }
            val body = if (items != null) {
                val scope = if (exchange.requestURI.path == "/v1/sync") "identity" else "group"
                """{"scope_id":"$scope","mode":"snapshot","from_revision":0,"through_revision":$cursor,"next_cursor":"$cursor","has_more":false,"server_time":"${Instant.now()}","items":$items}"""
            } else "{}"
            exchange.requestBody.close()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val social = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        val alarms = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            Preferences.instance.edit().putString(SocialPreferences.serverUrlKey, "http://127.0.0.1:${server.address.port}").commit()
            val repository = SocialRepository(context, social, AlarmRepository(alarms.alarmsDao()))
            repository.suppressSharedTimer("timer", expiresAt)
            repository.synchronize()
            assertEquals(null, shadowOf(context).nextStartedService)
            expiresAt += 60_000L
            cursor = 2L
            repository.synchronize()
            val start = shadowOf(context).nextStartedService
            assertNotNull(start)
            assertEquals(TimerService.SYNC_SHARED_TIMER_ACTION, start.action)
            assertEquals(-15584170, start.getIntExtra(TimerService.SHARED_TIMER_LABEL_COLOR_EXTRA_KEY, -1))
            assertEquals(expiresAt, start.getLongExtra(TimerService.SHARED_TIMER_EXPIRES_EXTRA_KEY, 0))
            assertEquals(1, registrations)
            cursor = 3L
            val rejecting = object : ContextWrapper(context) {
                override fun startForegroundService(service: Intent): android.content.ComponentName? {
                    throw IllegalStateException("Test background start rejection")
                }
            }
            val rejected = runCatching {
                SocialRepository(rejecting, social, AlarmRepository(alarms.alarmsDao())).synchronize()
            }
            assertTrue(rejected.exceptionOrNull() is IllegalStateException)
            assertEquals("3", social.socialDao().getScope("/v1/groups/group/sync")?.cursor)
            repository.refreshSharedState()
            assertNotNull(shadowOf(context).nextStartedService)
            val dismissed = social.socialDao().getDismissedTimers().single()
            social.socialDao().clearDismissedTimers(dismissed.expiresAt + 1)
            assertTrue(social.socialDao().getDismissedTimers().isEmpty())
            assertEquals(2, registrations)
        } finally {
            server.stop(0)
            social.close()
            alarms.close()
        }
    }
}
