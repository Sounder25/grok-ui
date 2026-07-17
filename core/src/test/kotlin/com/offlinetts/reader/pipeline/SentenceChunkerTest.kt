package com.offlinetts.reader.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SentenceChunkerTest {

    @Test
    fun `splits simple sentences within a paragraph`() {
        val text = "This is one. This is two. This is three."
        val chunks = SentenceChunker.chunk(text).map { it.text }
        assertEquals(listOf("This is one. This is two. This is three."), chunks)
    }

    @Test
    fun `does not split on abbreviations like Dr or Mrs`() {
        val text = "Dr. Smith met Mrs. Jones. They talked."
        val chunks = SentenceChunker.chunk(text).map { it.text }
        assertEquals(1, chunks.size, "abbreviation periods should not create extra sentence splits: $chunks")
        assertEquals("Dr. Smith met Mrs. Jones. They talked.", chunks[0])
    }

    @Test
    fun `splits separate paragraphs into separate chunk groups`() {
        val text = "Paragraph one sentence.\n\nParagraph two sentence."
        val chunks = SentenceChunker.chunk(text).map { it.text }
        assertEquals(listOf("Paragraph one sentence.", "Paragraph two sentence."), chunks)
    }

    @Test
    fun `flushes a chunk once it exceeds the max length rather than growing unbounded`() {
        val longSentence = "word ".repeat(80).trim() + "." // ~404 chars, one "sentence"
        val text = "$longSentence Short one."
        val chunks = SentenceChunker.chunk(text)
        assertTrue(chunks.size >= 2, "expected the oversized sentence to still flush before appending more: $chunks")
        assertTrue(chunks.all { it.text.length <= 500 }, "no chunk should balloon indefinitely: ${chunks.map { it.text.length }}")
    }

    @Test
    fun `indices are assigned in document order starting at zero`() {
        val text = "One. Two.\n\nThree. Four."
        val chunks = SentenceChunker.chunk(text)
        assertEquals(listOf(0, 1), chunks.map { it.index })
    }

    @Test
    fun `blank input yields no chunks`() {
        assertEquals(emptyList<TextChunk>(), SentenceChunker.chunk("   \n\n  "))
    }
}
