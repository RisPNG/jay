package com.bnyro.clock.social.presentation

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.social.data.SocialLink
import com.bnyro.clock.social.data.SocialPreferences
import com.bnyro.clock.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SocialActivityCoordinatorTest {
    @Before
    fun setUp() {
        Preferences.init(ApplicationProvider.getApplicationContext())
        Preferences.edit { clear() }
    }

    @Test
    fun incomingLinksAreQueuedOnceForTheirExistingFlows() {
        val coordinator = SocialActivityCoordinator(
            Robolectric.buildActivity(ComponentActivity::class.java).get()
        )
        for ((value, key) in listOf(
            "${SocialLink.BASE_URL}/join?token=example" to SocialPreferences.pendingInvitationKey,
            "${SocialLink.BASE_URL}/profile#name=Quiet&key=example" to SocialPreferences.pendingProfileKey
        )) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(value))
            assertTrue(coordinator.receiveLink(intent))
            assertEquals(value, Preferences.instance.getString(key, null))
            assertNull(intent.data)
            assertFalse(coordinator.receiveLink(intent))
        }
    }

    @Test
    fun unrelatedIntentsDoNotQueueIdentityChanges() {
        val coordinator = SocialActivityCoordinator(
            Robolectric.buildActivity(ComponentActivity::class.java).get()
        )
        assertFalse(coordinator.receiveLink(Intent(Intent.ACTION_SEND, Uri.parse("${SocialLink.BASE_URL}/profile"))))
        assertFalse(coordinator.receiveLink(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/join"))))
        assertFalse(coordinator.receiveLink(null))
        assertTrue(Preferences.instance.all.isEmpty())
    }
}
