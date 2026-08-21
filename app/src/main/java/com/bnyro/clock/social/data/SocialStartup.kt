package com.bnyro.clock.social.data

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bnyro.clock.BuildConfig
import com.bnyro.clock.social.presentation.SocialNotificationHelper
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit

object SocialStartup {
    fun initialize(application: Application) {
        SocialNotificationHelper.createNotificationChannel(application)
        if (
            BuildConfig.JAY_FIREBASE_APPLICATION_ID.isNotBlank() &&
            BuildConfig.JAY_FIREBASE_PROJECT_ID.isNotBlank() &&
            BuildConfig.JAY_FIREBASE_API_KEY.isNotBlank() &&
            FirebaseApp.getApps(application).isEmpty()
        ) {
            FirebaseApp.initializeApp(
                application,
                FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.JAY_FIREBASE_APPLICATION_ID)
                    .setProjectId(BuildConfig.JAY_FIREBASE_PROJECT_ID)
                    .setApiKey(BuildConfig.JAY_FIREBASE_API_KEY)
                    .build()
            )
            FirebaseMessaging.getInstance().token.addOnSuccessListener {
                PushRegistrationWorker.enqueue(application, it)
            }
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        SocialSyncWorker.enqueue(application)
        WorkManager.getInstance(application).enqueueUniquePeriodicWork(
            "jay_social_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SocialSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )
        if (BuildConfig.JAY_PLAY_ENTITLEMENT_ELIGIBLE) {
            WorkManager.getInstance(application).enqueueUniqueWork(
                "jay_play_entitlement_refresh",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PlayEntitlementWorker>()
                    .setConstraints(constraints)
                    .build()
            )
            WorkManager.getInstance(application).enqueueUniquePeriodicWork(
                "jay_play_entitlement",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PlayEntitlementWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()
            )
        }
    }
}
