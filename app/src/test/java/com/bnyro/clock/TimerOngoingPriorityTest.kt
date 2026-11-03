package com.bnyro.clock

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.TimerDescriptor
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.util.NotificationHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.TimerService
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TimerOngoingPriorityTest {
    private lateinit var application: Application
    private lateinit var controller: ServiceController<TimerService>
    private lateinit var service: TimerService

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        Preferences.init(application)
        Preferences.instance.edit().clear()
            .putBoolean(Preferences.timerFullScreenAlertKey, false).commit()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createStaticNotificationChannels(application)
        controller = Robolectric.buildService(TimerService::class.java).create()
        service = controller.get()
    }

    @After
    fun tearDown() {
        service.sendBroadcast(TimerService.updateStateIntent(TimerService.PRUNE_SHARED_TIMERS_ACTION, 0)
            .putStringArrayListExtra(TimerService.ACTIVE_SHARED_TIMER_IDS_EXTRA_KEY, arrayListOf()))
        shadowOf(Looper.getMainLooper()).idle()
        service.timerObjects.toList().forEach { sendAction(TimerService.ACTION_STOP, it.id) }
        controller.destroy()
        Preferences.instance.edit().clear().commit()
    }

    @Test
    fun theAppDeclaresPermissionForThePromotedStatusChip() {
        val permissions = application.packageManager
            .getPackageInfo(application.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertTrue(permissions.contains(Manifest.permission.POST_PROMOTED_NOTIFICATIONS))
    }

    @Test
    fun onlyTheEarliestTimerRequestsTheStatusChipRegardlessOfStartOrder() {
        startTimer(1, 900)
        startTimer(2, 60)
        startTimer(3, 300)

        assertEquals(2, shadowOf(service).lastForegroundNotificationId)
        assertPromotedTimers(2)
        assertEquals(3, service.timerObjects.size)
    }

    @Test
    fun pausingResumingAndStoppingTransferTheChipBetweenRemainingTimers() {
        startTimer(1, 60)
        startTimer(2, 300)

        sendAction(TimerService.ACTION_PAUSE_RESUME, 1)
        assertEquals(2, shadowOf(service).lastForegroundNotificationId)
        assertPromotedTimers(2)

        sendAction(TimerService.ACTION_PAUSE_RESUME, 1)
        assertEquals(1, shadowOf(service).lastForegroundNotificationId)
        assertPromotedTimers(1)

        sendAction(TimerService.ACTION_STOP, 1)
        assertEquals(2, shadowOf(service).lastForegroundNotificationId)
        assertPromotedTimers(2)

        sendAction(TimerService.ACTION_PAUSE_RESUME, 2)
        assertPromotedTimers()
    }

    @Test
    fun extendingAndResettingTheEarliestTimerReevaluateItsDeadline() {
        startTimer(1, 60)
        startTimer(2, 300)

        service.sendBroadcast(TimerService.updateStateIntent(TimerService.ACTION_ADD_TIME, 1))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, shadowOf(service).lastForegroundNotificationId)
        assertPromotedTimers(2)

        service.updateTimer(1, TimerSettings(seconds = 30, soundEnabled = false, vibrate = false))
        sendAction(TimerService.TIMER_RESTART, 1)
        assertEquals(1, shadowOf(service).lastForegroundNotificationId)
        assertPromotedTimers(1)
    }

    @Test
    fun anOlderTimerCanExpireFirstEvenWhenItsOriginalDurationIsLonger() {
        startTimer(1, 120)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(90))
        startTimer(2, 60)

        assertEquals(1, shadowOf(service).lastForegroundNotificationId)
        assertPromotedTimers(1)
    }

    @Test
    fun anExpiredTimerLeavesTheChipToTheNextCountdownWhileItRings() {
        startTimer(1, 60)
        startTimer(2, 300)
        service.onStartCommand(
            Intent(application, TimerService::class.java).setAction(TimerService.ACTION_TIMER_EXPIRED)
                .putExtra(TimerService.ID_EXTRA_KEY, 1), 0, 3
        )

        assertPromotedTimers(2)
        assertEquals(2, service.timerObjects.size)
        sendAction(TimerService.ACTION_STOP, 1)
        assertEquals(2, shadowOf(service).lastForegroundNotificationId)
        assertPromotedTimers(2)
    }

    private fun startTimer(id: Int, seconds: Int) {
        service.onStartCommand(
            Intent(application, TimerService::class.java).putExtra(
                TimerService.INITIAL_TIMER_EXTRA_KEY,
                TimerDescriptor(id, TimerSettings(seconds = seconds, soundEnabled = false, vibrate = false, incrementSeconds = 600))
            ), 0, id
        )
    }

    private fun sendAction(action: String, id: Int) {
        service.sendBroadcast(TimerService.updateStateIntent(action, id))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertPromotedTimers(vararg ids: Int) {
        val notifications = application.getSystemService(NotificationManager::class.java)
            .activeNotifications
        assertEquals(
            ids.toSet(),
            notifications.filter {
                it.notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING)
            }.map { it.id }.toSet()
        )
    }

    @Test
    fun aSharedDeadlineChangeTransfersPromotionBetweenSharedAndPersonalTimers() {
        startTimer(1, 300)
        val update = Intent(application, TimerService::class.java)
            .setAction(TimerService.SYNC_SHARED_TIMER_ACTION)
            .putExtra(TimerService.SHARED_TIMER_ID_EXTRA_KEY, "shared")
            .putExtra(TimerService.SHARED_TIMER_GROUP_ID_EXTRA_KEY, "group")
            .putExtra(TimerService.SHARED_TIMER_MEMBERSHIP_ID_EXTRA_KEY, "membership")
            .putExtra(TimerService.SHARED_TIMER_RUN_ID_EXTRA_KEY, "run")
            .putExtra(TimerService.SHARED_TIMER_DURATION_EXTRA_KEY, 600)
            .putExtra(TimerService.SHARED_TIMER_EXPIRES_EXTRA_KEY, System.currentTimeMillis() + 60_000)
        service.onStartCommand(update, 0, 2)
        val shared = service.timerObjects.single { it.sharedTimerId == "shared" }
        assertEquals(shared.id, shadowOf(service).lastForegroundNotificationId)
        assertPromotedTimers(shared.id)

        update.putExtra(TimerService.SHARED_TIMER_EXPIRES_EXTRA_KEY, System.currentTimeMillis() + 600_000)
        service.onStartCommand(update, 0, 3)
        assertEquals(1, shadowOf(service).lastForegroundNotificationId)
        assertPromotedTimers(1)
    }

}
