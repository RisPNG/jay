package com.bnyro.clock

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.presentation.screens.alarm.AlarmAlertScreen
import com.bnyro.clock.presentation.screens.ringing.RingingAlert
import com.bnyro.clock.presentation.screens.timer.TimerAlertScreen
import com.bnyro.clock.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SharedRingingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun initializePreferences() {
        context = ApplicationProvider.getApplicationContext()
        Preferences.init(context)
    }

    @Test
    fun sharedAlarmDisplaysItsGroup() {
        val ringingSince = System.currentTimeMillis() - 60_000
        composeRule.setContent {
            AlarmAlertScreen(
                onDismiss = {},
                onSnooze = {},
                label = "Wake up",
                groupName = "Household",
                snoozeEnabled = true,
                snoozeTime = 10,
                ringingSince = ringingSince
            )
        }

        composeRule.onNodeWithText("Household").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.ringing_snooze_minutes, 10))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.dismiss))
            .assertIsDisplayed()
    }

    @Test
    fun groupAppearsAboveTheRingingTitle() {
        composeRule.setContent {
            RingingAlert(
                icon = rememberVectorPainter(Icons.Outlined.Alarm),
                onDismiss = {},
                groupName = "Household",
                title = { Text("Ringing time") },
                controls = {}
            )
        }

        val group = composeRule.onNodeWithText("Household").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithText("Ringing time").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("The group should appear above the ringing title", group.bottom <= title.top)
    }

    @Test
    fun readOnlySharedTimerHidesMutatingControlsAndKeepsDismissal() {
        var incrementSeconds by mutableIntStateOf(30)
        var mutations = 0
        var dismissals = 0
        val ringingSince = System.currentTimeMillis() - 60_000
        composeRule.setContent {
            key(incrementSeconds) {
                TimerAlertScreen(
                    onDismiss = { dismissals++ },
                    onSnooze = { mutations++ },
                    onReset = { mutations++ },
                    label = "Stretch",
                    groupName = "Workout",
                    ringingSince = ringingSince,
                    incrementSeconds = incrementSeconds,
                    canEdit = false
                )
            }
        }

        listOf(30, 0).forEachIndexed { index, increment ->
            composeRule.runOnIdle { incrementSeconds = increment }
            composeRule.onNodeWithText("Workout").assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.ringing_add_seconds, 30))
                .assertDoesNotExist()
            composeRule.onNodeWithText(context.getString(R.string.timer_reset))
                .assertDoesNotExist()
            composeRule.onNodeWithContentDescription(context.getString(R.string.timer_reset))
                .assertDoesNotExist()
            composeRule.onNodeWithContentDescription(context.getString(R.string.subtract_seconds, 1))
                .assertDoesNotExist()
            composeRule.onNodeWithContentDescription(
                context.resources.getQuantityString(R.plurals.add_seconds, 1, 1)
            ).assertDoesNotExist()
            composeRule.onNodeWithContentDescription(context.getString(R.string.dismiss))
                .assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
            composeRule.runOnIdle {
                assertEquals(index + 1, dismissals)
                assertEquals(0, mutations)
            }
        }
    }
}
