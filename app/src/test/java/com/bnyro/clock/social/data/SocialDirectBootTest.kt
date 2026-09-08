package com.bnyro.clock.social.data

import android.app.Application
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SocialDirectBootTest {
    @Test
    fun lockedStartupAndAnswersDoNotOpenCredentialStorage() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        Preferences.init(application.createDeviceProtectedStorageContext())
        Preferences.instance.edit().clear().commit()
        shadowOf(application.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        SocialStartup.initialize(application)
        SocialAlarmEvents.dismiss(application, 7L, "occurrence")
        SocialAlarmEvents.snooze(application, 8L, 5, "next-occurrence")
        assertEquals(2, Preferences.instance.all.keys.count { it.startsWith("jayPendingAlarmActivity:") })
        assertFalse(application.getDatabasePath("androidx.work.workdb").exists())
        assertFalse(application.getDatabasePath("jay_social").exists())
    }
}
