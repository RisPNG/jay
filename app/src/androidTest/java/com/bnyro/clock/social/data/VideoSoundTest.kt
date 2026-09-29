package com.bnyro.clock.social.data

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
}
