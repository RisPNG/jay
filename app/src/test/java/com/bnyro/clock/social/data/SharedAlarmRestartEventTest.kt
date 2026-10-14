package com.bnyro.clock.social.data

import android.app.Application
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.util.Preferences
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SharedAlarmRestartEventTest {
    @Test
    fun personalSnoozeAndDismissalDoNotCreateASharedPhaseMarker() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Preferences.init(context.createDeviceProtectedStorageContext())
        Preferences.instance.edit().clear().commit()
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val alarm = Alarm(id = 9L, time = 0L)
        try {
            SocialAlarmEvents.snooze(context, alarm, System.currentTimeMillis() + 10 * 60_000L, "personal-occurrence")
            SocialAlarmEvents.dismiss(context, alarm.id, "personal-occurrence")
            assertFalse(Preferences.instance.contains("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}"))
            assertFalse(Preferences.instance.contains("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}"))
            assertFalse(Preferences.instance.contains("${SocialPreferences.alarmSnoozedAtPrefix}${alarm.id}"))
        } finally {
            Preferences.instance.edit().clear().commit()
        }
    }

    @Test
    fun automaticSnoozeKeepsItsCanonicalDeadlineAndOccurrenceWhileLocked() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Preferences.init(context.createDeviceProtectedStorageContext())
        Preferences.instance.edit().clear().commit()
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val alarm = Alarm(id = 8L, time = 0L, missedRestartEnabled = true)
        val deadline = System.currentTimeMillis() + 5 * 60_000L
        val nextRing = deadline + 10 * 60_000L
        Preferences.edit { putString("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}", "original-occurrence") }
        try {
            SocialAlarmEvents.snooze(context, alarm, nextRing, "original-occurrence", reason = "timed_out", occurredAtMillis = deadline)

            val event = JSONObject(Preferences.instance.all.entries.single { it.key.startsWith("jayPendingAlarmActivity:") }.value as String)
            assertEquals("SNOOZED", event.getString("activity_kind"))
            assertEquals("timed_out", event.getString("reason"))
            assertEquals("original-occurrence", event.getString("occurrence_id"))
            assertEquals(Instant.ofEpochMilli(deadline).toString(), event.getString("occurred_at"))
            assertEquals(Instant.ofEpochMilli(nextRing).toString(), event.getString("snoozed_until"))
            assertEquals(nextRing + 5 * 60_000L, Preferences.instance.getLong("${SocialPreferences.alarmDeadlinePrefix}${alarm.id}", -1L))
            assertFalse(context.getDatabasePath("androidx.work.workdb").exists())
        } finally {
            Preferences.instance.edit().clear().commit()
        }
    }
}
