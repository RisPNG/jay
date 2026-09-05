package com.bnyro.clock.social.data

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FlacStreamInfoTest {
    private fun flacHeader(
        sampleRate: Int = 48_000,
        channelCount: Int = 1,
        bitsPerSample: Int = 16,
        totalSamples: Long = 48_000,
        magic: String = "fLaC",
        blockType: Int = 0,
        blockLength: Int = 34
    ): ByteArray {
        val header = ByteArray(42)
        magic.encodeToByteArray().copyInto(header, 0, 0, 4)
        header[4] = blockType.toByte()
        header[5] = ((blockLength shr 16) and 0xFF).toByte()
        header[6] = ((blockLength shr 8) and 0xFF).toByte()
        header[7] = (blockLength and 0xFF).toByte()
        val streamInfo = (sampleRate.toLong() shl 44) or
            ((channelCount - 1).toLong() shl 41) or
            ((bitsPerSample - 1).toLong() shl 36) or
            totalSamples
        for (index in 0 until 8) {
            header[18 + index] = ((streamInfo shr ((7 - index) * 8)) and 0xFF).toByte()
        }
        return header
    }

    @Test
    fun parsesCanonicalStreamInfo() {
        val info = readFlacStreamInfo(
            flacHeader(totalSamples = 14_400_000).inputStream()
        )
        assertEquals(48_000, info.sampleRate)
        assertEquals(1, info.channelCount)
        assertEquals(16, info.bitsPerSample)
        assertEquals(14_400_000L, info.totalSamples)
    }

    @Test
    fun parsesNonCanonicalStreamInfo() {
        val info = readFlacStreamInfo(
            flacHeader(sampleRate = 96_000, channelCount = 2, bitsPerSample = 24).inputStream()
        )
        assertEquals(96_000, info.sampleRate)
        assertEquals(2, info.channelCount)
        assertEquals(24, info.bitsPerSample)
    }

    @Test
    fun rejectsWrongMagic() {
        try {
            readFlacStreamInfo(flacHeader(magic = "fLbC").inputStream())
            fail("expected the wrong magic to be rejected")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun rejectsMissingStreamInfo() {
        try {
            readFlacStreamInfo(flacHeader(blockType = 1).inputStream())
            fail("expected a missing stream info block to be rejected")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun rejectsWrongBlockLength() {
        try {
            readFlacStreamInfo(flacHeader(blockLength = 33).inputStream())
            fail("expected the wrong block length to be rejected")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun rejectsTruncatedHeader() {
        try {
            readFlacStreamInfo(flacHeader().copyOfRange(0, 30).inputStream())
            fail("expected a truncated header to be rejected")
        } catch (_: IllegalStateException) {
        }
    }
}
