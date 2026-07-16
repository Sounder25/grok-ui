package com.offlinetts.reader.engine

import android.content.Context
import org.json.JSONObject

/**
 * Maps IPA phoneme characters to the integer vocabulary Kokoro was exported with.
 *
 * Deliberately *not* hardcoded: Kokoro's phoneme→id table is a property of the specific
 * ONNX export (see docs/BUILD.md's export script), so it's bundled as
 * `assets/models/tokenizer.json` — a flat `{"symbol": id}` map — next to the model file
 * rather than baked into source, which would silently drift out of sync with whichever
 * model build is bundled.
 */
class PhonemeTokenizer private constructor(private val vocab: Map<String, Long>) {

    // Kokoro's StyleTTS2-derived architecture reserves id 0 as the boundary/padding token
    // it expects at the start and end of every input sequence.
    private val paddingId = vocab["\$"] ?: 0L

    // The exported model's positional/style tables are sized for this many tokens
    // (StyleTTS2/Kokoro's default context window); longer inputs must be chunked upstream
    // by the sentence chunker rather than truncated silently here.
    val maxTokens = 510

    fun tokenize(phonemes: String): LongArray {
        val ids = ArrayList<Long>(phonemes.length + 2)
        ids += paddingId
        for (ch in phonemes) {
            if (ch.isWhitespace() && ids.lastOrNull() == vocab[" "]) continue // collapse runs
            vocab[ch.toString()]?.let { ids += it }
        }
        ids += paddingId

        require(ids.size <= maxTokens) {
            "Phoneme chunk too long (${ids.size} tokens > $maxTokens); the sentence " +
                "chunker should have split this further before it reached the engine."
        }
        return ids.toLongArray()
    }

    companion object {
        private const val VOCAB_ASSET_PATH = "models/tokenizer.json"

        fun load(context: Context): PhonemeTokenizer {
            val json = context.assets.open(VOCAB_ASSET_PATH).use { it.readBytes() }
                .toString(Charsets.UTF_8)
            val obj = JSONObject(json)
            val vocab = HashMap<String, Long>(obj.length())
            obj.keys().forEach { key -> vocab[key] = obj.getLong(key) }
            return PhonemeTokenizer(vocab)
        }
    }
}
