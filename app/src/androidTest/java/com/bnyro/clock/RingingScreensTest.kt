package com.bnyro.clock

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.presentation.screens.alarm.AlarmAlertScreen
import com.bnyro.clock.presentation.screens.ringing.RingingTitle
import com.bnyro.clock.presentation.screens.timer.TimerAlertScreen
import com.bnyro.clock.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RingingScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun initializePreferences() {
        context = ApplicationProvider.getApplicationContext()
        Preferences.init(context)
    }

    @Test
    fun snoozeAdjustsOneMinuteAtATimeAndCannotGoBelowOne() {
        var snoozedMinutes: Int? = null
        var dismissals = 0
        composeRule.setContent {
            AlarmAlertScreen(
                onDismiss = { dismissals++ },
                onSnooze = { snoozedMinutes = it },
                snoozeEnabled = true,
                snoozeTime = 1,
                ringingSince = System.currentTimeMillis() - 60_000
            )
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.subtract_minutes, 1))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(context.getString(R.string.add_minutes, 1))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.ringing_snooze_minutes, 2))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.subtract_minutes, 1))
            .assertIsEnabled().performClick()
        composeRule.onNodeWithText(context.getString(R.string.ringing_snooze_minutes, 1))
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, snoozedMinutes)
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun snoozeCannotExceedSixtyMinutesAndReturnsTheSelectedDuration() {
        var snoozedMinutes: Int? = null
        composeRule.setContent {
            AlarmAlertScreen(
                onDismiss = {},
                onSnooze = { snoozedMinutes = it },
                snoozeEnabled = true,
                snoozeTime = 59,
                ringingSince = System.currentTimeMillis() - 60_000
            )
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.add_minutes, 1))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.ringing_snooze_minutes, 60))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.add_minutes, 1))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(context.getString(R.string.subtract_minutes, 1))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.ringing_snooze_minutes, 59))
            .performClick()

        composeRule.runOnIdle { assertEquals(59, snoozedMinutes) }
    }

    @Test
    fun alarmWithoutSnoozeKeepsOnlyTheDismissalControl() {
        composeRule.setContent {
            AlarmAlertScreen(
                onDismiss = {},
                onSnooze = {},
                snoozeEnabled = false,
                snoozeTime = 10,
                ringingSince = System.currentTimeMillis() - 60_000
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.ringing_snooze_minutes, 10))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.subtract_minutes, 1))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.add_minutes, 1))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.dismiss))
            .assertIsDisplayed()
    }

    @Test
    fun timerChangesFromSecondsToMinutesAndAddsTheSelectedDuration() {
        var addedSeconds: Int? = null
        var dismissals = 0
        var resets = 0
        composeRule.setContent {
            TimerAlertScreen(
                onDismiss = { dismissals++ },
                onSnooze = { addedSeconds = it },
                onReset = { resets++ },
                ringingSince = System.currentTimeMillis(),
                incrementSeconds = 119
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.ringing_add_seconds, 119))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.timer_reset))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.subtract_seconds, 1))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.resources.getQuantityString(R.plurals.add_seconds, 1, 1)
        ).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(context.getString(R.string.ringing_add_minutes, 2))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.subtract_minutes, 1))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.ringing_add_seconds, 60))
            .performClick()

        composeRule.runOnIdle {
            assertEquals(60, addedSeconds)
            assertEquals(0, dismissals)
            assertEquals(0, resets)
        }
    }

    @Test
    fun timerCannotGoBelowOneSecond() {
        var addedSeconds: Int? = null
        composeRule.setContent {
            TimerAlertScreen(
                onDismiss = {},
                onSnooze = { addedSeconds = it },
                onReset = {},
                ringingSince = System.currentTimeMillis(),
                incrementSeconds = 1
            )
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.subtract_seconds, 1))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(
            context.resources.getQuantityString(R.plurals.add_seconds, 1, 1)
        ).performClick()
        composeRule.onNodeWithText(context.getString(R.string.ringing_add_seconds, 2))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.subtract_seconds, 1))
            .assertIsEnabled().performClick()
        composeRule.onNodeWithText(context.getString(R.string.ringing_add_seconds, 1))
            .performClick()

        composeRule.runOnIdle { assertEquals(1, addedSeconds) }
    }

    @Test
    fun timerCannotExceedSixtyMinutes() {
        var addedSeconds: Int? = null
        composeRule.setContent {
            TimerAlertScreen(
                onDismiss = {},
                onSnooze = { addedSeconds = it },
                onReset = {},
                ringingSince = System.currentTimeMillis(),
                incrementSeconds = 60 * 60
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.ringing_add_minutes, 60))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.add_minutes, 1))
            .assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(context.getString(R.string.subtract_minutes, 1))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.ringing_add_minutes, 59))
            .performClick()

        composeRule.runOnIdle { assertEquals(59 * 60, addedSeconds) }
    }

    @Test
    fun timerWithoutAddTimeCanStillBeReset() {
        var additions = 0
        var dismissals = 0
        var resets = 0
        composeRule.setContent {
            TimerAlertScreen(
                onDismiss = { dismissals++ },
                onSnooze = { additions++ },
                onReset = { resets++ },
                ringingSince = System.currentTimeMillis(),
                incrementSeconds = 0
            )
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.subtract_seconds, 1))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(
            context.resources.getQuantityString(R.plurals.add_seconds, 1, 1)
        ).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.timer_reset)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, resets)
            assertEquals(0, additions)
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun labelPillSwitchesBetweenTheRingingTimeAndTheFullLabelOnTap() {
        val label = "Wake up for the early train to the airport"
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                RingingTitle(label = label, ringingTime = "07:23")
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("07:23").performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithText(label).assertIsDisplayed().performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithText("07:23").assertIsDisplayed()
        composeRule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun labelPillAlternatesAutomaticallyAndBlankLabelsKeepTheTime() {
        var label by mutableStateOf("Feed the cat")
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                RingingTitle(label = label, ringingTime = "07:23")
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("07:23").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(4500)
        composeRule.onNodeWithText("Feed the cat").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(4500)
        composeRule.onNodeWithText("07:23").assertIsDisplayed()
        composeRule.runOnIdle { label = "" }
        composeRule.mainClock.advanceTimeBy(4500)
        composeRule.onNodeWithText("07:23").assertIsDisplayed()
        composeRule.onNodeWithText("Feed the cat").assertDoesNotExist()
    }

    @Test
    fun tappingOrDraggingTheDismissHandleAShortDistanceDoesNotDismiss() {
        var dismissals = 0
        composeRule.setContent {
            AlarmAlertScreen(
                onDismiss = { dismissals++ },
                onSnooze = {},
                snoozeEnabled = false,
                snoozeTime = 10,
                ringingSince = System.currentTimeMillis() - 60_000
            )
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.dismiss))
            .performTouchInput { click(center) }
        composeRule.runOnIdle { assertEquals(0, dismissals) }

        val handle = composeRule.onNodeWithContentDescription(context.getString(R.string.dismiss))
            .fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        composeRule.onRoot().performTouchInput {
            swipe(handle.center, handle.center + Offset(root.width * 0.03f, 0f), 500)
        }

        composeRule.runOnIdle { assertEquals(0, dismissals) }
    }

    @Test
    fun longSwipesInEachDirectionDismissExactlyOnce() {
        var attempt by mutableIntStateOf(0)
        val dismissals = IntArray(4)
        composeRule.setContent {
            key(attempt) {
                val currentAttempt = attempt
                AlarmAlertScreen(
                    onDismiss = { dismissals[currentAttempt]++ },
                    onSnooze = {},
                    snoozeEnabled = false,
                    snoozeTime = 10,
                    ringingSince = System.currentTimeMillis() - 60_000
                )
            }
        }

        listOf(Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f))
            .forEachIndexed { index, direction ->
                composeRule.runOnIdle { attempt = index }
                val handle = composeRule
                    .onNodeWithContentDescription(context.getString(R.string.dismiss))
                    .fetchSemanticsNode().boundsInRoot
                val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
                val distance = minOf(root.width, root.height) * 0.45f
                composeRule.onRoot().performTouchInput {
                    swipe(handle.center, handle.center + direction * distance, 500)
                }
                composeRule.runOnIdle { assertEquals(1, dismissals[index]) }
            }
    }

    @Test
    fun timerControlsStayInsideTheViewportAtNormalAndLargeFontSizes() {
        var fontScale by mutableStateOf(1f)
        val ringingSince = System.currentTimeMillis() - 3_660_000
        composeRule.setContent {
            val density = LocalDensity.current
            val viewport = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Modifier.widthIn(max = 640.dp)
            } else {
                Modifier
            }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.fillMaxSize()) {
                    Box(viewport.fillMaxSize().testTag("ringing-viewport")) {
                        TimerAlertScreen(
                            onDismiss = {},
                            onSnooze = {},
                            onReset = {},
                            label = "Pasta",
                            ringingSince = ringingSince,
                            incrementSeconds = 30
                        )
                    }
                }
            }
        }
        val controls = listOf(
            "reset" to hasContentDescription(context.getString(R.string.timer_reset)),
            "minus" to hasContentDescription(context.getString(R.string.subtract_seconds, 1)),
            "add" to hasText(context.getString(R.string.ringing_add_seconds, 30)),
            "plus" to hasContentDescription(context.resources.getQuantityString(R.plurals.add_seconds, 1, 1))
        )

        listOf(1f, 1.5f, 2f).forEach { scale ->
            composeRule.runOnIdle { fontScale = scale }
            val viewport = composeRule.onNodeWithTag("ringing-viewport").getUnclippedBoundsInRoot()
            controls.forEach { (name, matcher) ->
                val control = composeRule.onNode(matcher).assertIsDisplayed()
                val bounds = control.getUnclippedBoundsInRoot()
                assertTrue("$name must have positive width at font scale $scale", bounds.right > bounds.left)
                assertTrue("$name must have positive height at font scale $scale", bounds.bottom > bounds.top)
                assertTrue("$name must fit horizontally at font scale $scale", bounds.left >= viewport.left && bounds.right <= viewport.right)
                assertTrue("$name must fit vertically at font scale $scale", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            }
        }
    }
}
