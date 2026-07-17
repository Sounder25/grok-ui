package com.offlinetts.reader.ingestion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TextSanitizerTest {

    @Test
    fun `strips zero-width and directional control characters`() {
        val input = "Hello​World‌‪test‬"
        val out = TextSanitizer.sanitize(input)
        assertEquals("HelloWorldtest", out)
    }

    @Test
    fun `strips emoji including supplementary-plane pictographs`() {
        val input = "Great job! 😀👍 done"
        val out = TextSanitizer.sanitize(input)
        assertFalse(out.contains("\uD83D"), "surrogate pair for emoji should be fully removed")
        assertEquals("Great job! done", out)
    }

    @Test
    fun `normalizes curly quotes and dashes`() {
        val input = "‘single’ “double” word–word em—dash"
        val out = TextSanitizer.sanitize(input)
        assertEquals("'single' \"double\" word-word em - dash", out)
    }

    @Test
    fun `normalizes ellipsis and ligatures`() {
        val input = "wait… ﬁnally ﬂow"
        val out = TextSanitizer.sanitize(input)
        assertEquals("wait... finally flow", out)
    }

    @Test
    fun `collapses runs of spaces and blank lines but keeps paragraph breaks`() {
        val input = "para one\n\n\n\n\npara   two   with    spaces"
        val out = TextSanitizer.sanitize(input)
        assertEquals("para one\n\npara two with spaces", out)
    }

    @Test
    fun `strips control characters entirely, with no replacement space`() {
        val input = "prefixsuffix"
        val out = TextSanitizer.sanitize(input)
        assertEquals("prefixsuffix", out)
    }

    @Test
    fun `keeps single ordinary spaces untouched`() {
        val input = "word one word two"
        val out = TextSanitizer.sanitize(input)
        assertEquals("word one word two", out)
    }
}
