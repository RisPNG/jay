package com.bnyro.clock.social.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.RingtoneManager
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

data class ProcessedSharedSound(
    val file: File,
    val sha256: String,
    val durationMs: Int
)

class SharedSoundStore(private val context: Context) {
    fun process(source: Uri): ProcessedSharedSound {
        val resolvedSource = if (RingtoneManager.isDefault(source)) {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: error("The device has no default alarm sound")
        } else {
            source
        }
        val workingDirectory = File(context.cacheDir, "jay-sound-processing").apply { mkdirs() }
        val stem = UUID.randomUUID().toString()
        val pcmFile = File(workingDirectory, "$stem.pcm")
        val outputFile = File(workingDirectory, "$stem.flac")
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, resolvedSource, null)
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
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var accumulator = 0L
            var outputFrames = 0L
            FileOutputStream(pcmFile).use { pcm ->
                val info = MediaCodec.BufferInfo()
                while (!outputEnded) {
                    if (!inputEnded) {
                        val index = decoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = decoder.getInputBuffer(index)!!
                            val sampleTime = extractor.sampleTime
                            if (sampleTime < 0 || sampleTime >= MAX_DURATION_US) {
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
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val format = decoder.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        check(
                            !format.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                                format.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                AudioFormat.ENCODING_PCM_16BIT
                        ) { "The device decoder did not produce 16-bit PCM" }
                    } else if (index >= 0) {
                        val buffer = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val shorts = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (shorts.remaining() >= channelCount) {
                            var sum = 0
                            repeat(channelCount) { sum += shorts.get().toInt() }
                            val mono = sum / channelCount
                            accumulator += TARGET_SAMPLE_RATE
                            while (accumulator >= sampleRate && outputFrames < MAX_OUTPUT_FRAMES) {
                                pcm.write(mono and 0xff)
                                pcm.write((mono shr 8) and 0xff)
                                outputFrames++
                                accumulator -= sampleRate
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
            check(outputFrames > 0) { "The selected audio file is empty" }
            encodeFlac(pcmFile, outputFile, outputFrames)
            val verified = MediaExtractor()
            try {
                verified.setDataSource(outputFile.absolutePath)
                val format = verified.getTrackFormat(0)
                check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_FLAC)
                check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == TARGET_SAMPLE_RATE)
                check(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
            } finally {
                verified.release()
            }
            val digest = MessageDigest.getInstance("SHA-256")
            outputFile.inputStream().use { input ->
                val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
            }
            return ProcessedSharedSound(
                outputFile,
                digest.digest().joinToString("") { "%02x".format(it) },
                ((outputFrames * 1000) / TARGET_SAMPLE_RATE).toInt()
            )
        } catch (exception: Exception) {
            outputFile.delete()
            throw exception
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
            pcmFile.delete()
        }
    }

    fun cache(soundId: String, api: SocialApi): File? {
        val directory = File(context.filesDir, "shared-sounds").apply { mkdirs() }
        val ready = File(directory, "$soundId.flac")
        if (ready.exists()) return ready
        val download = api.getSoundDownload(soundId)
        val temporary = File(directory, "$soundId.part")
        try {
            api.downloadSound(download, temporary)
            check(temporary.length() == download.byteLength)
            val digest = MessageDigest.getInstance("SHA-256")
            temporary.inputStream().use { input ->
                val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == download.sha256)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(temporary.absolutePath)
                val format = extractor.getTrackFormat(0)
                check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_FLAC)
                check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == TARGET_SAMPLE_RATE)
                check(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
            } finally {
                extractor.release()
            }
            check(temporary.renameTo(ready))
            return ready
        } catch (_: Exception) {
            temporary.delete()
            return null
        }
    }

    fun cached(soundId: String): File? = File(
        File(context.filesDir, "shared-sounds"),
        "$soundId.flac"
    ).takeIf(File::exists)

    fun keep(soundId: String, processed: File) {
        val directory = File(context.filesDir, "shared-sounds").apply { mkdirs() }
        val temporary = File(directory, "$soundId.part")
        val ready = File(directory, "$soundId.flac")
        processed.inputStream().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        check(temporary.renameTo(ready))
    }

    fun prune(activeSoundIds: Set<String>) {
        File(context.filesDir, "shared-sounds").listFiles().orEmpty().forEach {
            if (it.extension == "flac" && it.nameWithoutExtension !in activeSoundIds) it.delete()
            if (it.extension == "part") it.delete()
        }
    }

    private fun encodeFlac(inputFile: File, outputFile: File, frameCount: Long) {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        try {
            encoder.configure(
                MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_FLAC,
                    TARGET_SAMPLE_RATE,
                    1
                ).apply {
                    setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
                },
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            encoder.start()
            var inputEnded = false
            var outputEnded = false
            var queuedFrames = 0L
            inputFile.inputStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    val info = MediaCodec.BufferInfo()
                    while (!outputEnded) {
                        if (!inputEnded) {
                            val index = encoder.dequeueInputBuffer(10_000)
                            if (index >= 0) {
                                val buffer = encoder.getInputBuffer(index)!!
                                val bytes = ByteArray(buffer.remaining().coerceAtMost(DEFAULT_BUFFER_SIZE))
                                val count = input.read(bytes)
                                if (count < 0) {
                                    encoder.queueInputBuffer(
                                        index,
                                        0,
                                        0,
                                        queuedFrames * 1_000_000 / TARGET_SAMPLE_RATE,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputEnded = true
                                } else {
                                    buffer.put(bytes, 0, count)
                                    encoder.queueInputBuffer(
                                        index,
                                        0,
                                        count,
                                        queuedFrames * 1_000_000 / TARGET_SAMPLE_RATE,
                                        0
                                    )
                                    queuedFrames += count / 2
                                }
                            }
                        }
                        val index = encoder.dequeueOutputBuffer(info, 10_000)
                        if (index >= 0) {
                            val buffer = encoder.getOutputBuffer(index)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            output.write(bytes)
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            check(queuedFrames == frameCount)
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
        }
    }

    companion object {
        private const val TARGET_SAMPLE_RATE = 48_000
        private const val MAX_DURATION_US = 300_000_000L
        private const val MAX_OUTPUT_FRAMES = 14_400_000L
    }
}
