package com.bnyro.clock.social.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.RepeatUnit
import com.bnyro.clock.presentation.screens.alarmpicker.components.AlarmPicker
import com.bnyro.clock.social.domain.AlarmPermission
import com.bnyro.clock.social.domain.MemberRole
import com.bnyro.clock.social.domain.SocialGroup
import com.bnyro.clock.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AlarmPickerPermissionsTest {
    private val group = SocialGroup(
        id = "hourly-test-group", name = "Household", alarmPermission = AlarmPermission.EVERYONE,
        notifyAlarmChanges = true, notifySnoozed = true, notifyDismissed = true,
        notifyIgnored = true, notifyMembership = true, notifyAdministrative = true,
        role = MemberRole.LEADER
    )

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

    @Test
    fun choosingHoursSavesTheIntervalAndRemovesCalendarRunControls() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var saved: Alarm? = null
        composeRule.setContent {
            MaterialTheme {
                AlarmPicker(
                    currentAlarm = Alarm(
                        time = 8 * 3_600_000L, repeatUnit = RepeatUnit.DAY, repeatInterval = 2,
                        repeatDuration = 3, repeatDurationUnit = RepeatUnit.WEEK,
                        endOccurrences = 3, advanced = true
                    ),
                    advanced = true, groups = listOf(group), currentGroupId = group.id,
                    canUseAdvancedGroupTiming = true, canUseHourlyGroupRecurrence = true,
                    onSave = { alarm, _ -> saved = alarm }, onCancel = {}
                )
            }
        }

        composeRule.onNodeWithText(context.resources.getQuantityString(R.plurals.repeat_unit_days, 2))
            .performScrollTo().performClick()
        composeRule.onNodeWithText(context.resources.getQuantityString(R.plurals.repeat_unit_hours, 2))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.repeats_for)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.repeats_on)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.shared_hourly_alarm_info))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.save)).performClick()
        composeRule.runOnIdle {
            assertEquals(RepeatUnit.HOUR, saved?.repeatUnit)
            assertEquals(2, saved?.repeatInterval)
            assertEquals(3, saved?.endOccurrences)
            assertNull(saved?.repeatDuration)
            assertFalse(requireNotNull(saved).startWithLaterOffset)
        }
    }

    @Test
    fun anOlderServerCannotSaveAnHourlyPersonalAlarmIntoAGroup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var saved: Alarm? = null
        composeRule.setContent {
            MaterialTheme {
                AlarmPicker(
                    currentAlarm = Alarm(time = 8 * 3_600_000L, repeatUnit = RepeatUnit.HOUR, advanced = true),
                    advanced = true, groups = listOf(group), currentGroupId = null,
                    canUseAdvancedGroupTiming = true, canUseHourlyGroupRecurrence = false,
                    onSave = { alarm, _ -> saved = alarm }, onCancel = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.personal_alarm)).performScrollTo().performClick()
        composeRule.onNodeWithText(group.name).performClick()
        composeRule.onNodeWithText(context.getString(R.string.shared_hourly_alarm_unavailable))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.save)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.resources.getQuantityString(R.plurals.repeat_unit_hours, 1))
            .performScrollTo().performClick()
        composeRule.onNodeWithText(context.resources.getQuantityString(R.plurals.repeat_unit_days, 1))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.save)).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(RepeatUnit.DAY, saved?.repeatUnit) }
    }
}
