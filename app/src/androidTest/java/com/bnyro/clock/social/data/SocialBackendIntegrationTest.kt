package com.bnyro.clock.social.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.App
import com.bnyro.clock.social.domain.AlarmPermission
import com.bnyro.clock.util.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        repository.adjustSharedTimer(timer.key, expiry, operationId, savedAt)
        repository.synchronize()
        repository.adjustSharedTimer(timer.key, expiry, operationId, savedAt)
        repository.synchronize()
        val updated = dao.getScopeResources(group.id).single { it.kind == "timer" }
        assertEquals(expiry, OffsetDateTime.parse(Json.parseToJsonElement(updated.representation).jsonObject.getValue("expires_at").jsonPrimitive.content).toInstant().toEpochMilli())
        repository.cancelSharedTimer(timer.key)
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
            repository.cancelSharedTimer(timer.key)
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

}
