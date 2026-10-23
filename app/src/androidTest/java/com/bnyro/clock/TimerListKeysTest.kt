package com.bnyro.clock

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.bnyro.clock.domain.model.TimerDescriptor
import com.bnyro.clock.domain.model.TimerPickerBehaviour
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.domain.model.WatchState
import com.bnyro.clock.presentation.screens.settings.model.SettingsModel
import com.bnyro.clock.presentation.screens.timer.TimerScreen
import com.bnyro.clock.presentation.screens.timer.model.TimerModel
import com.bnyro.clock.util.Preferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TimerListKeysTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var previousSavedTimers: String? = null

    @Before
    fun initializePreferences() {
        Preferences.init(ApplicationProvider.getApplicationContext())
        previousSavedTimers = Preferences.instance.getString(Preferences.savedTimersKey, null)
    }

    @After
    fun restorePreferences() {
        Preferences.edit { putString(Preferences.savedTimersKey, previousSavedTimers) }
    }

    @Test
    fun restoredAndSavedTimersWithTheSameIdRenderWithoutMixingSavedEdits() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val restored = TimerDescriptor(1, TimerSettings(seconds = 60, label = "Imported timer"))
            .asScheduledObject().apply { state.value = WatchState.PAUSED }
        val timerModel = TimerModel()
        timerModel.savedTimers = listOf(TimerSettings(id = 1, seconds = 120, label = "Saved timer"))
        timerModel.onChangeTimers(arrayOf(restored))
        val settingsModel = SettingsModel().apply {
            timerPickerBehaviour = TimerPickerBehaviour.HIDE
        }
        composeRule.setContent {
            MaterialTheme {
                TimerScreen(onClickSettings = {}, timerModel = timerModel, settingsModel = settingsModel)
            }
        }

        composeRule.onNodeWithText("Imported timer").assertIsDisplayed()
        composeRule.onNodeWithText("Saved timer").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNode(hasText("Saved timer") and hasSetTextAction())
            .performTextReplacement("Edited saved timer")
        closeSoftKeyboard()
        composeRule.onNodeWithText(context.getString(R.string.save)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            timerModel.savedTimers.single().label == "Edited saved timer"
        }
        composeRule.runOnIdle {
            assertEquals("Edited saved timer", timerModel.savedTimers.single().label)
            assertEquals("Imported timer", restored.label.value)
        }

        composeRule.onNodeWithText("Imported timer").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Edited saved timer").performScrollTo().assertIsDisplayed()
    }
}
