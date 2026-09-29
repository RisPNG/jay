package com.bnyro.clock.social.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.App
import com.bnyro.clock.AppContainer
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.social.domain.MembershipAccessDto
import com.bnyro.clock.social.domain.SharedAlarmDto
import com.bnyro.clock.social.domain.SharedAlarmLink
import com.bnyro.clock.social.domain.SharedSoundMode
import com.bnyro.clock.social.domain.SocialGroupDto
import com.bnyro.clock.social.domain.SocialOccurrenceDto
import com.bnyro.clock.social.domain.SyncedResource
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.AlarmService
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = App::class)
class SharedAlarmSnoozeSyncTest {
    @Test
    fun remoteSnoozeMovesTheLocalAlarmWithoutResolvingItsOccurrence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<App>()
        Preferences.init(context)
        Preferences.instance.edit().clear().commit()
        val social = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        val alarms = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            context.container = AppContainer(context, alarms)
            val alarmRepository = AlarmRepository(alarms.alarmsDao())
            val localId = alarmRepository.addAlarm(Alarm(time = 0L, enabled = true))
            val occurrenceId = (System.currentTimeMillis() - 60_000L).toString()
            val snoozedUntil = System.currentTimeMillis() + 7 * 60_000L
            Preferences.edit {
                putString("${SocialPreferences.alarmOccurrencePrefix}$localId", occurrenceId)
            }
            social.socialDao().putAlarmLink(
                SharedAlarmLink("alarm", localId, "group", 1, SharedSoundMode.MEMBER_DEFAULT, null, null, null, "save")
            )
            social.socialDao().putResources(listOf(
                SyncedResource("identity", "membership", "membership", Json.encodeToString(
                    MembershipAccessDto("membership", "group", "group", "member", true, true)
                )),
                SyncedResource("group", "group", "group", Json.encodeToString(
                    SocialGroupDto("group", "Family", "everyone", true, true, true, true, sharedAnswers = true)
                )),
                SyncedResource("group", "alarm", "alarm", Json.encodeToString(
                    SharedAlarmDto(
                        id = "alarm", groupId = "group", revision = 1, saveId = "save",
                        time = 0L, label = "Wake up", enabled = true,
                        days = listOf(0, 1, 2, 3, 4, 5, 6), vibrate = true,
                        startDate = LocalDate.now().minusDays(1).toString(),
                        repeatInterval = 1, repeatUnit = "DAY", repeatAnchor = "DAY_OF_MONTH",
                        repeatDurationUnit = "DAY", snoozeEnabled = true, snoozeMinutes = 10,
                        vibrationPattern = listOf(0, 1000), vibrationPatternName = "Default"
                    )
                )),
                SyncedResource("group", "occurrence", "occurrence", Json.encodeToString(
                    SocialOccurrenceDto("alarm", occurrenceId, "pending", 1, Instant.ofEpochMilli(snoozedUntil).toString())
                ))
            ))

            SocialRepository(context, social, alarmRepository).refreshSharedState()

            val postponed = requireNotNull(alarmRepository.getAlarmById(localId))
            assertEquals(snoozedUntil, postponed.snoozedUntil)
            assertEquals(snoozedUntil, AlarmHelper.getAlarmTime(postponed))
            assertNull(postponed.dismissedAt)
            assertEquals(occurrenceId, Preferences.instance.getString("${SocialPreferences.alarmOccurrencePrefix}$localId", null))
            assertTrue(shadowOf(context).broadcastIntents.any {
                it.action == AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION &&
                    it.getLongExtra(AlarmHelper.EXTRA_ID, -1L) == localId
            })
            assertTrue(social.socialDao().getPendingOperations().none { it.kind == "outcome" })
        } finally {
            social.close()
            alarms.close()
            Preferences.instance.edit().clear().commit()
        }
    }
}
