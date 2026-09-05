package com.bnyro.clock.social.data

import com.bnyro.clock.social.domain.SHARED_SOUND_SAMPLE_RATE
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class CanonicalSoundConverter(
    inputSampleRate: Int,
    private val channelCount: Int,
    private val maxOutputFrames: Long,
    private val sink: suspend (ShortArray, Int) -> Unit
) {
    private val passthrough = inputSampleRate == SHARED_SOUND_SAMPLE_RATE
    private val stepNumerator: Int
    private val stepDenominator: Int
    private val halfLength: Int
    private val phaseCount: Int
    private val phases: Array<FloatArray>
    private var tail = FloatArray(0)
    private var inputFrames = 0L
    private var nextOutput = 0L
    var outputFrames = 0L
        private set
    private val outputChunk = ShortArray(OUTPUT_CHUNK_FRAMES)
    private var outputChunkFrames = 0

    val isComplete: Boolean
        get() = outputFrames >= maxOutputFrames

    init {
        val divisor = gcd(inputSampleRate, SHARED_SOUND_SAMPLE_RATE)
        stepNumerator = SHARED_SOUND_SAMPLE_RATE / divisor
        stepDenominator = inputSampleRate / divisor
        val ratio = stepNumerator.toDouble() / stepDenominator
        halfLength = minOf(96.0, ceil(16.0 / minOf(1.0, ratio))).toInt()
        phaseCount = minOf(stepNumerator, MAX_PHASES)
        phases = if (passthrough) emptyArray() else Array(phaseCount) { phaseIndex ->
            val offset = phaseIndex.toDouble() / phaseCount
            val angle = 2 * PI * CUTOFF_FACTOR * minOf(1.0, ratio)
            val taps = FloatArray(2 * halfLength)
            for (tap in taps.indices) {
                val distance = (halfLength - 1 - tap) + offset
                val sinc = if (distance == 0.0) 1.0 else sin(angle * distance) / (angle * distance)
                val windowPosition = distance / halfLength
                val window = BLACKMAN_HARRIS[0] +
                    BLACKMAN_HARRIS[1] * cos(PI * windowPosition) +
                    BLACKMAN_HARRIS[2] * cos(2 * PI * windowPosition) +
                    BLACKMAN_HARRIS[3] * cos(3 * PI * windowPosition)
                taps[tap] = (2 * CUTOFF_FACTOR * minOf(1.0, ratio) * sinc * window).toFloat()
            }
            val sum = taps.sum()
            for (tap in taps.indices) taps[tap] /= sum
            taps
        }
    }

    suspend fun push(samples: ShortArray, frames: Int) {
        if (frames == 0 || isComplete) return
        if (passthrough) {
            inputFrames += frames
            val allowed = minOf(frames.toLong(), maxOutputFrames - outputFrames).toInt()
            if (allowed > 0) {
                if (channelCount == 1) {
                    sinkIntoChunk(samples, allowed)
                } else {
                    val mono = ShortArray(allowed)
                    for (frame in 0 until allowed) {
                        var sum = 0f
                        val base = frame * channelCount
                        for (channel in 0 until channelCount) sum += samples[base + channel] / PCM16_SCALE
                        mono[frame] = ((sum / channelCount) * PCM16_SCALE).roundToInt().toShort()
                    }
                    sinkIntoChunk(mono, allowed)
                }
                outputFrames += allowed
                flushOutput()
            }
            return
        }
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            val base = frame * channelCount
            for (channel in 0 until channelCount) sum += samples[base + channel] / PCM16_SCALE
            mono[frame] = sum / channelCount
        }
        resample(mono, ended = false)
        flushOutput()
    }

    suspend fun finish() {
        if (passthrough) {
            flushOutput()
            return
        }
        resample(FloatArray(0), ended = true)
        flushOutput()
    }

    private suspend fun resample(chunk: FloatArray, ended: Boolean) {
        val windowSize = 2 * halfLength
        val tailStart = (inputFrames - tail.size).toInt()
        val consumedFrames = inputFrames.toInt()
        val available = inputFrames + chunk.size
        while (!isComplete) {
            val position = nextOutput * stepDenominator
            val emittable = if (ended) {
                position < available * stepNumerator
            } else {
                position <= (available - 1 - halfLength) * stepNumerator
            }
            if (!emittable) break
            val center = (position / stepNumerator).toInt()
            val scaledPhase = (position % stepNumerator) * phaseCount
            val phaseIndex = (scaledPhase / stepNumerator).toInt()
            val blend = (scaledPhase % stepNumerator).toFloat() / stepNumerator
            val firstTaps = phases[phaseIndex]
            val secondTaps = phases[(phaseIndex + 1) % phaseCount]
            var value = 0f
            for (tap in 0 until windowSize) {
                val sampleIndex = center - halfLength + 1 + tap
                val sample = when {
                    sampleIndex < 0 || ended && sampleIndex >= available -> 0f
                    sampleIndex < consumedFrames -> tail[sampleIndex - tailStart]
                    else -> chunk[sampleIndex - consumedFrames]
                }
                value += sample * (firstTaps[tap] + (secondTaps[tap] - firstTaps[tap]) * blend)
            }
            appendOutput(value)
            nextOutput++
        }
        val end = tail.size + chunk.size
        val start = maxOf(0, end - windowSize)
        val newTail = FloatArray(minOf(end, windowSize))
        var write = 0
        var read = start
        while (read < end) {
            newTail[write++] = if (read < tail.size) tail[read] else chunk[read - tail.size]
            read++
        }
        tail = newTail
        inputFrames += chunk.size
    }

    private suspend fun sinkIntoChunk(samples: ShortArray, frames: Int) {
        var offset = 0
        while (offset < frames) {
            if (outputChunkFrames == outputChunk.size) flushOutput()
            val count = minOf(frames - offset, outputChunk.size - outputChunkFrames)
            samples.copyInto(outputChunk, outputChunkFrames, offset, offset + count)
            outputChunkFrames += count
            offset += count
        }
    }

    private suspend fun appendOutput(value: Float) {
        if (outputChunkFrames == outputChunk.size) flushOutput()
        outputChunk[outputChunkFrames++] = (value * PCM16_SCALE).roundToInt()
            .coerceIn(-32768, 32767)
            .toShort()
        outputFrames++
    }

    private suspend fun flushOutput() {
        if (outputChunkFrames > 0) {
            sink(outputChunk, outputChunkFrames)
            outputChunkFrames = 0
        }
    }

    private fun gcd(first: Int, second: Int): Int = if (second == 0) first else gcd(second, first % second)

    companion object {
        private const val PCM16_SCALE = 32768f
        private const val MAX_PHASES = 1024
        private const val CUTOFF_FACTOR = 0.475
        private const val OUTPUT_CHUNK_FRAMES = 4096
        private val BLACKMAN_HARRIS = doubleArrayOf(0.35875, 0.48829, 0.14128, 0.01168)
    }
}
