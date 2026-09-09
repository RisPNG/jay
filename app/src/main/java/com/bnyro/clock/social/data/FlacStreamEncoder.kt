package com.bnyro.clock.social.data

import com.bnyro.clock.social.domain.SHARED_SOUND_SAMPLE_RATE
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class FlacStreamEncoder(outputFile: File) : Closeable {
    private var handle = createEncoder(outputFile.absolutePath, SHARED_SOUND_SAMPLE_RATE)
    private var finished = false

    suspend fun write(samples: ShortArray, frames: Int) {
        check(handle != 0L && !finished) { "The FLAC encoder is not writable" }
        require(frames in 0..samples.size)
        var offset = 0
        while (offset < frames) {
            currentCoroutineContext().ensureActive()
            val count = minOf(frames - offset, 4096)
            if (!encodeSamples(handle, samples, offset, count)) {
                throw IOException("Cannot encode shared audio")
            }
            offset += count
        }
    }

    suspend fun finish() {
        check(handle != 0L && !finished) { "The FLAC encoder is not writable" }
        currentCoroutineContext().ensureActive()
        finished = true
        if (!finishEncoder(handle)) throw IOException("Cannot finalize shared audio")
    }

    override fun close() {
        if (handle == 0L) return
        deleteEncoder(handle)
        handle = 0L
    }

    private external fun createEncoder(path: String, sampleRate: Int): Long
    private external fun encodeSamples(handle: Long, samples: ShortArray, offset: Int, count: Int): Boolean
    private external fun finishEncoder(handle: Long): Boolean
    private external fun deleteEncoder(handle: Long)

    companion object {
        init {
            System.loadLibrary("jay_audio")
        }
    }
}
