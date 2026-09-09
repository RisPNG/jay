package com.bnyro.clock.social.data

import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bnyro.clock.util.NotificationHelper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedSoundStoreTest {
    @Test
    fun sharedSoundIsDecodedLosslesslyCachedAndPlayable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val soundId = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "$soundId.flac")
        val directory = File(context.filesDir, "shared-sounds").apply { mkdirs() }
        val playback = File(directory, "$soundId.wav")
        val samples = ShortArray(96_000) { (sin(it * 0.05) * 3_000).roundToInt().toShort() }
        val store = SharedSoundStore(context)
        val api = SocialApi("http://127.0.0.1:1", DeviceIdentity("test", "test", "test", "test"))
        try {
            FlacStreamEncoder(source).use {
                it.write(samples, samples.size)
                it.finish()
            }
            store.keep(soundId, source)
            assertEquals(playback, store.cached(soundId))
            val prepared = playback.readBytes()
            assertEquals(44 + samples.size * 2, prepared.size)
            val decoded = ShortArray(samples.size)
            ByteBuffer.wrap(prepared, 44, prepared.size - 44)
                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(decoded)
            assertTrue(samples.contentEquals(decoded))
            assertNotNull(store.cache(soundId, api))
            assertTrue(prepared.contentEquals(playback.readBytes()))
            assertEquals(playback, store.cache(soundId, api))
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
                player.setDataSource(context, playback.toUri())
                player.setAudioAttributes(NotificationHelper.audioAttributes)
                player.prepare()
                assertEquals(2_000, player.duration)
                val startedAt = SystemClock.elapsedRealtime()
                player.start()
                assertTrue("The prepared sound did not finish", completed.await(8, TimeUnit.SECONDS))
                assertEquals(null, playbackError)
                assertTrue("Playback ended before delivering audio", SystemClock.elapsedRealtime() - startedAt >= 1_500)
            } finally {
                player.release()
            }
        } finally {
            source.delete()
            playback.delete()
        }
        Unit
    }
}
