package com.bnyro.clock.social.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.App
import com.bnyro.clock.domain.model.RepeatUnit
import com.bnyro.clock.social.domain.AlarmActivityRequest
import com.bnyro.clock.social.domain.AlarmOccurrenceSchedule
import com.bnyro.clock.social.domain.SharedSoundSelection
import com.bnyro.clock.social.domain.SharedTimerDto
import com.bnyro.clock.social.domain.SharedTimerRequest
import com.bnyro.clock.social.domain.SocialOccurrenceDto
import com.bnyro.clock.social.domain.TimerOccurrenceDto
import com.bnyro.clock.social.domain.TimerOccurrenceRequest
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import com.bnyro.clock.social.domain.AlarmPermission
import com.bnyro.clock.util.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SocialBackendIntegrationTest {
    @Test
    fun queuedChangesReachTheServerWithoutALiveStreamOrManualSync() = runBlocking {
        val server = InstrumentationRegistry.getArguments().getString("jayTestServer")
        assumeTrue("Requires an isolated Django test server", server != null)
        val app = ApplicationProvider.getApplicationContext<App>()
        assertTrue(server == Preferences.instance.getString(SocialPreferences.serverUrlKey, null))
        val repository = app.container.socialRepository
        repository.synchronize()
        val dao = SocialDatabase.getDatabase(app).socialDao()
        val api = SocialApi(requireNotNull(server), DeviceIdentityStore.loadOrCreate(app, server))
        val name = "Queued ${UUID.randomUUID()}"
        repository.createGroup(name, AlarmPermission.EVERYONE, false, false, false, false)
        withTimeout(10_000) {
            while (dao.getPendingOperations().isNotEmpty()) delay(50)
        }
        val group = repository.groups.first().single { it.name == name }
        try {
            repository.createSharedAlarm(group.id, Alarm(time = 28_800_000, label = "Queued alarm"))
            withTimeout(10_000) {
                while (dao.getPendingOperations().isNotEmpty()) delay(50)
            }
            assertTrue(api.synchronizeScope("/v1/groups/${group.id}/sync", null).items.any {
                it.kind == "alarm" && it.data["label"]?.jsonPrimitive?.content == "Queued alarm"
            })
            repository.startSharedTimer(group.id, "Queued timer", TimerSettings(seconds = 600))
            withTimeout(10_000) {
                while (dao.getPendingOperations().isNotEmpty()) delay(50)
            }
            assertTrue(api.synchronizeScope("/v1/groups/${group.id}/sync", null).items.any {
                it.kind == "timer"
            })
        } finally {
            repository.deleteGroup(group.id)
            withTimeout(10_000) {
                while (dao.getPendingOperations().isNotEmpty()) delay(50)
            }
        }
    }

    @Test
    fun sharedItemsReconcileAndGroupDeletionPreservesPersonalAlarms() = runBlocking {
        val server = InstrumentationRegistry.getArguments().getString("jayTestServer")
        assumeTrue("Requires an isolated Django test server", server != null)
        val app = ApplicationProvider.getApplicationContext<App>()
        assertTrue(server == Preferences.instance.getString(SocialPreferences.serverUrlKey, null))
        val repository = app.container.socialRepository
        repository.synchronize()
        val name = "Emulator ${UUID.randomUUID()}"
        repository.createGroup(name, AlarmPermission.EVERYONE, false, false, false, false)
        repository.synchronize()
        val group = repository.groups.first().single { it.name == name }
        val dao = SocialDatabase.getDatabase(app).socialDao()
        assertTrue(dao.getPendingOperations().isEmpty())
        assertTrue(dao.getScope("/v1/groups/${group.id}/sync")?.cursor != null)
        val alarms = app.container.alarmRepository
        val personalId = alarms.addAlarm(Alarm(time = 25_200_000, label = "Personal emulator alarm"))
        val alarmId = repository.createSharedAlarm(group.id, Alarm(time = 28_800_000, label = "Shared emulator alarm"))
        repository.synchronize()
        val link = requireNotNull(dao.getAlarmLinkByLocalId(alarmId))
        assertNotNull(alarms.getAlarmById(alarmId))
        assertTrue(dao.getScopeResources(group.id).any { it.kind == "alarm" && it.key == link.remoteAlarmId })
        repository.updateAlarm(requireNotNull(alarms.getAlarmById(alarmId)).copy(label = "Updated shared alarm"))
        repository.synchronize()
        assertEquals("Updated shared alarm", alarms.getAlarmById(alarmId)?.label)
        repository.startSharedTimer(group.id, "Emulator countdown", TimerSettings(seconds = 600))
        repository.synchronize()
        val timer = dao.getScopeResources(group.id).single { it.kind == "timer" }
        val expiry = OffsetDateTime.parse(Json.parseToJsonElement(timer.representation).jsonObject.getValue("expires_at").jsonPrimitive.content).toInstant().toEpochMilli() + 60_000
        val operationId = UUID.randomUUID().toString()
        val savedAt = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()
        repository.adjustSharedTimer(timer.key, group.id, group.membershipId, runId, 0, expiry, operationId, savedAt)
        repository.synchronize()
        repository.adjustSharedTimer(timer.key, group.id, group.membershipId, runId, 0, expiry, operationId, savedAt)
        repository.synchronize()
        val updated = dao.getScopeResources(group.id).single { it.kind == "timer" }
        assertEquals(expiry, OffsetDateTime.parse(Json.parseToJsonElement(updated.representation).jsonObject.getValue("expires_at").jsonPrimitive.content).toInstant().toEpochMilli())
        repository.cancelSharedTimer(timer.key, group.id, group.membershipId, UUID.randomUUID().toString())
        repository.synchronize()
        assertTrue(dao.getScopeResources(group.id).none { it.kind == "timer" })
        repository.deleteGroup(group.id)
        repository.synchronize()
        assertTrue(repository.groups.first().none { it.id == group.id })
        assertNull(alarms.getAlarmById(alarmId))
        assertNotNull(alarms.getAlarmById(personalId))
        alarms.deleteAlarm(requireNotNull(alarms.getAlarmById(personalId)))
    }

    @Test
    fun durableMemberChangesRespectRevocationAndDeletion() = runBlocking {
        val server = InstrumentationRegistry.getArguments().getString("jayTestServer")
        assumeTrue("Requires an isolated Django test server", server != null)
        val app = ApplicationProvider.getApplicationContext<App>()
        assertEquals(server, Preferences.instance.getString(SocialPreferences.serverUrlKey, null))
        val repository = app.container.socialRepository
        repository.synchronize()
        val name = "Permissions ${UUID.randomUUID()}"
        repository.createGroup(name, AlarmPermission.EVERYONE, false, false, false, false)
        repository.synchronize()
        val group = repository.groups.first().single { it.name == name }
        val invitation = requireNotNull(repository.createInvite(group.id))
        val token = requireNotNull(SocialLink.parse(invitation)?.parameters?.get("token"))
        val peerId = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val api = SocialApi(requireNotNull(server), DeviceIdentity(peerId, "Emulator peer", UUID.randomUUID().toString(), ""))
        api.register()
        val databaseName = "emulator-peer-${UUID.randomUUID()}"
        var database = Room.databaseBuilder(app, SocialDatabase::class.java, databaseName).build()
        try {
            var synchronization = SocialSynchronization(app, database)
            synchronization.saveOperation(peerId, "join", token, "POST", "/v1/groups/join",
                JsonObject(mapOf("token" to JsonPrimitive(token))), null, ordered = false)
            synchronization.flushOperations(api)
            synchronization.synchronizeScope(api, "/v1/sync")
            val membership = database.socialDao().getResources().single { it.kind == "membership" }
            val membershipId = Json.parseToJsonElement(membership.representation).jsonObject.getValue("id").jsonPrimitive.content
            synchronization.synchronizeScope(api, "/v1/groups/${group.id}/sync")
            repository.synchronize()
            repository.startSharedTimer(group.id, "Permission timer", TimerSettings(seconds = 600))
            repository.synchronize()
            synchronization.synchronizeScope(api, "/v1/groups/${group.id}/sync")
            val timer = database.socialDao().getScopeResources(group.id).single { it.kind == "timer" }
            val state = Json.parseToJsonElement(timer.representation).jsonObject
            val payload = JsonObject(state.filterKeys {
                when (it) {
                    "label", "duration_seconds", "increment_seconds", "expires_at", "vibrate", "vibration_pattern", "vibration_pattern_name" -> true
                    else -> false
                }
            } + mapOf(
                "membership_id" to JsonPrimitive(membershipId),
                "sound" to JsonObject(mapOf("mode" to JsonPrimitive("member_default"), "sound_id" to kotlinx.serialization.json.JsonNull))
            ))
            val revoked = synchronization.saveOperation(group.id, "timer", timer.key, "PUT", "/v1/timers/${timer.key}", payload, state)
            database.close()
            database = Room.databaseBuilder(app, SocialDatabase::class.java, databaseName).build()
            synchronization = SocialSynchronization(app, database)
            assertEquals(revoked.payload, database.socialDao().getOperation(revoked.operationId)?.payload)
            repository.saveGroupSettings(group.copy(alarmPermission = AlarmPermission.LEADERS))
            repository.synchronize()
            synchronization.flushOperations(api)
            assertEquals("rejected", database.socialDao().getOperation(revoked.operationId)?.state)
            assertEquals("editor_required", database.socialDao().getOperation(revoked.operationId)?.rejectionCode)
            repository.saveGroupSettings(group)
            repository.synchronize()
            val deleted = synchronization.saveOperation(group.id, "timer", timer.key, "PUT", "/v1/timers/${timer.key}", payload, state)
            repository.cancelSharedTimer(timer.key, group.id, group.membershipId, UUID.randomUUID().toString())
            repository.synchronize()
            synchronization.flushOperations(api)
            assertEquals("rejected", database.socialDao().getOperation(deleted.operationId)?.state)
            synchronization.synchronizeScope(api, "/v1/groups/${group.id}/sync")
            assertTrue(database.socialDao().getScopeResources(group.id).none { it.kind == "timer" })
            repository.deleteGroup(group.id)
            repository.synchronize()
        } finally {
            database.close()
            app.deleteDatabase(databaseName)
        }
        Unit
    }

    @Test
    fun alarmRestartsKeepTheFinalOccurrenceAndIndependentTimeoutPolicies() = runBlocking {
        val server = InstrumentationRegistry.getArguments().getString("jayTestServer")
        assumeTrue("Requires an isolated Django test server", server != null)
        val app = ApplicationProvider.getApplicationContext<App>()
        assertEquals(server, Preferences.instance.getString(SocialPreferences.serverUrlKey, null))
        val repository = app.container.socialRepository
        repository.synchronize()
        val name = "Restart protocol ${UUID.randomUUID()}"
        repository.createGroup(name, AlarmPermission.EVERYONE, false, false, false, false)
        repository.synchronize()
        val group = repository.groups.first().single { it.name == name }
        val invitation = requireNotNull(repository.createInvite(group.id))
        val token = requireNotNull(SocialLink.parse(invitation)?.parameters?.get("token"))
        val peerId = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val api = SocialApi(requireNotNull(server), DeviceIdentity(peerId, "Restart peer", UUID.randomUUID().toString(), ""))
        api.register()
        val databaseName = "restart-peer-${UUID.randomUUID()}"
        var database = Room.databaseBuilder(app, SocialDatabase::class.java, databaseName).build()
        try {
            var synchronization = SocialSynchronization(app, database)
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            synchronization.saveOperation(peerId, "join", token, "POST", "/v1/groups/join",
                JsonObject(mapOf("token" to JsonPrimitive(token))), null, ordered = false)
            synchronization.flushOperations(api)
            synchronization.synchronizeScope(api, "/v1/sync")
            val membership = database.socialDao().getResources().single { it.kind == "membership" }
            val membershipId = Json.parseToJsonElement(membership.representation).jsonObject.getValue("id").jsonPrimitive.content
            val trigger = Instant.ofEpochMilli(System.currentTimeMillis() - 30 * 60_000L).truncatedTo(ChronoUnit.MINUTES)
            val localTrigger = trigger.atZone(ZoneId.systemDefault())
            val alarmIds = mutableListOf<String>()
            for ((timeout, restart) in listOf(2 to 3, 7 to 5)) {
                val localId = repository.createSharedAlarm(group.id, Alarm(
                    time = localTrigger.toLocalTime().toSecondOfDay() * 1000L,
                    label = "Timeout $timeout", enabled = true, vibrate = false, soundEnabled = false,
                    startDate = localTrigger.toLocalDate().toEpochDay(), repeatUnit = RepeatUnit.DAY,
                    endOccurrences = 1, snoozeEnabled = false, timeoutMinutes = timeout,
                    missedRestartEnabled = true, missedRestartMinutes = restart
                ))
                repository.synchronize()
                val local = requireNotNull(app.container.alarmRepository.getAlarmById(localId))
                assertEquals(timeout, local.timeoutMinutes)
                assertEquals(restart, local.missedRestartMinutes)
                assertTrue(local.missedRestartEnabled)
                assertTrue(!local.snoozeEnabled)
                val link = requireNotNull(SocialDatabase.getDatabase(app).socialDao().getAlarmLinkByLocalId(localId))
                alarmIds += link.remoteAlarmId
                val state = Json.parseToJsonElement(SocialDatabase.getDatabase(app).socialDao().getScopeResources(group.id)
                    .single { it.kind == "alarm" && it.key == link.remoteAlarmId }.representation).jsonObject
                val historical = JsonObject(state.filterKeys {
                    it !in setOf("id", "group_id", "revision", "save_id", "saved_at", "sound_mode", "sound_id", "sound_title")
                } + mapOf(
                    "start_date" to JsonPrimitive(localTrigger.toLocalDate().toString()),
                    "membership_id" to JsonPrimitive(membershipId),
                    "sound" to json.encodeToJsonElement(SharedSoundSelection("off")).jsonObject
                ))
                val update = synchronization.saveOperation(group.id, "alarm", link.remoteAlarmId, "PUT",
                    "/v1/alarms/${link.remoteAlarmId}", historical, null)
                synchronization.flushOperations(api)
                assertEquals("accepted", database.socialDao().getOperation(update.operationId)?.state)
                val revision = Json.parseToJsonElement(requireNotNull(database.socialDao().getOperation(update.operationId)?.response))
                    .jsonObject.getValue("revision").jsonPrimitive.content.toInt()
                val schedule = AlarmOccurrenceSchedule(revision, trigger.toEpochMilli().toString(), trigger.toString(),
                    trigger.plusSeconds(3600).toString(), localTrigger.toLocalDate().toString(), membershipId)
                val registration = synchronization.saveOperation(group.id, "occurrence", schedule.occurrenceId, "PUT",
                    "/v1/alarms/${link.remoteAlarmId}/occurrence", json.encodeToJsonElement(schedule).jsonObject,
                    null, ordered = false)
                synchronization.flushOperations(api)
                assertEquals("accepted", database.socialDao().getOperation(registration.operationId)?.state)
            }
            synchronization.synchronizeScope(api, "/v1/groups/${group.id}/sync")
            val original = database.socialDao().getScopeResources(group.id).single {
                it.kind == "occurrence" && json.decodeFromString<SocialOccurrenceDto>(it.representation).let {
                    it.alarmId == alarmIds[0] && it.occurrenceId == trigger.toEpochMilli().toString()
                }
            }
            val originalState = json.decodeFromString<SocialOccurrenceDto>(original.representation)
            assertEquals(trigger.plusSeconds(120), Instant.parse(originalState.deadlineAt))
            for ((deadlineMinutes, restartMinutes) in listOf(2L to 5L, 7L to 10L)) {
                val eventId = UUID.randomUUID().toString()
                val activity = AlarmActivityRequest(eventId, originalState.alarmRevision, "snoozed",
                    trigger.plusSeconds(deadlineMinutes * 60).toString(), originalState.occurrenceId, "timed_out", membershipId,
                    trigger.plusSeconds(restartMinutes * 60).toString())
                val operation = synchronization.saveOperation(group.id, "outcome", eventId, "POST",
                    "/v1/alarms/${alarmIds[0]}/activity", json.encodeToJsonElement(activity).jsonObject,
                    null, ordered = false, operationId = eventId)
                synchronization.flushOperations(api)
                assertEquals("accepted", database.socialDao().getOperation(eventId)?.state)
                assertEquals(Json.parseToJsonElement(requireNotNull(database.socialDao().getOperation(eventId)?.response)), Json.parseToJsonElement(api.executeOperation(operation)))
                val route = "/v1/groups/${group.id}/sync"
                val previousRevision = requireNotNull(database.socialDao().getScope(route)).revision
                synchronization.synchronizeScope(api, route)
                assertTrue(requireNotNull(database.socialDao().getScope(route)).revision > previousRevision)
                val rows = database.socialDao().getScopeResources(group.id).filter {
                    it.kind == "occurrence" && json.decodeFromString<SocialOccurrenceDto>(it.representation).occurrenceId == originalState.occurrenceId
                }
                assertEquals(2, rows.size)
                val phase = json.decodeFromString<SocialOccurrenceDto>(rows.single { it.key == original.key }.representation)
                assertEquals(originalState.occurrenceId, phase.occurrenceId)
                assertEquals(originalState.alarmRevision, phase.alarmRevision)
                assertEquals(trigger, Instant.parse(phase.triggerAt))
                assertEquals("pending", phase.status)
                assertEquals(trigger.plusSeconds(restartMinutes * 60), Instant.parse(phase.snoozedUntil))
                assertEquals(trigger.plusSeconds(deadlineMinutes * 60), Instant.parse(phase.snoozedAt))
                assertEquals(trigger.plusSeconds((restartMinutes + 2) * 60), Instant.parse(phase.deadlineAt))
                val independent = json.decodeFromString<SocialOccurrenceDto>(rows.single { it.key != original.key }.representation)
                assertEquals(trigger.plusSeconds(7 * 60), Instant.parse(independent.deadlineAt))
                assertNull(independent.snoozedUntil)
                if (deadlineMinutes == 2L) {
                    database.close()
                    database = Room.databaseBuilder(app, SocialDatabase::class.java, databaseName).build()
                    synchronization = SocialSynchronization(app, database)
                    assertEquals(phase, json.decodeFromString<SocialOccurrenceDto>(
                        database.socialDao().getScopeResources(group.id).single { it.key == original.key }.representation))
                }
            }
            for (activity in listOf(
                AlarmActivityRequest(UUID.randomUUID().toString(), originalState.alarmRevision, "snoozed",
                    trigger.plusSeconds(2 * 60).toString(), originalState.occurrenceId, "timed_out", membershipId,
                    trigger.plusSeconds(5 * 60).toString()),
                AlarmActivityRequest(UUID.randomUUID().toString(), originalState.alarmRevision, "ignored",
                    trigger.plusSeconds(12 * 60).toString(), originalState.occurrenceId, "no_response", membershipId)
            )) {
                val stale = synchronization.saveOperation(group.id, "outcome", activity.id, "POST",
                    "/v1/alarms/${alarmIds[0]}/activity", json.encodeToJsonElement(activity).jsonObject,
                    null, ordered = false, operationId = activity.id)
                synchronization.flushOperations(api)
                assertEquals(if (activity.reason == "timed_out") "confirmed" else "rejected", database.socialDao().getOperation(stale.operationId)?.state)
                assertEquals("response_outdated", database.socialDao().getOperation(stale.operationId)?.rejectionCode)
            }
            val dismissal = AlarmActivityRequest(UUID.randomUUID().toString(), originalState.alarmRevision, "dismissed",
                trigger.plusSeconds(11 * 60).toString(), originalState.occurrenceId, null, membershipId)
            synchronization.saveOperation(group.id, "outcome", dismissal.id, "POST", "/v1/alarms/${alarmIds[0]}/activity",
                json.encodeToJsonElement(dismissal).jsonObject, null, ordered = false, operationId = dismissal.id)
            synchronization.flushOperations(api)
            assertEquals("accepted", database.socialDao().getOperation(dismissal.id)?.state)
            val late = AlarmActivityRequest(UUID.randomUUID().toString(), originalState.alarmRevision, "snoozed",
                trigger.plusSeconds(12 * 60).toString(), originalState.occurrenceId, "timed_out", membershipId,
                trigger.plusSeconds(15 * 60).toString())
            synchronization.saveOperation(group.id, "outcome", late.id, "POST", "/v1/alarms/${alarmIds[0]}/activity",
                json.encodeToJsonElement(late).jsonObject, null, ordered = false, operationId = late.id)
            synchronization.flushOperations(api)
            assertEquals("confirmed", database.socialDao().getOperation(late.id)?.state)
            assertEquals("response_outdated", database.socialDao().getOperation(late.id)?.rejectionCode)
            synchronization.synchronizeScope(api, "/v1/groups/${group.id}/sync")
            val final = json.decodeFromString<SocialOccurrenceDto>(
                database.socialDao().getScopeResources(group.id).single { it.kind == "occurrence" && it.key == original.key }.representation)
            assertEquals("dismissed", final.status)
            assertNull(final.snoozedUntil)
            val history = api.getAlarmActivity(alarmIds[0], null).items.filter { it.entityType == "outcome" }
            assertEquals(2, history.count { it.action == "snoozed" && it.details?.get("reason")?.jsonPrimitive?.content == "timed_out" })
            assertEquals(1, history.count { it.action == "dismissed" })
            assertTrue(history.none { it.action == "ignored" })
        } finally {
            try {
                repository.deleteGroup(group.id)
                repository.synchronize()
            } finally {
                database.close()
                app.deleteDatabase(databaseName)
            }
        }
        Unit
    }

    @Test
    fun finiteSharedTimerAnswersKeepIndependentDeadlinesAndResetGenerations() = runBlocking {
        val server = InstrumentationRegistry.getArguments().getString("jayTestServer")
        assumeTrue("Requires an isolated Django test server", server != null)
        val app = ApplicationProvider.getApplicationContext<App>()
        assertEquals(server, Preferences.instance.getString(SocialPreferences.serverUrlKey, null))
        val repository = app.container.socialRepository
        repository.synchronize()
        val name = "Repeat protocol ${UUID.randomUUID()}"
        repository.createGroup(name, AlarmPermission.EVERYONE, false, false, false, false)
        repository.synchronize()
        val group = repository.groups.first().single { it.name == name }
        repository.saveGroupSettings(group.copy(sharedAnswers = true))
        repository.synchronize()
        val owner = DeviceIdentityStore.loadOrCreate(app, requireNotNull(server))
        val ownerApi = SocialApi(server, owner)
        val invitation = requireNotNull(repository.createInvite(group.id))
        val token = requireNotNull(SocialLink.parse(invitation)?.parameters?.get("token"))
        val peerId = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val api = SocialApi(server, DeviceIdentity(peerId, "Repeat peer", UUID.randomUUID().toString(), ""))
        api.register()
        val databaseName = "repeat-peer-${UUID.randomUUID()}"
        val database = Room.databaseBuilder(app, SocialDatabase::class.java, databaseName).build()
        try {
            val synchronization = SocialSynchronization(app, database)
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            synchronization.saveOperation(peerId, "join", token, "POST", "/v1/groups/join",
                JsonObject(mapOf("token" to JsonPrimitive(token))), null, ordered = false)
            synchronization.flushOperations(api)
            synchronization.synchronizeScope(api, "/v1/sync")
            val membership = database.socialDao().getResources().single { it.kind == "membership" }
            val membershipId = Json.parseToJsonElement(membership.representation).jsonObject.getValue("id").jsonPrimitive.content
            for (timeout in listOf(2, 7)) {
                repository.startSharedTimer(group.id, "Timeout $timeout", TimerSettings(
                    seconds = 600, soundEnabled = false, vibrate = false,
                    timeoutMinutes = timeout, repeatEnabled = true, repeatCount = 2
                ))
            }
            repository.synchronize()
            synchronization.synchronizeScope(api, "/v1/groups/${group.id}/sync")
            val templates = database.socialDao().getScopeResources(group.id).filter { it.kind == "timer" }
                .map { json.decodeFromString<SharedTimerDto>(it.representation) }.sortedBy { it.timeoutMinutes }
            assertEquals(listOf(2, 7), templates.map { it.timeoutMinutes })
            assertTrue(templates.all { it.repeatEnabled && it.repeatCount == 2 && it.repeatIndex == 0 })
            val expiry = Instant.ofEpochMilli(System.currentTimeMillis() - 10_000)
            val series = templates.map { template ->
                SharedTimerRequest(label = template.label, labelColor = template.labelColor, durationSeconds = 1,
                    incrementSeconds = template.incrementSeconds, vibrate = template.vibrate,
                    vibrationPattern = template.vibrationPattern, vibrationPatternName = template.vibrationPatternName,
                    sound = SharedSoundSelection(template.soundMode, template.soundId), expiresAt = expiry.toString(),
                    runId = UUID.randomUUID().toString(), timeoutMinutes = template.timeoutMinutes,
                    repeatEnabled = template.repeatEnabled, repeatCount = template.repeatCount,
                    membershipId = membershipId, id = template.id)
            }
            for (timer in series) {
                val adjustment = synchronization.saveOperation(group.id, "timer", requireNotNull(timer.id), "PUT",
                    "/v1/timers/${timer.id}", JsonObject(json.encodeToJsonElement(timer).jsonObject.filterKeys { it != "id" }), null)
                synchronization.flushOperations(api)
                assertEquals("accepted", database.socialDao().getOperation(adjustment.operationId)?.state)
            }
            for (index in 0..2) {
                for (timer in series) {
                    val answer = TimerOccurrenceRequest(membershipId, timer.runId, index, Instant.now().toString())
                    val operation = synchronization.saveOperation(group.id, "timer_occurrence", UUID.randomUUID().toString(), "POST",
                        "/v1/timers/${timer.id}/occurrence", json.encodeToJsonElement(answer).jsonObject, null, ordered = false)
                    synchronization.flushOperations(api)
                    assertEquals("accepted", database.socialDao().getOperation(operation.operationId)?.state)
                    assertEquals(Json.parseToJsonElement(requireNotNull(database.socialDao().getOperation(operation.operationId)?.response)), Json.parseToJsonElement(api.executeOperation(operation)))
                }
                synchronization.synchronizeScope(api, "/v1/groups/${group.id}/sync")
                val answers = database.socialDao().getScopeResources(group.id).filter { it.kind == "timer_occurrence" }
                    .map { json.decodeFromString<TimerOccurrenceDto>(it.representation) }
                val ownerAnswers = ownerApi.synchronizeScope("/v1/groups/${group.id}/sync", null).items.filter { it.kind == "timer_occurrence" }
                    .map { json.decodeFromJsonElement<TimerOccurrenceDto>(it.data) }
                assertEquals(2 * (index + 1), answers.size)
                assertEquals(answers.size, ownerAnswers.size)
                for (timer in series) {
                    val own = answers.filter { it.timerId == timer.id }
                    val shared = ownerAnswers.filter { it.timerId == timer.id }
                    assertEquals((0..index).toList(), own.map { it.repeatIndex }.sorted())
                    assertEquals((0..index).toList(), shared.map { it.repeatIndex }.sorted())
                    for (answer in own + shared) {
                        assertEquals(timer.runId, answer.runId)
                        assertEquals("dismissed", answer.status)
                        assertEquals(expiry.plusSeconds(answer.repeatIndex.toLong()), Instant.parse(answer.triggerAt))
                        assertEquals(expiry.plusSeconds(answer.repeatIndex + timer.timeoutMinutes * 60L), Instant.parse(answer.deadlineAt))
                    }
                    assertTrue(own.all { it.identityId == peerId && it.membershipId == membershipId })
                    assertTrue(shared.all { it.identityId == owner.id && it.membershipId == group.membershipId })
                }
            }
            for (timer in series) {
                val overflow = TimerOccurrenceRequest(membershipId, timer.runId, 3, Instant.now().toString())
                val operation = synchronization.saveOperation(group.id, "timer_occurrence", UUID.randomUUID().toString(), "POST",
                    "/v1/timers/${timer.id}/occurrence", json.encodeToJsonElement(overflow).jsonObject, null, ordered = false)
                synchronization.flushOperations(api)
                assertEquals("occurrence_invalid", database.socialDao().getOperation(operation.operationId)?.rejectionCode)
            }
            val timer = series.first()
            val reset = timer.copy(runId = UUID.randomUUID().toString(), repeatIndex = 0,
                expiresAt = Instant.ofEpochMilli(System.currentTimeMillis() - 4_000).toString())
            val resetOperation = synchronization.saveOperation(group.id, "timer", requireNotNull(timer.id), "PUT",
                "/v1/timers/${timer.id}", JsonObject(json.encodeToJsonElement(reset).jsonObject.filterKeys { it != "id" }), null)
            synchronization.flushOperations(api)
            assertEquals("accepted", database.socialDao().getOperation(resetOperation.operationId)?.state)
            val oldAnswer = TimerOccurrenceRequest(membershipId, timer.runId, 2, Instant.now().toString())
            val outdated = synchronization.saveOperation(group.id, "timer_occurrence", UUID.randomUUID().toString(), "POST",
                "/v1/timers/${timer.id}/occurrence", json.encodeToJsonElement(oldAnswer).jsonObject, null, ordered = false)
            synchronization.flushOperations(api)
            assertEquals("run_changed", database.socialDao().getOperation(outdated.operationId)?.rejectionCode)
            for (index in 0..2) {
                val answer = TimerOccurrenceRequest(membershipId, reset.runId, index, Instant.now().toString())
                val operation = synchronization.saveOperation(group.id, "timer_occurrence", UUID.randomUUID().toString(), "POST",
                    "/v1/timers/${timer.id}/occurrence", json.encodeToJsonElement(answer).jsonObject, null, ordered = false)
                synchronization.flushOperations(api)
                assertEquals("accepted", database.socialDao().getOperation(operation.operationId)?.state)
            }
            val remaining = series.last().copy(runId = UUID.randomUUID().toString(), repeatIndex = 1,
                expiresAt = Instant.ofEpochMilli(System.currentTimeMillis() - 4_000).toString())
            val continued = synchronization.saveOperation(group.id, "timer", requireNotNull(remaining.id), "PUT",
                "/v1/timers/${remaining.id}", JsonObject(json.encodeToJsonElement(remaining).jsonObject.filterKeys { it != "id" }), null)
            synchronization.flushOperations(api)
            assertEquals("accepted", database.socialDao().getOperation(continued.operationId)?.state)
            for (index in listOf(0, 1, 2, 3)) {
                val answer = TimerOccurrenceRequest(membershipId, remaining.runId, index, Instant.now().toString())
                val operation = synchronization.saveOperation(group.id, "timer_occurrence", UUID.randomUUID().toString(), "POST",
                    "/v1/timers/${remaining.id}/occurrence", json.encodeToJsonElement(answer).jsonObject, null, ordered = false)
                synchronization.flushOperations(api)
                if (index in 1..2) {
                    assertEquals("accepted", database.socialDao().getOperation(operation.operationId)?.state)
                } else {
                    assertEquals("occurrence_invalid", database.socialDao().getOperation(operation.operationId)?.rejectionCode)
                }
            }
            synchronization.synchronizeScope(api, "/v1/groups/${group.id}/sync")
            val resources = database.socialDao().getScopeResources(group.id)
            assertEquals(2, resources.count { it.kind == "timer" })
            val updated = json.decodeFromString<SharedTimerDto>(resources.single { it.kind == "timer" && it.key == timer.id }.representation)
            assertEquals(reset.runId, updated.runId)
            assertEquals(timer.timeoutMinutes, updated.timeoutMinutes)
            assertEquals(2, updated.repeatCount)
            assertEquals(0, updated.repeatIndex)
            val generations = resources.filter { it.kind == "timer_occurrence" }
                .map { json.decodeFromString<TimerOccurrenceDto>(it.representation) }.filter { it.timerId == timer.id }
            assertEquals(6, generations.size)
            assertEquals(setOf(timer.runId, reset.runId), generations.map { it.runId }.toSet())
            val continuedState = json.decodeFromString<SharedTimerDto>(resources.single { it.kind == "timer" && it.key == remaining.id }.representation)
            assertEquals(remaining.runId, continuedState.runId)
            assertEquals(7, continuedState.timeoutMinutes)
            assertEquals(2, continuedState.repeatCount)
            assertEquals(1, continuedState.repeatIndex)
            val remainingAnswers = resources.filter { it.kind == "timer_occurrence" }
                .map { json.decodeFromString<TimerOccurrenceDto>(it.representation) }.filter { it.runId == remaining.runId }
            assertEquals(listOf(1, 2), remainingAnswers.map { it.repeatIndex }.sorted())
        } finally {
            try {
                repository.deleteGroup(group.id)
                repository.synchronize()
            } finally {
                database.close()
                app.deleteDatabase(databaseName)
            }
        }
        Unit
    }

}
