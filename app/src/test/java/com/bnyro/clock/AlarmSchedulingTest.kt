package com.bnyro.clock

import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.RepeatUnit
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.util.AlarmHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmSchedulingTest {
    @Test
    fun receivingAMissedOneTimeAlarmPreservesItsDateAndDisablesIt(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<App>()
        val repository = app.container.alarmRepository
        val useCase = CreateUpdateDeleteAlarmUseCase(app, repository)
        for (zone in listOf(ZoneId.of("Asia/Kuala_Lumpur"), ZoneId.of("America/New_York"))) {
            val date = LocalDate.now(zone).minusDays(1).toEpochDay()
            val id = useCase.createAlarm(Alarm(time = 0, enabled = true, startDate = date, repeatUnit = RepeatUnit.DAY, endOccurrences = 1), zone)
            val received = requireNotNull(repository.getAlarmById(id))
            assertEquals(date, received.startDate)
            assertFalse(received.enabled)
            useCase.updateAlarm(received.copy(enabled = true, label = "Updated remotely"), zone)
            val updated = requireNotNull(repository.getAlarmById(id))
            assertEquals(date, updated.startDate)
            assertFalse(updated.enabled)
            useCase.deleteAlarm(updated)
        }
    }

    @Test
    fun receivingAFiniteRecurringAlarmDoesNotRestartItsOccurrenceCount(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<App>()
        val repository = app.container.alarmRepository
        val useCase = CreateUpdateDeleteAlarmUseCase(app, repository)
        val zone = ZoneId.of("UTC")
        val date = LocalDate.now(zone).minusDays(1).toEpochDay()
        val id = useCase.createAlarm(Alarm(time = 0, enabled = true, startDate = date, repeatUnit = RepeatUnit.DAY, endOccurrences = 3), zone)
        val received = requireNotNull(repository.getAlarmById(id))
        assertEquals(date, received.startDate)
        assertTrue(received.enabled)
        assertEquals(LocalDate.now(zone).plusDays(1), AlarmHelper.getNextOccurrence(received, timeZone = zone))
        useCase.updateAlarm(received.copy(endOccurrences = 2), zone)
        assertFalse(requireNotNull(repository.getAlarmById(id)).enabled)
        useCase.deleteAlarm(received)
    }

    @Test
    fun futureReceiptAndExplicitRearmingKeepTheirIntendedBehavior(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<App>()
        val repository = app.container.alarmRepository
        val useCase = CreateUpdateDeleteAlarmUseCase(app, repository)
        val zone = ZoneId.of("UTC")
        val tomorrow = LocalDate.now(zone).plusDays(1)
        val id = useCase.createAlarm(Alarm(time = 0, enabled = true, startDate = tomorrow.toEpochDay(), repeatUnit = RepeatUnit.DAY, endOccurrences = 1), zone)
        val received = requireNotNull(repository.getAlarmById(id))
        assertTrue(received.enabled)
        assertEquals(tomorrow, AlarmHelper.getNextOccurrence(received, timeZone = zone))
        val rearmed = received.copy(startDate = tomorrow.minusDays(2).toEpochDay())
        useCase.prepareForScheduling(rearmed, zone)
        useCase.updateAlarm(rearmed, zone)
        val updated = requireNotNull(repository.getAlarmById(id))
        assertTrue(updated.enabled)
        assertEquals(tomorrow.toEpochDay(), updated.startDate)
        useCase.deleteAlarm(updated)
    }
}
