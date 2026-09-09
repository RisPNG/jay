package com.bnyro.clock.social.presentation

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.navigation.HomeRoutes
import com.bnyro.clock.social.data.SocialSyncResult
import com.bnyro.clock.social.domain.AlarmPermission
import com.bnyro.clock.social.domain.MemberRole
import com.bnyro.clock.social.domain.SocialChange
import com.bnyro.clock.social.domain.SocialGroup
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SocialNotificationHelperTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val group = SocialGroup(
        "group-one", "Household", AlarmPermission.EVERYONE,
        true, true, true, true, true, true, MemberRole.MEMBER
    )
    private val change = SocialChange(
        "1", group.id, group.name, "membership", "member-one", "joined",
        null, null, "other-device", "Other", "member-one", "Member", null, null,
        "2026-09-09T10:00:00Z"
    )
    private val coordinator = SocialActivityCoordinator(
        Robolectric.buildActivity(ComponentActivity::class.java).get()
    )

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        context.getSharedPreferences("jay_social_notification_accumulation", Context.MODE_PRIVATE)
            .edit().clear().commit()
        SocialNotificationHelper.createNotificationChannel(context)
    }

    @Test
    fun membershipNotificationTargetsItsGroupLogs() {
        SocialNotificationHelper.notifySocialChanges(
            context, SocialSyncResult(listOf(change), mapOf(group.id to group), "self")
        )
        val intent = shadowOf(manager.activeNotifications.single().notification.contentIntent).savedIntent
        assertEquals(group.id, intent.getStringExtra(SocialNotificationHelper.EXTRA_SOCIAL_GROUP_ID))
        assertEquals(HomeRoutes.Groups, coordinator.homeRoute(intent))
    }

    @Test
    fun alarmChangesAndOutcomesTargetTheirAlarmLogs() {
        for (entityType in listOf("alarm", "outcome")) {
            manager.cancelAll()
            SocialNotificationHelper.notifySocialChanges(
                context, SocialSyncResult(
                    listOf(change.copy(entityType = entityType, entityId = "alarm-one", action = "dismissed")),
                    mapOf(group.id to group), "self"
                )
            )
            val intent = shadowOf(manager.activeNotifications.single().notification.contentIntent).savedIntent
            assertEquals("alarm-one", intent.getStringExtra(SocialNotificationHelper.EXTRA_SOCIAL_ENTITY_ID))
            assertEquals(group.id, intent.getStringExtra(SocialNotificationHelper.EXTRA_SOCIAL_GROUP_ID))
            assertEquals(HomeRoutes.Alarm, coordinator.homeRoute(intent))
        }
    }

    @Test
    fun notificationsForDifferentGroupsKeepTheirOwnDestinations() {
        val secondGroup = group.copy(id = "group-two")
        for (target in listOf(group, secondGroup)) {
            SocialNotificationHelper.notifySocialChanges(
                context, SocialSyncResult(
                    listOf(change.copy(groupId = target.id)), mapOf(target.id to target), "self"
                )
            )
        }
        val destinations = manager.activeNotifications.map {
            shadowOf(it.notification.contentIntent).savedIntent
                .getStringExtra(SocialNotificationHelper.EXTRA_SOCIAL_GROUP_ID)
        }.toSet()
        assertEquals(setOf(group.id, secondGroup.id), destinations)
    }

    @Test
    fun accumulatedNotificationsTargetTheGroupContainingAllEvents() {
        for (alarmId in listOf("alarm-one", "alarm-two")) {
            SocialNotificationHelper.notifySocialChanges(
                context, SocialSyncResult(
                    listOf(change.copy(entityType = "alarm", entityId = alarmId, action = "updated")),
                    mapOf(group.id to group), "self"
                )
            )
        }
        val intent = shadowOf(manager.activeNotifications.single().notification.contentIntent).savedIntent
        assertEquals(group.id, intent.getStringExtra(SocialNotificationHelper.EXTRA_SOCIAL_GROUP_ID))
        assertEquals(HomeRoutes.Groups, coordinator.homeRoute(intent))
    }
}
