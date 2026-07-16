package com.offlinetts.reader.pipeline

/**
 * Worker A (producer): splits sanitized document text into sentence-sized chunks for the
 * synthesizer. Chunk granularity matters for two reasons — Kokoro was trained on
 * sentence-length inputs (long chunks degrade prosody), and small chunks bound the
 * latency between "user presses play" and "first audio out".
 */
object SentenceChunker {

    // Common English abbreviations whose trailing '.' should not be treated as a sentence
    // boundary. Not exhaustive by design — the phonemizer is far more forgiving of an
    // over-eager split than of a chunk that runs two unrelated sentences together.
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st",
        "vs", "etc", "e.g", "i.e", "approx", "no", "fig",
    )

    private val SENTENCE_END = Regex("(?<=[.!?])\\s+(?=[A-Z\"'“(])")

    /** Target chunk length; chunks are grown up to this before being flushed, whichever comes first. */
    private const val MAX_CHUNK_CHARS = 300

    fun chunk(text: String): List<TextChunk> {
        val chunks = ArrayList<TextChunk>()
        var index = 0

        for (paragraph in text.split(Regex("\\n{2,}"))) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) continue

            val sentences = splitSentences(trimmed)
            val buffer = StringBuilder()

            fun flush() {
                if (buffer.isNotEmpty()) {
                    chunks += TextChunk(index++, buffer.toString().trim())
                    buffer.clear()
                }
            }

            for (sentence in sentences) {
                if (buffer.length + sentence.length > MAX_CHUNK_CHARS && buffer.isNotEmpty()) {
                    flush()
                }
                buffer.append(if (buffer.isEmpty()) sentence else " $sentence")
                if (buffer.length >= MAX_CHUNK_CHARS) flush()
            }
            flush()
        }
        return chunks
    }

    private fun splitSentences(paragraph: String): List<String> {
        val raw = paragraph.split(SENTENCE_END)
        val merged = ArrayList<String>()
        for (piece in raw) {
            val prev = merged.lastOrNull()
            val prevEndsWithAbbrev = prev != null &&
                ABBREVIATIONS.contains(prev.substringAfterLast(' ').trimEnd('.').lowercase())
            if (prevEndsWithAbbrev) {
                merged[merged.lastIndex] = "$prev $piece"
            } else {
                merged += piece
            }
        }
        return merged.filter { it.isNotBlank() }
    }
}
