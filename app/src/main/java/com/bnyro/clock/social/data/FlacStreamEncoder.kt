package com.bnyro.clock.social.data

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import com.bnyro.clock.social.domain.SHARED_SOUND_SAMPLE_RATE
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class FlacStreamEncoder(private val outputFile: File) : Closeable {
    private val codec: MediaCodec
    private val output: FileOutputStream
    private var closed = false
    private var inputEnded = false
    private var outputEnded = false
    private var stalls = 0
    private var queuedFrames = 0L

    init {
        check(isSupported()) { "This device cannot encode FLAC audio" }
        output = FileOutputStream(outputFile)
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        codec.configure(
            MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_FLAC,
                SHARED_SOUND_SAMPLE_RATE,
                1
            ).apply {
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, COMPRESSION_LEVEL)
            },
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )
        codec.start()
    }

    suspend fun write(samples: ShortArray, frames: Int) {
        var offset = 0
        while (offset < frames) {
            currentCoroutineContext().ensureActive()
            var progressed = false
            val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)!!
                val capacityFrames = buffer.remaining() / 2
                if (capacityFrames > 0) {
                    val count = minOf(frames - offset, capacityFrames)
                    buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, offset, count)
                    codec.queueInputBuffer(
                        index,
                        0,
                        count * 2,
                        presentationTimeUs(queuedFrames),
                        0
                    )
                    queuedFrames += count
                    offset += count
                    progressed = true
                }
            }
            if (drain()) progressed = true
            if (progressed) stalls = 0 else if (++stalls > MAX_CODEC_STALLS) {
                error("The device audio encoder stalled")
            }
        }
    }

    suspend fun finish() {
        while (!outputEnded) {
            currentCoroutineContext().ensureActive()
            var progressed = false
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (index >= 0) {
                    codec.queueInputBuffer(
                        index,
                        0,
                        0,
                        presentationTimeUs(queuedFrames),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    inputEnded = true
                    progressed = true
                }
            }
            if (drain()) progressed = true
            if (progressed) stalls = 0 else if (++stalls > MAX_CODEC_STALLS) {
                error("The device audio encoder stalled")
            }
        }
        writeTotalSamples()
    }

    private fun writeTotalSamples() {
        RandomAccessFile(outputFile, "rw").use { file ->
            val streamInfo = ByteArray(8)
            file.seek(STREAM_INFO_OFFSET)
            file.readFully(streamInfo)
            var value = 0L
            streamInfo.forEach { value = value shl 8 or (it.toLong() and 0xFF) }
            value = (value ushr 36 shl 36) or queuedFrames
            file.seek(STREAM_INFO_OFFSET)
            for (index in 0 until 8) {
                file.write(((value shr ((7 - index) * 8)) and 0xFF).toInt())
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        codec.release()
        output.close()
    }

    private fun drain(): Boolean {
        var progressed = false
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            if (index >= 0) {
                val buffer = codec.getOutputBuffer(index)!!
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                output.write(bytes)
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(index, false)
                progressed = true
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                progressed = true
            } else {
                return progressed
            }
        }
    }

    private fun presentationTimeUs(frames: Long): Long = frames * 1_000_000 / SHARED_SOUND_SAMPLE_RATE

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_CODEC_STALLS = 1_000
        private const val COMPRESSION_LEVEL = 5
        private const val STREAM_INFO_OFFSET = 18L

        fun isSupported(): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
            it.isEncoder && it.supportedTypes.contains(MediaFormat.MIMETYPE_AUDIO_FLAC)
        }
    }
}
