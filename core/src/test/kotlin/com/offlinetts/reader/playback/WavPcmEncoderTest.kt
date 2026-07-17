package com.offlinetts.reader.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavPcmEncoderTest {

    @Test
    fun `header declares correct RIFF-WAVE-fmt-data layout and sizes`() {
        val pcm = FloatArray(100) { 0f }
        val bytes = WavPcmEncoder.encode(pcm, sampleRateHz = 24_000)

        assertEquals(44 + 100 * 2, bytes.size)

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { buf.get(it) }
        assertEquals("RIFF", String(riff, Charsets.US_ASCII))

        val riffSize = buf.int
        assertEquals(36 + 100 * 2, riffSize)

        val wave = ByteArray(4).also { buf.get(it) }
        assertEquals("WAVE", String(wave, Charsets.US_ASCII))

        val fmt = ByteArray(4).also { buf.get(it) }
        assertEquals("fmt ", String(fmt, Charsets.US_ASCII))

        assertEquals(16, buf.int) // fmt chunk size
        assertEquals(1, buf.short.toInt()) // PCM
        assertEquals(1, buf.short.toInt()) // mono
        assertEquals(24_000, buf.int) // sample rate
        assertEquals(24_000 * 2, buf.int) // byte rate
        assertEquals(2, buf.short.toInt()) // block align
        assertEquals(16, buf.short.toInt()) // bits per sample

        val data = ByteArray(4).also { buf.get(it) }
        assertEquals("data", String(data, Charsets.US_ASCII))
        assertEquals(100 * 2, buf.int) // data chunk size
    }

    @Test
    fun `round-trips full-scale and silent samples correctly`() {
        val pcm = floatArrayOf(0f, 1f, -1f, 0.5f, -0.5f)
        val bytes = WavPcmEncoder.encode(pcm, sampleRateHz = 24_000)

        val samples = ByteBuffer.wrap(bytes, 44, pcm.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        assertEquals(0, samples.get(0).toInt())
        assertEquals(Short.MAX_VALUE.toInt(), samples.get(1).toInt())
        assertEquals(Short.MIN_VALUE.toInt() + 1, samples.get(2).toInt()) // -1f * 32767, rounded
        assertEquals((0.5 * Short.MAX_VALUE).let(Math::round).toInt(), samples.get(3).toInt())
        assertEquals((-0.5 * Short.MAX_VALUE).let(Math::round).toInt(), samples.get(4).toInt())
    }

    @Test
    fun `clamps out-of-range samples instead of wrapping around`() {
        val pcm = floatArrayOf(2.5f, -3.0f)
        val bytes = WavPcmEncoder.encode(pcm, sampleRateHz = 24_000)

        val samples = ByteBuffer.wrap(bytes, 44, pcm.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        // Must clamp to +/-Short.MAX_VALUE (the encoder scales by MAX_VALUE, not 32768, so
        // -1f maps to -32767, not the asymmetric Short.MIN_VALUE) rather than wrapping.
        assertEquals(Short.MAX_VALUE.toInt(), samples.get(0).toInt())
        assertEquals(-Short.MAX_VALUE.toInt(), samples.get(1).toInt())
    }

    @Test
    fun `empty pcm array produces a header-only, zero-length-data wav`() {
        val bytes = WavPcmEncoder.encode(FloatArray(0), sampleRateHz = 24_000)
        assertEquals(44, bytes.size)
    }
}
