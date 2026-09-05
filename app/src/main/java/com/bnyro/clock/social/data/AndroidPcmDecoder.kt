package com.bnyro.clock.social.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class DecodedPcmBlock(
    val sampleRate: Int,
    val channelCount: Int,
    val samples: ShortArray,
    val frames: Int
)

class AndroidPcmDecoder(private val context: Context, private val source: Uri) {
    val durationUs: Long = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, source, null)
            val trackFormat = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (trackFormat?.containsKey(MediaFormat.KEY_DURATION) == true) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else -1L
        } finally {
            extractor.release()
        }
    }.getOrDefault(-1L)

    suspend fun decode(limitUs: Long, consume: suspend (DecodedPcmBlock) -> Boolean) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, source, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("The selected file has no audio track")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("The selected audio format is unknown")
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            extractor.selectTrack(trackIndex)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            var inputEnded = false
            var outputEnded = false
            var latched = false
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var stalls = 0
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0 || sampleTime >= limitUs) {
                            decoder.queueInputBuffer(
                                index,
                                0,
                                0,
                                maxOf(sampleTime, 0),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            val size = extractor.readSampleData(buffer, 0)
                            decoder.queueInputBuffer(index, 0, size, sampleTime, 0)
                            extractor.advance()
                        }
                        stalls = 0
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = decoder.outputFormat
                    val formatSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val formatChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    check(
                        !format.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                            AudioFormat.ENCODING_PCM_16BIT
                    ) { "The device decoder did not produce 16-bit PCM" }
                    if (latched) {
                        check(formatSampleRate == sampleRate && formatChannelCount == channelCount) {
                            "The audio format changed during decoding"
                        }
                    } else {
                        sampleRate = formatSampleRate
                        channelCount = formatChannelCount
                        latched = true
                    }
                    stalls = 0
                } else if (index >= 0) {
                    if (!latched) {
                        val format = decoder.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        check(
                            !format.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                                format.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                AudioFormat.ENCODING_PCM_16BIT
                        ) { "The device decoder did not produce 16-bit PCM" }
                        latched = true
                    }
                    val buffer = decoder.getOutputBuffer(index)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val shorts = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = shorts.remaining() / channelCount
                    if (frames > 0) {
                        val samples = ShortArray(frames * channelCount)
                        shorts.get(samples)
                        if (!consume(DecodedPcmBlock(sampleRate, channelCount, samples, frames))) {
                            decoder.releaseOutputBuffer(index, false)
                            return
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(index, false)
                    stalls = 0
                } else if (++stalls > MAX_CODEC_STALLS) {
                    error("The device audio decoder stalled")
                }
            }
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_CODEC_STALLS = 1_000
    }
}
