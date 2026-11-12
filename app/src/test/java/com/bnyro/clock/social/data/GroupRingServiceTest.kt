package com.bnyro.clock.social.data

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.App
import com.bnyro.clock.R
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.RingingCoordinator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GroupRingServiceTest {
    private lateinit var app: App
    private lateinit var controller: ServiceController<GroupRingService>
    private lateinit var service: GroupRingService
    private lateinit var notifications: NotificationManager
    private var destroyed = false

    @Before
    fun createService() {
        app = ApplicationProvider.getApplicationContext()
        Preferences.instance.edit().clear().putString(SocialPreferences.serverUrlKey, "http://127.0.0.1:1").commit()
        DeviceIdentityStore.loadOrCreate(app, "http://127.0.0.1:1")
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)),
            ShadowMediaPlayer.MediaInfo(60_000, 0)
        )
        notifications = app.getSystemService(NotificationManager::class.java)
        controller = Robolectric.buildService(GroupRingService::class.java).create()
        service = controller.get()
    }

    @After
    fun destroyService() {
        if (!destroyed) controller.destroy()
        assertNull(RingingCoordinator.selected)
        ShadowMediaPlayer.resetStaticState()
        Preferences.instance.edit().clear().commit()
    }

    @Test
    fun aVisibleRingRetainsTheServiceAndOnlyShowsItsColoredExplanation() {
        service.onBind(Intent(app, GroupRingService::class.java))
        service.receive(Json.parseToJsonElement("""{"session_id":"visible","active":true,"lease_seconds":5,"actor_label":"Alice","group_label":"Family"}""").jsonObject)

        val retained = requireNotNull(shadowOf(app).nextStartedService)
        assertEquals(GroupRingService::class.java.name, retained.component?.className)
        service.onUnbind(Intent(app, GroupRingService::class.java))
        assertNotNull(RingingCoordinator.selected)
        val notification = notifications.activeNotifications.single().notification
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_COLORIZED))
        assertEquals("Alice is ringing Family", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(notification.actions.isNullOrEmpty())
        assertNull(notification.fullScreenIntent)
        assertNull(notification.contentIntent)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun aPushWaitsSilentlyThroughStreamResetAndExpiresWithoutAFreshLease() {
        service.onStartCommand(
            Intent(app, GroupRingService::class.java).setAction(GroupRingService.ACTION_WAKE)
                .putExtra(GroupRingService.EXTRA_SESSION_ID, "pending")
                .putExtra(GroupRingService.EXTRA_LEASE_MILLIS, 5_000L), 0, 1
        )
        service.receive(null)

        assertNull(RingingCoordinator.selected)
        assertEquals(app.getString(R.string.group_ring_waiting), notifications.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        val wakeLock = GroupRingService::class.java.getDeclaredField("wakeLock").apply { isAccessible = true }.get(service) as PowerManager.WakeLock
        assertTrue(wakeLock.isHeld)

        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)

        assertTrue(notifications.activeNotifications.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(wakeLock.isHeld)
        assertNull(RingingCoordinator.selected)
    }

    @Test
    fun duplicatePushesCannotExtendAnActiveRingOrResurrectAReleasedSession() {
        service.receive(Json.parseToJsonElement("""{"session_id":"duplicate","active":true,"lease_seconds":5,"actor_label":"Alice","group_label":"Family"}""").jsonObject)
        val wake = Intent(app, GroupRingService::class.java).setAction(GroupRingService.ACTION_WAKE)
            .putExtra(GroupRingService.EXTRA_SESSION_ID, "duplicate")
            .putExtra(GroupRingService.EXTRA_LEASE_MILLIS, 5_000L)
        service.onStartCommand(wake, 0, 1)
        assertEquals("Alice is ringing Family", notifications.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())

        service.receive(Json.parseToJsonElement("""{"session_id":"duplicate","active":false,"lease_seconds":5}""").jsonObject)
        service.onStartCommand(wake, 0, 2)

        assertTrue(notifications.activeNotifications.isEmpty())
        assertNull(RingingCoordinator.selected)
    }

    @Test
    fun aReleaseAlsoCancelsAPushThatHasNotReceivedItsFirstLease() {
        service.onStartCommand(
            Intent(app, GroupRingService::class.java).setAction(GroupRingService.ACTION_WAKE)
                .putExtra(GroupRingService.EXTRA_SESSION_ID, "released-pending")
                .putExtra(GroupRingService.EXTRA_LEASE_MILLIS, 5_000L), 0, 1
        )

        service.receive(Json.parseToJsonElement("""{"session_id":"released-pending","active":false,"lease_seconds":5}""").jsonObject)

        assertTrue(notifications.activeNotifications.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(RingingCoordinator.selected)
    }

    @Test
    fun aFreshLiveLeaseTurnsAColdPushIntoRingingUntilRelease() {
        service.onStartCommand(
            Intent(app, GroupRingService::class.java).setAction(GroupRingService.ACTION_WAKE)
                .putExtra(GroupRingService.EXTRA_SESSION_ID, "cold")
                .putExtra(GroupRingService.EXTRA_LEASE_MILLIS, 5_000L), 0, 1
        )
        assertNull(RingingCoordinator.selected)

        service.receive(Json.parseToJsonElement("""{"session_id":"cold","active":true,"lease_seconds":5,"actor_label":"Alice","group_label":"Family"}""").jsonObject)
        assertNotNull(RingingCoordinator.selected)
        assertEquals("Alice is ringing Family", notifications.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())

        service.receive(Json.parseToJsonElement("""{"session_id":"cold","active":false,"lease_seconds":5}""").jsonObject)

        assertTrue(notifications.activeNotifications.isEmpty())
        assertNull(RingingCoordinator.selected)
    }

    @Test
    fun concurrentCallersKeepTheirExplanationUntilTheLastLeaseExpires() {
        service.receive(Json.parseToJsonElement("""{"session_id":"alice","active":true,"lease_seconds":5,"actor_label":"Alice","group_label":"Family"}""").jsonObject)
        service.receive(Json.parseToJsonElement("""{"session_id":"bob","active":true,"lease_seconds":5,"actor_label":"Bob","group_label":"Friends"}""").jsonObject)
        val combined = notifications.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(combined.contains("Alice is ringing Family"))
        assertTrue(combined.contains("Bob is ringing Friends"))

        service.receive(Json.parseToJsonElement("""{"session_id":"alice","active":false,"lease_seconds":5}""").jsonObject)
        assertEquals("Bob is ringing Friends", notifications.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNotNull(RingingCoordinator.selected)
        val wakeLock = GroupRingService::class.java.getDeclaredField("wakeLock").apply { isAccessible = true }.get(service) as PowerManager.WakeLock

        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)

        assertTrue(notifications.activeNotifications.isEmpty())
        assertNull(RingingCoordinator.selected)
        assertFalse(wakeLock.isHeld)
    }

    @Test
    fun destroyingTheServiceReleasesPlaybackNotificationAndWakeLock() {
        service.receive(Json.parseToJsonElement("""{"session_id":"destroyed","active":true,"lease_seconds":5,"actor_label":"Alice","group_label":"Family"}""").jsonObject)
        val wakeLock = GroupRingService::class.java.getDeclaredField("wakeLock").apply { isAccessible = true }.get(service) as PowerManager.WakeLock
        assertTrue(wakeLock.isHeld)

        controller.destroy()
        destroyed = true
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)

        assertTrue(notifications.activeNotifications.isEmpty())
        assertNull(RingingCoordinator.selected)
        assertFalse(wakeLock.isHeld)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun changingTheServerEndsItsRingAndNotification() {
        service.receive(Json.parseToJsonElement("""{"session_id":"old-server","active":true,"lease_seconds":5,"actor_label":"Alice","group_label":"Family"}""").jsonObject)

        Preferences.instance.edit().putString(SocialPreferences.serverUrlKey, "http://127.0.0.1:2").commit()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(notifications.activeNotifications.isEmpty())
        assertNull(RingingCoordinator.selected)
    }

    @Test
    fun importingAnotherProfileEndsThePreviousProfilesRing() {
        service.receive(Json.parseToJsonElement("""{"session_id":"old-profile","active":true,"lease_seconds":5,"actor_label":"Alice","group_label":"Family"}""").jsonObject)

        app.getSharedPreferences("jay_identity", Context.MODE_PRIVATE).edit()
            .putString(SocialPreferences.deviceSecretKey, android.util.Base64.encodeToString(ByteArray(32) { 1 }, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)).commit()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(notifications.activeNotifications.isEmpty())
        assertNull(RingingCoordinator.selected)
    }

    @Test
    fun resettingTheProfileEndsItsRingEvenWhenPreferencesReportAClear() {
        service.receive(Json.parseToJsonElement("""{"session_id":"reset-profile","active":true,"lease_seconds":5,"actor_label":"Alice","group_label":"Family"}""").jsonObject)

        app.getSharedPreferences("jay_identity", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(notifications.activeNotifications.isEmpty())
        assertNull(RingingCoordinator.selected)
    }
}
