package com.bnyro.clock.social.data

import android.app.Application
import android.content.Context
import android.os.Build
import org.robolectric.Shadows.shadowOf
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertNotEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class PlayInstallationStoreTest {
    @Test
    fun purchaseCredentialBelongsOnlyToThePaidPlayInstallation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = "https://jay.example"
        val serverKey = MessageDigest.getInstance("SHA-256").digest(server.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(context.noBackupFilesDir, "jay-play-installation-$serverKey")
        file.delete()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            shadowOf(context.packageManager).setInstallSourceInfo(context.packageName, "com.android.vending", "com.android.vending")
        } else {
            context.packageManager.setInstallerPackageName(context.packageName, "com.android.vending")
        }
        val credential = PlayInstallationStore.credential(context, server)
        if (BuildConfig.JAY_PLAY_ENTITLEMENT_ELIGIBLE && !BuildConfig.DEBUG) {
            assertNotNull(credential)
            assertEquals(credential, file.readText())
            assertNotEquals(credential, PlayInstallationStore.credential(context, "https://other.example"))
            context.getSharedPreferences("jay_identity", Context.MODE_PRIVATE).edit().clear().commit()
            assertEquals(credential, PlayInstallationStore.credential(context, server))
        } else {
            assertNull(credential)
            assertFalse(file.exists())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            shadowOf(context.packageManager).setInstallSourceInfo(context.packageName, null, null)
        } else {
            context.packageManager.setInstallerPackageName(context.packageName, null)
        }
        assertNull(PlayInstallationStore.credential(context, server))
        file.delete()
    }
}
