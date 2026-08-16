package com.bnyro.clock

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.social.data.SocialSyncWorker
import com.bnyro.clock.social.data.PlayEntitlementWorker
import com.bnyro.clock.social.data.PushRegistrationWorker
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.bnyro.clock.util.NotificationHelper
import com.bnyro.clock.util.Preferences
import java.util.concurrent.TimeUnit

class App : Application(), Configuration.Provider {
    lateinit var container: AppContainer

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    //should work for android 6 OR all higher
    private val safeContext: Context by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext()
        } else {
            this
        }
    }
    private val database by lazy {
        AppDatabase.getDatabase(safeContext)
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            migrateToDeviceProtectedStorage()
        }

        Preferences.init(safeContext)

        NotificationHelper.createStaticNotificationChannels(this)

        container = AppContainer(this, database)
        if (
            BuildConfig.JAY_FIREBASE_APPLICATION_ID.isNotBlank() &&
            BuildConfig.JAY_FIREBASE_PROJECT_ID.isNotBlank() &&
            BuildConfig.JAY_FIREBASE_API_KEY.isNotBlank() &&
            FirebaseApp.getApps(this).isEmpty()
        ) {
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.JAY_FIREBASE_APPLICATION_ID)
                    .setProjectId(BuildConfig.JAY_FIREBASE_PROJECT_ID)
                    .setApiKey(BuildConfig.JAY_FIREBASE_API_KEY)
                    .build()
            )
            FirebaseMessaging.getInstance().token.addOnSuccessListener {
                PushRegistrationWorker.enqueue(this, it)
            }
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        SocialSyncWorker.enqueue(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "jay_social_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SocialSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )
        if (BuildConfig.JAY_PLAY_ENTITLEMENT_ELIGIBLE) {
            WorkManager.getInstance(this).enqueueUniqueWork(
                "jay_play_entitlement_refresh",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PlayEntitlementWorker>()
                    .setConstraints(constraints)
                    .build()
            )
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "jay_play_entitlement",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PlayEntitlementWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()
            )
        }
    }
    private fun migrateToDeviceProtectedStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dbName = "app_database"
            val prefName = "${packageName}_preferences"
            if (!safeContext.getDatabasePath(dbName).exists()) {
                safeContext.moveDatabaseFrom(this, dbName)
            }
            if (!safeContext.getSharedPreferences(prefName, MODE_PRIVATE).all.isNotEmpty()) {
                safeContext.moveSharedPreferencesFrom(this, prefName)
            }
        }
    }
}
