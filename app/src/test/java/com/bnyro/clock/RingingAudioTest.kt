package com.bnyro.clock

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.util.RingingAudio
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [RingingAudioTest.RoutedPlayer::class])
class RingingAudioTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audio: ShadowAudioManager = shadowOf(audioManager)
    private val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
    private val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
    private val players = mutableListOf<ShadowMediaPlayer>()
    private lateinit var ringing: RingingAudio

    @Before
    fun setUp() {
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, sound), ShadowMediaPlayer.MediaInfo(60_000, 0))
        ShadowMediaPlayer.setCreateListener { _, player -> players.add(player) }
        audio.setOutputDevices(listOf(speaker, headset))
        ringing = RingingAudio(context)
    }

    @After
    fun tearDown() {
        ringing.stop()
        RoutedPlayer.output = null
        ShadowMediaPlayer.resetStaticState()
    }

    @Test
    fun delayedFocusStartsTheSamePlayerBeforeTheDeadline() {
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        ringing.start(null, 0, System.currentTimeMillis() + 5_000)
        val request = audio.lastAudioFocusRequest
        assertFalse(players.single().isReallyPlaying)

        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        val player = players.single()
        assertTrue(player.isReallyPlaying)
        assertEquals(1f, player.leftVolume, 0f)
        ringing.stop()
        assertSame(request.audioFocusRequest, audio.lastAbandonedAudioFocusRequest)
        assertEquals(ShadowMediaPlayer.State.END, player.state)
    }

    @Test
    fun focusArrivingAfterTheDeadlineDoesNotStartThePlayer() {
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        ringing.start(null, 0, System.currentTimeMillis() + 1_000)
        val request = audio.lastAudioFocusRequest
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertFalse(players.single().isReallyPlaying)
    }

    @Test
    fun focusFromAStoppedRingCannotStartItsReplacement() {
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        ringing.start(null, 0, System.currentTimeMillis() + 5_000)
        val stopped = audio.lastAudioFocusRequest
        ringing.stop()
        assertSame(stopped.audioFocusRequest, audio.lastAbandonedAudioFocusRequest)
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        ringing.start(null, 0, System.currentTimeMillis() + 5_000)

        stopped.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(ShadowMediaPlayer.State.END, players.first().state)
        assertFalse(players.last().isReallyPlaying)
    }

    @Test
    fun delayedFocusPlaysOnTheConfirmedSpeakerDuringACellularCall() {
        audioManager.mode = AudioManager.MODE_IN_CALL
        RoutedPlayer.output = speaker
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)

        ringing.start(null, 0, System.currentTimeMillis() + 5_000)

        assertTrue(players.single().isReallyPlaying)
        assertEquals(1f, players.single().leftVolume, 0f)
    }

    @Test
    fun headsetRouteStaysMutedEvenWithFocusDuringAnInternetCall() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        RoutedPlayer.output = headset
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        ringing.start(null, 0, System.currentTimeMillis() + 5_000)

        assertTrue(players.single().isReallyPlaying)
        assertEquals(0f, players.single().leftVolume, 0f)
    }

    @Test
    fun reroutingAnAlarmToTheHeadsetCancelsItsVolumeRamp() {
        audioManager.mode = AudioManager.MODE_IN_CALL
        RoutedPlayer.output = speaker
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        ringing.start(null, 3, System.currentTimeMillis() + 5_000)
        val player = players.single() as RoutedPlayer
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(player.leftVolume > 0f)

        RoutedPlayer.output = headset
        player.reportRoutingChange()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals(0f, player.leftVolume, 0f)
    }

    @Implements(MediaPlayer::class)
    class RoutedPlayer : ShadowMediaPlayer() {
        @RealObject
        private lateinit var realPlayer: MediaPlayer
        private lateinit var routingListener: AudioRouting.OnRoutingChangedListener

        @Implementation
        fun getRoutedDevice(): AudioDeviceInfo? = output

        @Implementation
        fun addOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener, handler: Handler?) {
            routingListener = listener
        }

        fun reportRoutingChange() {
            routingListener.onRoutingChanged(realPlayer)
        }

        companion object {
            var output: AudioDeviceInfo? = null
        }
    }
}
