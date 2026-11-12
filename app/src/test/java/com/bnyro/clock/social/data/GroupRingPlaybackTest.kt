package com.bnyro.clock.social.data

import android.app.Application
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.util.RingingCoordinator
import com.bnyro.clock.util.RingingSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GroupRingPlaybackTest {
    private lateinit var playback: GroupRingPlayback
    private lateinit var dataSource: DataSource
    private lateinit var mediaInfo: ShadowMediaPlayer.MediaInfo
    private val players = mutableListOf<ShadowMediaPlayer>()
    private val pendingAlarm = object : RingingSession {
        override val soundEnabled = false
        override val vibrate = true
        override val timeoutMillis = 60_000L
        override val isAlarm = true
        var selections = 0

        override fun onSelected() {
            selections++
        }

        override fun onSuppressed() = Unit
    }

    @Before
    fun createPlayback() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        dataSource = DataSource.toDataSource(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        mediaInfo = ShadowMediaPlayer.MediaInfo(60_000, 0)
        ShadowMediaPlayer.addMediaInfo(dataSource, mediaInfo)
        ShadowMediaPlayer.setCreateListener { _, shadow -> players.add(shadow) }
        playback = GroupRingPlayback(context)
    }

    @After
    fun stopPlayback() {
        playback.receive(null)
        RingingCoordinator.stop(pendingAlarm)
        ShadowMediaPlayer.resetStaticState()
    }

    @Test
    fun disconnectedHolderCannotLeaveTheGroupRinging() {
        playback.receive(Json.parseToJsonElement("""{"session_id":"hold","active":true,"lease_seconds":5}""").jsonObject)
        assertSame(playback, RingingCoordinator.selected)
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertNull(RingingCoordinator.selected)
        assertEquals(ShadowMediaPlayer.State.END, players.single().state)
    }

    @Test
    fun releasingOneHolderKeepsTheOtherHolderRinging() {
        playback.receive(Json.parseToJsonElement("""{"session_id":"first","active":true,"lease_seconds":5}""").jsonObject)
        playback.receive(Json.parseToJsonElement("""{"session_id":"second","active":true,"lease_seconds":5}""").jsonObject)
        playback.receive(Json.parseToJsonElement("""{"session_id":"first","active":false,"lease_seconds":5}""").jsonObject)
        assertSame(playback, RingingCoordinator.selected)
        playback.receive(Json.parseToJsonElement("""{"session_id":"second","active":false,"lease_seconds":5}""").jsonObject)
        assertNull(RingingCoordinator.selected)
        assertEquals(ShadowMediaPlayer.State.END, players.single().state)
    }

    @Test
    fun streamResetStopsAllHeldRings() {
        playback.receive(Json.parseToJsonElement("""{"session_id":"hold","active":true,"lease_seconds":5}""").jsonObject)
        playback.receive(null)
        assertNull(RingingCoordinator.selected)
        assertEquals(ShadowMediaPlayer.State.END, players.single().state)
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertNull(RingingCoordinator.selected)
    }

    @Test
    fun renewalExtendsTheHeldRingWithoutRestartingIt() {
        val ring = Json.parseToJsonElement("""{"session_id":"hold","active":true,"lease_seconds":5}""").jsonObject
        playback.receive(ring)
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
        assertTrue(players.single().isReallyPlaying)
        playback.receive(ring)
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
        assertSame(playback, RingingCoordinator.selected)
        assertTrue(players.single().isReallyPlaying)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertNull(RingingCoordinator.selected)
        assertEquals(ShadowMediaPlayer.State.END, players.single().state)
    }

    @Test
    fun delayedFramesOnlyRingForTheirRemainingServerLease() {
        val serverTime = Instant.parse("2026-11-12T12:00:00Z")
        val event = Json.parseToJsonElement("""{"session_id":"delayed","active":true,"lease_seconds":5,"server_time":"$serverTime","expires_at":"${serverTime.plusSeconds(2)}"}""").jsonObject
        playback.receive(event)

        assertSame(playback, RingingCoordinator.selected)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)

        assertNull(RingingCoordinator.selected)
        assertEquals(ShadowMediaPlayer.State.END, players.single().state)
    }

    @Test
    fun anExpiredFrameCannotStartPlayback() {
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        playback.receive(Json.parseToJsonElement("""{"session_id":"expired","active":true,"lease_seconds":5,"expires_at":"${now.minusSeconds(1)}"}""").jsonObject)

        assertNull(RingingCoordinator.selected)
        assertTrue(players.isEmpty())
    }

    @Test
    fun leaseChangesReportTheRemainingCallersUntilTheirLastExpiry() {
        val updates = mutableListOf<List<String>>()
        playback = GroupRingPlayback(ApplicationProvider.getApplicationContext<Application>()) { events ->
            updates.add(events.map { requireNotNull(it["session_id"]).jsonPrimitive.content })
        }
        playback.receive(Json.parseToJsonElement("""{"session_id":"first","active":true,"lease_seconds":5}""").jsonObject)
        playback.receive(Json.parseToJsonElement("""{"session_id":"second","active":true,"lease_seconds":5}""").jsonObject)
        playback.receive(Json.parseToJsonElement("""{"session_id":"first","active":false,"lease_seconds":5}""").jsonObject)
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)

        assertEquals(listOf(listOf("first"), listOf("first", "second"), listOf("second"), emptyList()), updates)
    }

    @Test
    fun unavailableRingtoneReleasesThePlayerAndResumesThePendingAlarm() {
        RingingCoordinator.start(pendingAlarm)
        ShadowMediaPlayer.addException(dataSource, IOException("The alarm ringtone is unavailable"))

        playback.receive(Json.parseToJsonElement("""{"session_id":"hold","active":true,"lease_seconds":5}""").jsonObject)

        assertEquals(ShadowMediaPlayer.State.END, players.single().state)
        assertSame(pendingAlarm, RingingCoordinator.selected)
        assertEquals(2, pendingAlarm.selections)
    }

    @Test
    fun playbackErrorResumesThePendingAlarmAndTheNextRenewalRetries() {
        RingingCoordinator.start(pendingAlarm)
        val error = mediaInfo.scheduleErrorAtOffset(100, MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO)
        val ring = Json.parseToJsonElement("""{"session_id":"hold","active":true,"lease_seconds":5}""").jsonObject

        playback.receive(ring)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(playback, RingingCoordinator.selected)
        assertTrue(players.single().isReallyPlaying)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)

        assertEquals(ShadowMediaPlayer.State.END, players.single().state)
        assertSame(pendingAlarm, RingingCoordinator.selected)
        assertEquals(2, pendingAlarm.selections)

        mediaInfo.removeEvent(error)
        playback.receive(ring)
        shadowOf(Looper.getMainLooper()).idle()

        assertSame(playback, RingingCoordinator.selected)
        assertEquals(2, players.size)
        assertTrue(players.last().isReallyPlaying)
    }
}
