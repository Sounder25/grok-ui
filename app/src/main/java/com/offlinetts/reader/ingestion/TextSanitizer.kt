package com.offlinetts.reader.ingestion

/**
 * Strict, deterministic cleanup pass that runs between raw text extraction and the
 * phonemizer. Anything that reaches espeak-ng un-normalized turns into a mispronunciation
 * or a synthesis glitch, so this is intentionally conservative rather than clever.
 */
object TextSanitizer {

    // Zero-width and directional-control characters that render invisibly but break
    // word-boundary detection in the phonemizer.
    private val ZERO_WIDTH = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]")

    // Broad emoji / pictograph / symbol ranges. Intentionally excludes punctuation blocks
    // so real punctuation used for prosody (em dash, curly quotes) survives.
    // \x{...} (rather than \uXXXX, which is limited to 4 hex digits) is required for the
    // supplementary-plane emoji blocks above U+FFFF.
    private val EMOJI = Regex(
        "[\\u2190-\\u21FF\\u2300-\\u27BF\\u2B00-\\u2BFF\\x{1F000}-\\x{1FFFF}\\uFE0F]"
    )

    private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    // Collapse repeated whitespace but keep paragraph breaks intact.
    private val MULTI_SPACE = Regex("[ \\t]{2,}")
    private val MULTI_BLANK_LINE = Regex("\\n{3,}")

    // Common ligatures/smart punctuation normalized to forms espeak-ng's lexicon expects.
    private val REPLACEMENTS = listOf(
        "‘" to "'", "’" to "'", // curly single quotes
        "“" to "\"", "”" to "\"", // curly double quotes
        "–" to "-", "—" to " - ", // en/em dash
        "…" to "...", // ellipsis
        "ﬁ" to "fi", "ﬂ" to "fl", // fi/fl ligatures
    )

    fun sanitize(raw: String): String {
        var text = raw
        for ((from, to) in REPLACEMENTS) {
            text = text.replace(from, to)
        }
        text = ZERO_WIDTH.replace(text, "")
        text = EMOJI.replace(text, "")
        text = CONTROL_CHARS.replace(text, "")
        text = text.replace(MULTI_SPACE, " ")
        text = text.replace(MULTI_BLANK_LINE, "\n\n")
        return text.trim()
    }
}
