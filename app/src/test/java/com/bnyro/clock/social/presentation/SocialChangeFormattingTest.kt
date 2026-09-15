package com.bnyro.clock.social.presentation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.social.domain.SocialChange
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SocialChangeFormattingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val change = SocialChange(
        "1", "group", "Household", "alarm", "alarm", "edited", "Wake up", 25200000,
        "member", "Alex", null, null, null, null, "2026-09-15T00:00:00Z"
    )

    @Test
    fun groupLogsDescribeAlarmActionsInsteadOfGenericActivity() {
        for (action in listOf("created", "edited", "enabled", "disabled", "deleted", "snoozed", "dismissed", "ignored", "updated")) {
            val entry = change.copy(action = action)
            val title = entry.groupLogTitle(context)
            assertTrue(title.contains("Alex"))
            assertTrue(title.contains("Wake up"))
            assertFalse(title.contains("Activity updated"))
        }
    }

    @Test
    fun deliveryLogsIdentifyTheReceivingMember() {
        for (action in listOf("delivered", "received")) {
            val entry = change.copy(entityType = "delivery", action = action)
            assertTrue(entry.groupLogTitle(context).contains("Alex"))
            assertTrue(entry.alarmLogTitle(context).contains("Alex"))
        }
    }

    @Test
    fun alarmEditsRetainDetailsWhenEnabledStateAlsoChanges() {
        val entry = change.copy(details = buildJsonObject {
            put("previous_label", "Morning")
            put("label", "Wake up")
            put("previous_time", 21600000)
            put("time", 25200000)
            put("previous_sound_id", "old")
            put("sound_id", "new")
        })
        val detail = requireNotNull(entry.logDetails(context))
        assertTrue(detail.contains("Morning"))
        assertTrue(detail.contains("Wake up"))
        for (action in listOf("enabled", "disabled", "updated")) {
            assertEquals(detail, entry.copy(action = action).logDetails(context))
        }
    }
}
