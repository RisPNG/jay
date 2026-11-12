package com.bnyro.clock.social.data

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.social.domain.SyncClockState
import com.bnyro.clock.util.Preferences
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SocialPushMessagingServiceTest {
    private lateinit var app: Application
    private lateinit var controller: ServiceController<SocialPushMessagingService>
    private lateinit var identity: DeviceIdentity
    private lateinit var database: SocialDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Preferences.init(app)
        Preferences.edit {
            clear()
            putString(SocialPreferences.serverUrlKey, "https://jay.example")
        }
        identity = DeviceIdentityStore.loadOrCreate(app, "https://jay.example")
        database = SocialDatabase.getDatabase(app)
        controller = Robolectric.buildService(SocialPushMessagingService::class.java).create()
    }

    @After
    fun tearDown() {
        controller.destroy()
        runBlocking { database.socialDao().clearClock() }
        database.close()
        SocialDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        Preferences.edit { clear() }
    }

    @Test
    fun onlyAFreshHighPriorityWakeForThisProfileAndServerStartsTheRingService() {
        val valid = mapOf(
            "kind" to "ring",
            "session_id" to "hold",
            "group_id" to "family",
            "identity_id" to identity.id,
            "server_url" to "https://jay.example/",
            "expires_at" to Instant.ofEpochMilli(System.currentTimeMillis() + 5_000L).toString()
        )
        for (data in listOf(
            valid + ("identity_id" to "previous-profile"),
            valid + ("server_url" to "https://previous.example"),
            valid + ("expires_at" to Instant.ofEpochMilli(System.currentTimeMillis() - 1_000L).toString()),
            valid + ("expires_at" to "invalid"),
            valid - "session_id"
        )) {
            controller.get().onMessageReceived(RemoteMessage(Bundle().apply {
                putString("google.delivered_priority", "high")
                data.forEach { (key, value) -> putString(key, value) }
            }))
            assertNull(shadowOf(app).nextStartedService)
        }
        controller.get().onMessageReceived(RemoteMessage(Bundle().apply {
            putString("google.delivered_priority", "normal")
            valid.forEach { (key, value) -> putString(key, value) }
        }))
        assertNull(shadowOf(app).nextStartedService)

        controller.get().onMessageReceived(RemoteMessage(Bundle().apply {
            putString("google.delivered_priority", "high")
            valid.forEach { (key, value) -> putString(key, value) }
        }))
        val wake = requireNotNull(shadowOf(app).nextStartedService)
        assertEquals(GroupRingService::class.java.name, wake.component?.className)
        assertEquals(GroupRingService.ACTION_WAKE, wake.action)
        assertEquals("hold", wake.getStringExtra(GroupRingService.EXTRA_SESSION_ID))
        assertEquals(5_000L, wake.getLongExtra(GroupRingService.EXTRA_LEASE_MILLIS, 0))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun wakeFreshnessUsesTheSynchronizedServerClock() = runBlocking {
        val serverTime = System.currentTimeMillis() - 300_000L
        database.socialDao().putClock(SyncClockState(
            serverMillis = serverTime,
            wallMillis = System.currentTimeMillis(),
            elapsedMillis = SystemClock.elapsedRealtime(),
            bootCount = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0),
            lastSavedMillis = 0
        ))
        controller.get().onMessageReceived(RemoteMessage(Bundle().apply {
            putString("google.delivered_priority", "high")
            putString("kind", "ring")
            putString("session_id", "clock")
            putString("group_id", "family")
            putString("identity_id", identity.id)
            putString("server_url", "https://jay.example")
            putString("expires_at", Instant.ofEpochMilli(serverTime + 4_000L).toString())
        }))

        assertEquals(GroupRingService.ACTION_WAKE, requireNotNull(shadowOf(app).nextStartedService).action)
    }
}
