package com.bnyro.clock.social.data

import com.bnyro.clock.App
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.WorkForegroundUpdater
import androidx.work.impl.utils.WorkProgressUpdater
import com.bnyro.clock.domain.model.TimerObject
import com.bnyro.clock.social.domain.AlarmPermission
import com.bnyro.clock.social.domain.DeviceCapabilities
import com.bnyro.clock.social.domain.MemberRole
import com.bnyro.clock.social.domain.SocialGroup
import com.bnyro.clock.util.Preferences
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class SharedTimerWorkerQueueTest {
    @Test
    fun cancellationIntentSurvivesNativeWorkQueueingAndCapabilityChanges() = runBlocking {
        data class Cancellation(val name: String, val capableAtEnqueue: Boolean?, val capableAtRun: Boolean, val expectedRun: String?)
        val cases = listOf(
            Cancellation("preexisting work with a run ID", null, true, null),
            Cancellation("old API work after the server upgrades", false, true, null),
            Cancellation("explicit intent after capabilities change", true, false, "captured-run")
        )
        val context = ApplicationProvider.getApplicationContext<App>()
        shadowOf(context.packageManager).setInstallSourceInfo(context.packageName, null, null)
        Preferences.init(context)
        Preferences.edit { clear() }
        val server = SocialRepository.DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, server)
        Preferences.edit {
            putString(SocialPreferences.capabilitiesServerKey, server)
            putString(SocialPreferences.capabilitiesDeviceKey, identity.id)
            putString(SocialPreferences.capabilitiesInstallationKey, "")
        }
        val social = SocialDatabase.getDatabase(context)
        val manager = WorkManagerImpl.getInstance(context)
        val names = mutableListOf<String>()
        try {
            for (case in cases) {
                withContext(Dispatchers.IO) { social.clearAllTables() }
                social.socialDao().putGroups(listOf(SocialGroup(
                    "group", "Kitchen", AlarmPermission.EVERYONE, true, true, true, true, true, true,
                    MemberRole.MEMBER, membershipId = "member"
                )))
                Preferences.edit {
                    putString(SocialPreferences.capabilitiesKey, Json.encodeToString(DeviceCapabilities(advancedGroupTiming = case.capableAtEnqueue == true)))
                }
                val timerId = UUID.randomUUID().toString()
                val input = if (case.capableAtEnqueue == null) {
                    Data.Builder().putString("timer_id", timerId).putString("action", SocialTimerWorker.ACTION_CANCEL)
                        .putString("group_id", "group").putString("membership_id", "member")
                        .putString("run_id", "captured-run").putString("operation_id", UUID.randomUUID().toString())
                        .putString("server", server).putString("identity", identity.id).build()
                } else {
                    val timer = TimerObject(sharedTimerId = timerId, sharedGroupId = "group", sharedMembershipId = "member",
                        sharedRunId = "captured-run", expiresAt = System.currentTimeMillis() + 60_000L)
                    val name = "timer:$server:${identity.id}:member:$timerId"
                    names += name
                    SocialTimerWorker.enqueue(context, timer, SocialTimerWorker.ACTION_CANCEL)
                    val work = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).single()
                    withContext(Dispatchers.IO) { requireNotNull(manager.workDatabase.workSpecDao().getWorkSpec(work.id.toString())).input }
                }
                assertEquals(case.name, case.expectedRun, input.getString("cancel_run_id"))
                Preferences.edit {
                    putString(SocialPreferences.capabilitiesKey, Json.encodeToString(DeviceCapabilities(advancedGroupTiming = case.capableAtRun)))
                }
                val configuration = manager.configuration
                val executor = manager.workTaskExecutor
                val parameters = WorkerParameters(
                    UUID.randomUUID(), input, emptyList(), WorkerParameters.RuntimeExtras(), 0, 0,
                    configuration.executor, configuration.workerCoroutineContext, executor, configuration.workerFactory,
                    WorkProgressUpdater(manager.workDatabase, executor), WorkForegroundUpdater(manager.workDatabase, manager.processor, executor)
                )

                assertEquals(case.name, ListenableWorker.Result.success(), SocialTimerWorker(context, parameters).doWork())

                val operation = requireNotNull(social.socialDao().getOperation(requireNotNull(input.getString("operation_id"))))
                val payload = Json.parseToJsonElement(requireNotNull(operation.payload)).jsonObject
                assertEquals(case.name, case.expectedRun, payload["run_id"]?.jsonPrimitive?.content)
                assertEquals(case.name, if (case.expectedRun == null) setOf("membership_id") else setOf("membership_id", "run_id"), payload.keys)
            }
        } finally {
            names.forEach { manager.cancelUniqueWork(it).result.get(5, TimeUnit.SECONDS) }
            manager.cancelUniqueWork("jay_social_sync_immediate").result.get(5, TimeUnit.SECONDS)
            withContext(Dispatchers.IO) { social.clearAllTables() }
            Preferences.edit { clear() }
        }
    }

    @Test
    fun pressesBeforeWorkerPersistenceFormOneNativeSeriesChain() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Preferences.init(context)
        Preferences.instance.edit().clear().commit()
        val timer = TimerObject(sharedTimerId = UUID.randomUUID().toString(), sharedGroupId = "group",
            sharedMembershipId = "member", sharedRunId = "run", repeatIndex = 2,
            expiresAt = System.currentTimeMillis() + 60_000)
        val server = SocialRepository.DEFAULT_SERVER_URL
        val identityId = DeviceIdentityStore.loadOrCreate(context, server).id
        val name = "timer:$server:$identityId:member:${timer.sharedTimerId}"
        val manager = WorkManager.getInstance(context)
        try {
            SocialTimerWorker.enqueue(context, timer, SocialTimerWorker.ACTION_ADJUST, timer.expiresAt + 15_000, 2, adjustmentSeconds = 15)
            SocialTimerWorker.enqueue(context, timer, SocialTimerWorker.ACTION_ADJUST, timer.expiresAt + 15_000, 2, adjustmentSeconds = 15)
            SocialTimerWorker.enqueue(context, timer, SocialTimerWorker.ACTION_ADJUST, System.currentTimeMillis() + 60_000, 0)
            val work = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)
            assertEquals(3, work.size)
            assertEquals(2, work.count { it.state == WorkInfo.State.BLOCKED })
        } finally {
            manager.cancelUniqueWork(name).result.get(5, TimeUnit.SECONDS)
            Preferences.instance.edit().clear().commit()
        }
    }
}
