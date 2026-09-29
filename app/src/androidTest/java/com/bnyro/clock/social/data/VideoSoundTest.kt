package com.bnyro.clock.social.data

import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bnyro.clock.util.NotificationHelper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoSoundTest {
    private lateinit var context: Context
    private lateinit var source: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        source = File.createTempFile("video-sound-", ".mp4", context.cacheDir)
        InstrumentationRegistry.getInstrumentation().context.assets.open("video-with-audio.mp4").use { input ->
            source.outputStream().use(input::copyTo)
        }
    }

    @After
    fun tearDown() {
        source.delete()
    }

    @Test
    fun videoAudioTrackConvertsForSharedSound() = runBlocking {
        val processed = SharedSoundProcessor(context).process(source.toUri()) {}
        try {
            val streamInfo = processed.file.inputStream().use(::readFlacStreamInfo)
            assertEquals(48_000, streamInfo.sampleRate)
            assertTrue("The video audio track did not produce samples", streamInfo.totalSamples > 0)
            assertTrue("The video audio track did not produce a sound", processed.durationMs > 0)
        } finally {
            processed.file.delete()
        }
        Unit
    }

    @Test
    fun videoAudioTrackPlaysAsPersonalSound() {
        val completed = CountDownLatch(1)
        var playbackError: String? = null
        val player = MediaPlayer()
        try {
            player.setOnCompletionListener { completed.countDown() }
            player.setOnErrorListener { _, what, extra ->
                playbackError = "MediaPlayer error $what/$extra"
                completed.countDown()
                true
            }
            player.setDataSource(context, source.toUri())
            player.setAudioAttributes(NotificationHelper.audioAttributes)
            player.prepare()
            assertTrue("The selected file has no video track", player.trackInfo.any {
                it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO
            })
            assertTrue("The video has no playable audio track", player.trackInfo.any {
                it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO
            })
            val startedAt = SystemClock.elapsedRealtime()
            player.start()
            assertTrue("The video sound did not finish", completed.await(8, TimeUnit.SECONDS))
            assertEquals(null, playbackError)
            assertTrue("Playback ended before delivering audio", SystemClock.elapsedRealtime() - startedAt >= 500)
        } finally {
            player.release()
        }
    }
}
