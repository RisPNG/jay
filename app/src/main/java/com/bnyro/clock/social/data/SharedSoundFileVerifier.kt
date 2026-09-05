package com.bnyro.clock.social.data

import android.media.MediaExtractor
import android.media.MediaFormat
import com.bnyro.clock.social.domain.SHARED_SOUND_MAX_DURATION_MS
import com.bnyro.clock.social.domain.SHARED_SOUND_MAX_OUTPUT_FRAMES
import com.bnyro.clock.social.domain.SHARED_SOUND_SAMPLE_RATE
import java.io.File
import java.security.MessageDigest

object SharedSoundFileVerifier {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(
        file: File,
        sha256: String? = null,
        byteLength: Long? = null,
        durationMs: Int? = null
    ) {
        check(file.length() > 0) { "The sound file is empty" }
        byteLength?.let {
            check(file.length() == it) { "The sound file does not match its expected length" }
        }
        sha256?.let {
            check(this.sha256(file) == it) { "The sound file does not match its checksum" }
        }
        val streamInfo = file.inputStream().use(::readFlacStreamInfo)
        check(streamInfo.sampleRate == SHARED_SOUND_SAMPLE_RATE) {
            "The shared sound is not ${SHARED_SOUND_SAMPLE_RATE} Hz"
        }
        check(streamInfo.channelCount == 1) { "The shared sound is not mono" }
        check(streamInfo.bitsPerSample == 16) { "The shared sound is not 16-bit" }
        check(streamInfo.totalSamples in 1..SHARED_SOUND_MAX_OUTPUT_FRAMES) {
            "The shared sound is empty or too long"
        }
        val actualDurationMs = streamInfo.totalSamples * 1000 / SHARED_SOUND_SAMPLE_RATE
        check(actualDurationMs <= SHARED_SOUND_MAX_DURATION_MS) { "The shared sound is too long" }
        durationMs?.let {
            check(kotlin.math.abs(actualDurationMs - it) <= 1) {
                "The shared sound duration does not match"
            }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            check(extractor.trackCount == 1) { "The shared sound has multiple tracks" }
            val format = extractor.getTrackFormat(0)
            check(format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                "The device cannot read the shared sound"
            }
            check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == SHARED_SOUND_SAMPLE_RATE) {
                "The shared sound is not ${SHARED_SOUND_SAMPLE_RATE} Hz"
            }
            check(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) {
                "The shared sound is not mono"
            }
        } finally {
            extractor.release()
        }
    }
}
