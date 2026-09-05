package com.bnyro.clock.social.data

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import com.bnyro.clock.social.domain.SHARED_SOUND_MAX_DURATION_US
import com.bnyro.clock.social.domain.SHARED_SOUND_MAX_OUTPUT_FRAMES
import com.bnyro.clock.social.domain.SHARED_SOUND_SAMPLE_RATE
import com.bnyro.clock.social.domain.SharedSoundProgress
import java.io.File
import java.util.UUID

data class ProcessedSharedSound(
    val file: File,
    val sha256: String,
    val durationMs: Int
)

class SharedSoundProcessor(private val context: Context) {
    suspend fun process(
        source: Uri,
        onProgress: (SharedSoundProgress) -> Unit
    ): ProcessedSharedSound {
        onProgress(SharedSoundProgress.Preparing)
        val resolvedSource = if (RingtoneManager.isDefault(source)) {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: error("The device has no default alarm sound")
        } else {
            source
        }
        val workingDirectory = File(context.cacheDir, "jay-sound-processing").apply { mkdirs() }
        val outputFile = File(workingDirectory, "${UUID.randomUUID()}.flac")
        try {
            val decoder = AndroidPcmDecoder(context, resolvedSource)
            val expectedFrames = decoder.durationUs
                .takeIf { it > 0 }
                ?.let { minOf(it * SHARED_SOUND_SAMPLE_RATE / 1_000_000, SHARED_SOUND_MAX_OUTPUT_FRAMES) }
            var converter: CanonicalSoundConverter? = null
            val encoder = FlacStreamEncoder(outputFile)
            try {
                decoder.decode(SHARED_SOUND_MAX_DURATION_US) { block ->
                    val activeConverter = converter ?: CanonicalSoundConverter(
                        block.sampleRate,
                        block.channelCount,
                        SHARED_SOUND_MAX_OUTPUT_FRAMES
                    ) { samples, frames -> encoder.write(samples, frames) }.also { converter = it }
                    activeConverter.push(block.samples, block.frames)
                    onProgress(
                        SharedSoundProgress.Processing(
                            expectedFrames
                                ?.let { (activeConverter.outputFrames.toFloat() / it).coerceAtMost(1f) }
                        )
                    )
                    !activeConverter.isComplete
                }
                val outputFrames = converter?.outputFrames ?: 0L
                check(outputFrames > 0) { "The selected audio file is empty" }
                converter?.finish()
                encoder.finish()
                onProgress(SharedSoundProgress.Finalizing)
                val durationMs = (outputFrames * 1000 / SHARED_SOUND_SAMPLE_RATE).toInt()
                SharedSoundFileVerifier.verify(outputFile, durationMs = durationMs)
                return ProcessedSharedSound(
                    outputFile,
                    SharedSoundFileVerifier.sha256(outputFile),
                    durationMs
                )
            } finally {
                encoder.close()
            }
        } catch (exception: Exception) {
            outputFile.delete()
            throw exception
        }
    }
}
