package com.bnyro.clock.social.data

import android.content.Context
import androidx.core.net.toUri
import com.bnyro.clock.social.domain.SHARED_SOUND_MAX_DURATION_US
import com.bnyro.clock.social.domain.SHARED_SOUND_SAMPLE_RATE
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CancellationException

class SharedSoundStore(private val context: Context) {
    fun prepareUpload(soundId: String, sourceUri: String): File {
        val directory = File(context.filesDir, "sound-uploads").apply { mkdirs() }
        val source = File(directory, "$soundId.source")
        requireNotNull(context.contentResolver.openInputStream(sourceUri.toUri())).use { input ->
            java.io.FileOutputStream(source).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        return source
    }

    suspend fun cache(soundId: String, api: SocialApi): File? {
        cached(soundId)?.let { return it }
        val directory = sharedSoundDirectory()
        val temporary = File(directory, "$soundId.${UUID.randomUUID()}.part")
        try {
            val download = api.getSoundDownload(soundId)
            api.downloadSound(download, temporary)
            SharedSoundFileVerifier.verify(
                temporary,
                sha256 = download.sha256,
                byteLength = download.byteLength
            )
            keep(soundId, temporary)
            return cached(soundId)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return null
        } finally {
            temporary.delete()
        }
    }

    fun cached(soundId: String): File? = File(
        File(context.filesDir, "shared-sounds"),
        "$soundId.wav"
    ).takeIf(File::exists)

    suspend fun keep(soundId: String, processed: File) {
        val directory = sharedSoundDirectory()
        val temporary = File(directory, "$soundId.${UUID.randomUUID()}.part")
        val ready = File(directory, "$soundId.wav")
        try {
            SharedSoundFileVerifier.verify(processed)
            val streamInfo = processed.inputStream().use(::readFlacStreamInfo)
            val dataBytes = (streamInfo.totalSamples * 2).toInt()
            RandomAccessFile(temporary, "rw").use { output ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".toByteArray(Charsets.US_ASCII))
                    .putInt(36 + dataBytes)
                    .put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                    .putInt(16)
                    .putShort(1)
                    .putShort(1)
                    .putInt(SHARED_SOUND_SAMPLE_RATE)
                    .putInt(SHARED_SOUND_SAMPLE_RATE * 2)
                    .putShort(2)
                    .putShort(16)
                    .put("data".toByteArray(Charsets.US_ASCII))
                    .putInt(dataBytes)
                output.write(header.array())
                AndroidPcmDecoder(context, processed.toUri()).decode(
                    SHARED_SOUND_MAX_DURATION_US
                ) { block ->
                    check(block.sampleRate == SHARED_SOUND_SAMPLE_RATE && block.channelCount == 1) {
                        "The decoded shared sound format does not match"
                    }
                    val bytes = ByteBuffer.allocate(block.frames * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(block.samples, 0, block.frames)
                    output.write(bytes.array())
                    true
                }
                check(output.length() == 44L + dataBytes) {
                    "The decoded shared sound length does not match"
                }
                output.fd.sync()
            }
            check(temporary.renameTo(ready))
        } finally {
            temporary.delete()
        }
    }

    fun prune(activeSoundIds: Set<String>) {
        sharedSoundDirectory().listFiles().orEmpty().forEach {
            if (it.extension in setOf("flac", "wav") && it.nameWithoutExtension !in activeSoundIds) {
                it.delete()
            }
            if (it.extension == "part" && it.lastModified() < System.currentTimeMillis() - 3_600_000) it.delete()
        }
        File(context.filesDir, "sound-uploads").listFiles().orEmpty().filter {
            it.name.substringBefore('.') !in activeSoundIds && it.lastModified() < System.currentTimeMillis() - 3_600_000
        }.forEach { it.delete() }
    }

    private fun sharedSoundDirectory(): File =
        File(context.filesDir, "shared-sounds").apply { mkdirs() }
}
