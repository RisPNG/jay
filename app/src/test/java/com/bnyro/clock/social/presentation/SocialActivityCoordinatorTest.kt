package com.bnyro.clock.social.presentation

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import org.robolectric.Shadows.shadowOf
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

    class LiteActivity : ComponentActivity() {
        override fun getPackageName() = "com.rispng.jay.lite"
    }

    @Test
    fun liteForwardsBothLinkKindsToInstalledFullWithoutQueuingLocally() {
        val activity = Robolectric.buildActivity(LiteActivity::class.java).get()
        val coordinator = SocialActivityCoordinator(activity)
        for (value in listOf(
            "${SocialLink.BASE_URL}/join?token=invitation",
            "${SocialLink.BASE_URL}/profile#name=Quiet&key=profile-secret"
        )) {
            val link = Uri.parse(value)
            val target = Intent(Intent.ACTION_VIEW, link).setClassName(
                "com.rispng.jay", "com.bnyro.clock.ui.MainActivity"
            )
            shadowOf(activity.packageManager).addResolveInfoForIntent(target, ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.rispng.jay"
                    name = "com.bnyro.clock.ui.MainActivity"
                }
            })
            assertTrue(coordinator.receiveLink(Intent(Intent.ACTION_VIEW, link)))
            val forwarded = shadowOf(activity).nextStartedActivity
            assertEquals("com.rispng.jay", forwarded.component?.packageName)
            assertEquals(value, forwarded.getStringExtra(SocialLink.EXTRA_LINK))
            assertTrue(Preferences.instance.all.isEmpty())
            shadowOf(activity.packageManager).removeResolveInfosForIntent(target, "com.rispng.jay")
        }
    }

    @Test
    fun browserProfilePayloadIsPreservedAndLiteWorksWithoutFull() {
        val activity = Robolectric.buildActivity(LiteActivity::class.java).get()
        val value = "${SocialLink.BASE_URL}/profile#name=Quiet&key=profile-secret"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${SocialLink.BASE_URL}/profile"))
            .putExtra(SocialLink.EXTRA_LINK, value)
        assertTrue(SocialActivityCoordinator(activity).receiveLink(intent))
        assertEquals(value, Preferences.instance.getString(SocialPreferences.pendingProfileKey, null))
        assertNull(intent.getStringExtra(SocialLink.EXTRA_LINK))
        assertNull(shadowOf(activity).nextStartedActivity)
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
