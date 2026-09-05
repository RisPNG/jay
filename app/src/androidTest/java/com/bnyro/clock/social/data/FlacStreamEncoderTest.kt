package com.bnyro.clock.social.data

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlacStreamEncoderTest {
    @Test
    fun encodedFileIsCanonicalAndLossless() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val frames = 48_000
        val file = File(context.cacheDir, "flac-encoder-test.flac")
        val samples = ShortArray(frames) { ((sin(it * 0.05) * 10_000).roundToInt()).toShort() }
        try {
            FlacStreamEncoder(file).use { encoder ->
                encoder.write(samples, frames)
                encoder.finish()
            }
            val streamInfo = file.inputStream().use(::readFlacStreamInfo)
            assertEquals(48_000L, streamInfo.totalSamples)
            assertEquals(48_000, streamInfo.sampleRate)
            assertEquals(1, streamInfo.channelCount)
            assertEquals(16, streamInfo.bitsPerSample)
            SharedSoundFileVerifier.verify(file, durationMs = 1_000)
            val decoded = decodePcm(file)
            assertEquals(samples.size, decoded.size)
            assertTrue(samples.contentEquals(decoded))
        } finally {
            file.delete()
        }
        Unit
    }

    private fun decodePcm(file: File): ShortArray {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(0)
            val trackFormat = extractor.getTrackFormat(0)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
            val chunks = mutableListOf<ShortArray>()
            var totalSamples = 0
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            if (mime == MediaFormat.MIMETYPE_AUDIO_FLAC) {
                decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
                decoder.configure(
                    MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_FLAC,
                        trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    ).apply {
                        setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    },
                    null,
                    null,
                    0
                )
                decoder.start()
                while (!outputEnded) {
                    if (!inputEnded) {
                        val index = decoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = decoder.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    index,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10_000)
                    if (index >= 0) {
                        val buffer = decoder.getOutputBuffer(index)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val shorts = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val chunk = ShortArray(shorts.remaining())
                        shorts.get(chunk)
                        chunks += chunk
                        totalSamples += chunk.size
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            } else {
                check(mime == MediaFormat.MIMETYPE_AUDIO_RAW) { "Unexpected FLAC track mime $mime" }
                val sampleBuffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                while (true) {
                    sampleBuffer.clear()
                    val size = extractor.readSampleData(sampleBuffer, 0)
                    if (size < 0) break
                    check(size % 2 == 0) { "The extractor produced a partial PCM frame" }
                    sampleBuffer.position(0)
                    sampleBuffer.limit(size)
                    val chunk = ShortArray(size / 2)
                    sampleBuffer.asShortBuffer().get(chunk)
                    chunks += chunk
                    totalSamples += chunk.size
                    extractor.advance()
                }
            }
            val decoded = ShortArray(totalSamples)
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(decoded, offset)
                offset += chunk.size
            }
            return decoded
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }
}
