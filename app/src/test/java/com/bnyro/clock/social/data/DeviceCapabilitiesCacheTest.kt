package com.bnyro.clock.social.data

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.social.domain.DeviceCapabilities
import com.bnyro.clock.util.Preferences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeviceCapabilitiesCacheTest {
    @Test
    fun grantsCannotFollowServerOrIdentityChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Preferences.init(context)
        val server = "https://self-hosted.example"
        Preferences.edit { putString(SocialPreferences.serverUrlKey, server) }
        val identity = DeviceIdentityStore.loadOrCreate(context, server)
        val social = Room.inMemoryDatabaseBuilder(context, SocialDatabase::class.java).build()
        val alarms = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = SocialRepository(context, social, AlarmRepository(alarms.alarmsDao()))
            Preferences.edit {
                putString(SocialPreferences.capabilitiesServerKey, server)
                putString(SocialPreferences.capabilitiesDeviceKey, identity.id)
                putString(SocialPreferences.capabilitiesKey, Json.encodeToString(DeviceCapabilities(true, false)))
            }
            assertTrue(repository.canUploadSharedSounds)
            context.getSharedPreferences("jay_identity", Context.MODE_PRIVATE).edit { clear() }
            assertFalse(repository.canUploadSharedSounds)
            val replacement = DeviceIdentityStore.loadOrCreate(context, server)
            Preferences.edit { putString(SocialPreferences.capabilitiesDeviceKey, replacement.id) }
            assertTrue(repository.canUploadSharedSounds)
            repository.changeServer("https://restricted.example")
            assertFalse(repository.canUploadSharedSounds)
            repository.changeServer(server)
            assertFalse(repository.canUploadSharedSounds)
        } finally {
            social.close()
            alarms.close()
            Preferences.edit { clear() }
        }
    }
}
