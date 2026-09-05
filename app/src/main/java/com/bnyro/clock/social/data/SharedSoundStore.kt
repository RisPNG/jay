package com.bnyro.clock.social.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class SharedSoundStore(private val context: Context) {
    fun cache(soundId: String, api: SocialApi): File? {
        val directory = sharedSoundDirectory()
        val ready = File(directory, "$soundId.flac")
        if (ready.exists()) return ready
        val download = api.getSoundDownload(soundId)
        val temporary = File(directory, "$soundId.${UUID.randomUUID()}.part")
        try {
            api.downloadSound(download, temporary)
            SharedSoundFileVerifier.verify(
                temporary,
                sha256 = download.sha256,
                byteLength = download.byteLength
            )
            sync(temporary)
            if (ready.exists()) {
                temporary.delete()
            } else {
                check(temporary.renameTo(ready))
            }
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
        val directory = sharedSoundDirectory()
        val temporary = File(directory, "$soundId.${UUID.randomUUID()}.part")
        val ready = File(directory, "$soundId.flac")
        processed.inputStream().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        try {
            SharedSoundFileVerifier.verify(temporary)
            sync(temporary)
            check(temporary.renameTo(ready))
        } catch (exception: Exception) {
            temporary.delete()
            throw exception
        }
    }

    fun prune(activeSoundIds: Set<String>) {
        sharedSoundDirectory().listFiles().orEmpty().forEach {
            if (it.extension == "flac" && it.nameWithoutExtension !in activeSoundIds) it.delete()
            if (it.extension == "part") it.delete()
        }
    }

    private fun sharedSoundDirectory(): File =
        File(context.filesDir, "shared-sounds").apply { mkdirs() }

    private fun sync(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }
}
