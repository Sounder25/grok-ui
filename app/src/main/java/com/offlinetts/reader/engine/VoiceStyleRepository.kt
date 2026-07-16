package com.offlinetts.reader.engine

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kokoro conditions generation on a per-voice "style" vector, and that vector is itself
 * indexed by the token count of the current chunk (the model was trained with
 * length-bucketed style embeddings — StyleTTS2's diffusion-free style vectors). Each
 * voice ships as a flat float32 binary blob of shape `[maxTokens, styleDim]`; this class
 * memory-maps it once and slices out the row matching the current chunk's length.
 */
class VoiceStyleRepository(private val context: Context) {

    companion object {
        private const val STYLE_DIM = 256
        private const val MAX_TOKENS = 510
        private const val VOICES_DIR = "models/voices"
    }

    private val cache = HashMap<String, FloatArray>()

    fun availableVoices(): List<String> =
        context.assets.list(VOICES_DIR)?.filter { it.endsWith(".bin") }
            ?.map { it.removeSuffix(".bin") }
            ?.sorted()
            ?: emptyList()

    /** Returns the [STYLE_DIM]-length style vector for [voiceId] at the given [tokenCount]. */
    fun styleFor(voiceId: String, tokenCount: Int): FloatArray {
        val full = cache.getOrPut(voiceId) { loadVoiceBlob(voiceId) }
        val rowIndex = (tokenCount - 1).coerceIn(0, MAX_TOKENS - 1)
        val start = rowIndex * STYLE_DIM
        return full.copyOfRange(start, start + STYLE_DIM)
    }

    private fun loadVoiceBlob(voiceId: String): FloatArray {
        val bytes = context.assets.open("$VOICES_DIR/$voiceId.bin").use { it.readBytes() }
        val expected = MAX_TOKENS * STYLE_DIM * 4
        require(bytes.size == expected) {
            "Voice blob '$voiceId' is ${bytes.size} bytes, expected $expected " +
                "($MAX_TOKENS x $STYLE_DIM float32). Was it exported with a different shape?"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(MAX_TOKENS * STYLE_DIM)
        buffer.asFloatBuffer().get(floats)
        return floats
    }
}
