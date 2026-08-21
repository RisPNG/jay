package com.bnyro.clock.social.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.presentation.screens.alarmpicker.components.AlarmPicker
import com.bnyro.clock.util.Preferences
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AlarmPickerPermissionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun initializePreferences() {
        Preferences.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun readOnlySharedAlarmDisablesEditingAndDestructiveActions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.setContent {
            MaterialTheme {
                AlarmPicker(
                    currentAlarm = Alarm(id = 1L, time = 0L),
                    advanced = false,
                    groups = emptyList(),
                    currentGroupId = null,
                    canSave = false,
                    onSave = { _, _ -> },
                    onDelete = {},
                    onCancel = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.alarm_name))
            .assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.delete))
            .assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.save))
            .assertIsNotEnabled()
    }
}
