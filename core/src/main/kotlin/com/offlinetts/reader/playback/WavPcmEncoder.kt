package com.offlinetts.reader.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Wraps one chunk of Kokoro's raw Float32 PCM output in a minimal, fully self-contained
 * mono 16-bit PCM WAV envelope. Each synthesized chunk becomes its own tiny in-memory
 * "file" with a correct, known-in-advance length — see [com.offlinetts.reader.playback.TtsPlayer]
 * for why: it lets each chunk become one queued ExoPlayer MediaItem, and ExoPlayer's
 * playlist transitions between same-format items gaplessly, which is what actually
 * delivers the "no gap between paragraphs" requirement without inventing a fake streaming
 * container format.
 */
object WavPcmEncoder {

    fun encode(pcm: FloatArray, sampleRateHz: Int): ByteArray {
        val dataSize = pcm.size * 2 // 16-bit samples
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // fmt chunk size
            putShort(1) // PCM
            putShort(1) // mono
            putInt(sampleRateHz)
            putInt(sampleRateHz * 2) // byte rate = sampleRate * channels * bytesPerSample
            putShort(2) // block align
            putShort(16) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }

        val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) {
            val clamped = sample.coerceIn(-1f, 1f)
            val intSample = (clamped * Short.MAX_VALUE).roundToInt().toShort()
            body.putShort(intSample)
        }

        return header.array() + body.array()
    }
}
