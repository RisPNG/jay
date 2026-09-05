package com.bnyro.clock.social.data

import java.io.InputStream

data class FlacStreamInfo(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val totalSamples: Long
)

fun readFlacStreamInfo(input: InputStream): FlacStreamInfo {
    val header = ByteArray(FLAC_HEADER_BYTES)
    var offset = 0
    while (offset < header.size) {
        val count = input.read(header, offset, header.size - offset)
        if (count < 0) error("The sound file is not a valid FLAC file")
        offset += count
    }
    check(
        header[0] == 'f'.code.toByte() &&
            header[1] == 'L'.code.toByte() &&
            header[2] == 'a'.code.toByte() &&
            header[3] == 'C'.code.toByte()
    ) { "The sound file is not a valid FLAC file" }
    check(header[4].toInt() and 0x7F == 0) { "The FLAC file does not start with stream info" }
    check(
        (header[5].toInt() and 0xFF shl 16) +
            (header[6].toInt() and 0xFF shl 8) +
            (header[7].toInt() and 0xFF) == FLAC_STREAM_INFO_BYTES
    ) { "The FLAC stream info block has an unexpected size" }
    var streamInfo = 0L
    for (index in 18 until 26) {
        streamInfo = streamInfo shl 8 or (header[index].toLong() and 0xFF)
    }
    return FlacStreamInfo(
        sampleRate = ((streamInfo shr 44) and 0xFFFFF).toInt(),
        channelCount = ((streamInfo shr 41) and 0x7).toInt() + 1,
        bitsPerSample = ((streamInfo shr 36) and 0x1F).toInt() + 1,
        totalSamples = streamInfo and ((1L shl 36) - 1)
    )
}

private const val FLAC_HEADER_BYTES = 42
private const val FLAC_STREAM_INFO_BYTES = 34
