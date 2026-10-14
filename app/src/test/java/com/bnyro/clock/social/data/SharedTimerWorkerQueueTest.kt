package com.bnyro.clock.social.data

import com.bnyro.clock.App
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bnyro.clock.domain.model.TimerObject
import com.bnyro.clock.util.Preferences
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class SharedTimerWorkerQueueTest {
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
