package com.bnyro.clock.social.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SocialSyncSchedulingTest {
    @Test
    fun periodicSyncDoesNotSuppressAnImmediateSync() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWork().result.get()
        val periodic = PeriodicWorkRequestBuilder<SocialSyncWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        try {
            manager.enqueueUniquePeriodicWork("jay_social_sync", ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, periodic).result.get()
            val existingIds = manager.getWorkInfosByTag(SocialSyncWorker::class.java.name).get().map { it.id }.toSet()
            SocialSyncWorker.enqueue(context, expedited = true)
            withTimeout(5_000) {
                while (manager.getWorkInfosByTag(SocialSyncWorker::class.java.name).get().none { it.id !in existingIds }) {
                    delay(50)
                }
            }
            assertEquals(periodic.id, manager.getWorkInfosForUniqueWork("jay_social_sync").get().single { !it.state.isFinished }.id)
        } finally {
            manager.cancelAllWork().result.get()
        }
    }

    @Test
    fun startupRegistersPeriodicSyncOverAnExistingOneTimeJob() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val manager = WorkManager.getInstance(application)
        manager.cancelAllWork().result.get()
        val previous = OneTimeWorkRequestBuilder<SocialSyncWorker>()
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()
        try {
            manager.enqueueUniqueWork("jay_social_sync", ExistingWorkPolicy.REPLACE, previous).result.get()
            SocialStartup.initialize(application)
            withTimeout(5_000) {
                while (manager.getWorkInfosForUniqueWork("jay_social_sync").get().none {
                        !it.state.isFinished && it.periodicityInfo != null
                    }) delay(50)
            }
            val periodic = manager.getWorkInfosForUniqueWork("jay_social_sync").get().single { !it.state.isFinished }
            assertNotNull(periodic.periodicityInfo)
        } finally {
            manager.cancelAllWork().result.get()
        }
    }

}
