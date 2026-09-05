package com.bnyro.clock.social.data

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CanonicalSoundConverterTest {
    private class Collector {
        val chunks = mutableListOf<ShortArray>()
        var frames = 0L
        val sink: suspend (ShortArray, Int) -> Unit = { samples, count ->
            chunks += samples.copyOf(count)
            frames += count
        }

        fun toArray(): ShortArray {
            val all = ShortArray(frames.toInt())
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(all, offset)
                offset += chunk.size
            }
            return all
        }
    }

    private fun tone(
        frequencyHz: Int,
        sampleRate: Int,
        frames: Int,
        channels: Int = 1,
        amplitude: Double = 0.5
    ): ShortArray {
        val samples = ShortArray(frames * channels)
        for (frame in 0 until frames) {
            val value = (amplitude * Short.MAX_VALUE *
                sin(2 * PI * frequencyHz * frame / sampleRate)).roundToInt()
            repeat(channels) { channel -> samples[frame * channels + channel] = value.toShort() }
        }
        return samples
    }

    private fun convert(
        samples: ShortArray,
        sampleRate: Int,
        channels: Int,
        maxOutputFrames: Long = Long.MAX_VALUE,
        split: List<Int>? = null
    ): Collector = runBlocking {
        val collector = Collector()
        val converter = CanonicalSoundConverter(sampleRate, channels, maxOutputFrames, collector.sink)
        if (split == null) {
            converter.push(samples, samples.size / channels)
        } else {
            var offset = 0
            split.forEach { chunk ->
                converter.push(samples.copyOfRange(offset * channels, (offset + chunk) * channels), chunk)
                offset += chunk
            }
        }
        converter.finish()
        collector
    }

    @Test
    fun passthroughMonoKeepsSamplesUnchanged() {
        val samples = ShortArray(10_000) { (it * 31 % 65536 - 32768).toShort() }
        val output = convert(samples, 48_000, 1)
        assertEquals(samples.size.toLong(), output.frames)
        assertTrue(output.toArray().contentEquals(samples))
    }

    @Test
    fun passthroughStereoIdenticalChannelsKeepsLevel() {
        val mono = ShortArray(5_000) { (it * 17 % 65536 - 32768).toShort() }
        val stereo = ShortArray(mono.size * 2)
        mono.forEachIndexed { index, value ->
            stereo[index * 2] = value
            stereo[index * 2 + 1] = value
        }
        val output = convert(stereo, 48_000, 2)
        assertEquals(mono.size.toLong(), output.frames)
        assertTrue(output.toArray().contentEquals(mono))
    }

    @Test
    fun passthroughStereoOppositePolarityCancels() {
        val stereo = ShortArray(5_000 * 2)
        for (frame in 0 until 5_000) {
            val value = ((frame * 13 % 65_535) - 32_767).toShort()
            stereo[frame * 2] = value
            stereo[frame * 2 + 1] = (-value).toShort()
        }
        val output = convert(stereo, 48_000, 2)
        assertEquals(5_000L, output.frames)
        assertTrue(output.toArray().all { it == 0.toShort() })
    }

    @Test
    fun resamplingKeepsDcLevel() {
        listOf(44_100, 22_050, 96_000).forEach { sampleRate ->
            listOf(1, 2, 6).forEach { channels ->
                val dc = 16_000.toShort()
                val samples = ShortArray(20_000 * channels) { dc }
                val output = convert(samples, sampleRate, channels)
                val expected = ceil(20_000.0 * 48_000 / sampleRate).toLong()
                assertEquals("rate $sampleRate channels $channels", expected, output.frames)
                val steadyState = output.toArray().sliceArray(1_000 until output.frames.toInt() - 1_000)
                steadyState.forEach { sample ->
                    assertTrue("rate $sampleRate channels $channels", abs(sample - dc) <= 2)
                }
            }
        }
    }

    @Test
    fun oneKilohertzToneSurvivesResampling() {
        val output = convert(tone(1_000, 44_100, 44_100), 44_100, 1)
        val samples = output.toArray()
        assertEquals(48_000L, output.frames)
        var crossings = 0
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            if ((previous < 0 && current >= 0) || (previous >= 0 && current < 0)) crossings++
        }
        assertTrue("measured ${crossings / 2.0} Hz", abs(crossings / 2.0 - 1_000) <= 30)
        val peak = samples.sliceArray(1_000 until samples.size - 1_000)
            .maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak >= 0.9 * 0.5 * Short.MAX_VALUE)
    }

    @Test
    fun thirtyKilohertzToneAtNinetySixKilohertzIsAttenuated() {
        val input = tone(30_000, 96_000, 48_000, amplitude = 0.9)
        val output = convert(input, 96_000, 1)
        val samples = output.toArray()
        val inputRms = sqrt(input.map { (it / 32768.0) * (it / 32768.0) }.average())
        val outputRms = sqrt(
            samples.sliceArray(1_000 until samples.size - 1_000)
                .map { (it / 32768.0) * (it / 32768.0) }
                .average()
        )
        assertTrue("input $inputRms output $outputRms", outputRms <= inputRms * 0.01)
    }

    @Test
    fun outputFrameCountMatchesRatio() {
        listOf(8_000, 16_000, 22_050, 32_000, 44_100, 48_000, 88_200, 96_000, 192_000).forEach { sampleRate ->
            val frames = 5_000
            val output = convert(ShortArray(frames), sampleRate, 1)
            val expected = (frames * 48_000L + sampleRate - 1) / sampleRate
            assertEquals("rate $sampleRate", expected, output.frames)
        }
    }

    @Test
    fun chunkPartitioningDoesNotChangeOutput() {
        val random = Random(42)
        val frames = 20_000
        val samples = ShortArray(frames * 2) { random.nextInt(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
        val whole = convert(samples, 44_100, 2).toArray()
        val splits = mutableListOf<Int>()
        var remaining = frames
        while (remaining > 0) {
            val chunk = minOf(remaining, random.nextInt(1, 3_000))
            splits += chunk
            remaining -= chunk
        }
        val partitioned = convert(samples, 44_100, 2, split = splits).toArray()
        assertTrue(whole.contentEquals(partitioned))
    }

    @Test
    fun emptyInputProducesNoOutput() {
        val output = convert(ShortArray(0), 44_100, 1)
        assertEquals(0L, output.frames)
    }

    @Test
    fun singleFrameResamplesToRatioCeiling() {
        val output = convert(shortArrayOf(12_345), 44_100, 1)
        assertEquals(2L, output.frames)
    }

    @Test
    fun outputIsCappedAtMaximum() {
        val converter = CanonicalSoundConverter(44_100, 1, 100) { _, _ -> }
        runBlocking {
            converter.push(ShortArray(5_000), 5_000)
            converter.finish()
        }
        assertEquals(100L, converter.outputFrames)
        assertTrue(converter.isComplete)
    }

    @Test
    fun fullScaleDcStaysInRange() {
        listOf(Short.MAX_VALUE, Short.MIN_VALUE).forEach { dc ->
            val output = convert(ShortArray(10_000) { dc }, 44_100, 1).toArray()
            assertTrue(output.all { it in Short.MIN_VALUE..Short.MAX_VALUE })
            output.sliceArray(1_000 until output.size - 1_000).forEach { sample ->
                assertTrue(abs(sample - dc) <= 2)
            }
        }
    }

    @Test
    fun silenceStaysSilent() {
        val output = convert(ShortArray(20_000), 44_100, 1).toArray()
        assertTrue(output.all { it == 0.toShort() })
    }
}
