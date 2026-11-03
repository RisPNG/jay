package com.bnyro.clock.social.data

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.util.RingingCoordinator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GroupRingPlaybackTest {
    private lateinit var playback: GroupRingPlayback

    @Before
    fun createPlayback() {
        playback = GroupRingPlayback(ApplicationProvider.getApplicationContext())
    }

    @After
    fun stopPlayback() {
        playback.receive(null)
    }

    @Test
    fun disconnectedHolderCannotLeaveTheGroupRinging() {
        playback.receive(Json.parseToJsonElement("""{"session_id":"hold","active":true,"lease_seconds":5}""").jsonObject)
        assertSame(playback, RingingCoordinator.selected)
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertNull(RingingCoordinator.selected)
    }

    @Test
    fun releasingOneHolderKeepsTheOtherHolderRinging() {
        playback.receive(Json.parseToJsonElement("""{"session_id":"first","active":true,"lease_seconds":5}""").jsonObject)
        playback.receive(Json.parseToJsonElement("""{"session_id":"second","active":true,"lease_seconds":5}""").jsonObject)
        playback.receive(Json.parseToJsonElement("""{"session_id":"first","active":false,"lease_seconds":5}""").jsonObject)
        assertSame(playback, RingingCoordinator.selected)
        playback.receive(Json.parseToJsonElement("""{"session_id":"second","active":false,"lease_seconds":5}""").jsonObject)
        assertNull(RingingCoordinator.selected)
    }

    @Test
    fun streamResetStopsAllHeldRings() {
        playback.receive(Json.parseToJsonElement("""{"session_id":"hold","active":true,"lease_seconds":5}""").jsonObject)
        playback.receive(null)
        assertNull(RingingCoordinator.selected)
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertNull(RingingCoordinator.selected)
    }

    @Test
    fun renewalExtendsTheHeldRingWithoutRestartingIt() {
        val ring = Json.parseToJsonElement("""{"session_id":"hold","active":true,"lease_seconds":5}""").jsonObject
        playback.receive(ring)
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
        playback.receive(ring)
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
        assertSame(playback, RingingCoordinator.selected)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertNull(RingingCoordinator.selected)
    }
}
