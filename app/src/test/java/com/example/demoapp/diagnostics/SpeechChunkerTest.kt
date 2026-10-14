package com.example.demoapp.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cutting a reply into pieces the speech engine will accept.
 *
 * The platform takes at most 4000 characters in one call, so a long answer is
 * spoken in several pieces, each synthesised independently. Every boundary is
 * therefore a place where a pause lands, and the tests are mostly about where it
 * lands rather than whether the split happens.
 */
class SpeechChunkerTest {

    @Test
    fun `a short reply is one piece`() {
        assertEquals(listOf("你好。"), SpeechChunker.split("你好。"))
    }

    @Test
    fun `nothing to say is nothing to queue`() {
        // Queueing a piece with no words in it asks the engine to speak silence, and
        // some engines answer that with an error.
        assertTrue(SpeechChunker.split("").isEmpty())
        assertTrue(SpeechChunker.split("   \n  ").isEmpty())
    }

    @Test
    fun `a reply exactly at the limit is not split`() {
        val text = "a".repeat(SpeechChunker.PLATFORM_LIMIT)

        assertEquals(1, SpeechChunker.split(text).size)
    }

    @Test
    fun `nothing exceeds the limit`() {
        // The one property that must hold whatever else does. A piece over the limit
        // is refused by the engine, and the reply stops partway with no explanation.
        val text = "这是一段很长的回复。".repeat(2000)

        val pieces = SpeechChunker.split(text)

        assertTrue(pieces.isNotEmpty())
        assertTrue(
            "Longest piece was ${pieces.maxOf { it.length }}",
            pieces.all { it.length <= SpeechChunker.PLATFORM_LIMIT },
        )
    }

    @Test
    fun `the text survives the split`() {
        // Nothing added, nothing lost. A chunker that drops a character has corrupted
        // what it was asked to read out.
        val text = buildString {
            repeat(3000) { append("第").append(it).append("句。") }
        }

        val rejoined = SpeechChunker.split(text).joinToString("")

        assertEquals(text.filterNot { it.isWhitespace() }, rejoined.filterNot { it.isWhitespace() })
    }

    @Test
    fun `a cut lands at a sentence ending, not mid-sentence`() {
        // Where the pause goes is the whole reason this is not `chunked(4000)`.
        val sentence = "这是一个完整的句子。"
        val text = sentence.repeat(1000)

        val pieces = SpeechChunker.split(text)

        assertTrue("More than one piece", pieces.size > 1)
        pieces.dropLast(1).forEach { piece ->
            assertTrue(
                "Ends at a sentence: ...${piece.takeLast(12)}",
                piece.endsWith("。"),
            )
        }
    }

    @Test
    fun `a sentence longer than the limit is cut anyway`() {
        // Nothing to break on, so it takes the ugly cut rather than looping forever
        // or dropping the rest.
        val text = "字".repeat(SpeechChunker.PLATFORM_LIMIT * 3)

        val pieces = SpeechChunker.split(text)

        assertEquals("字".repeat(SpeechChunker.PLATFORM_LIMIT), pieces[0])
        assertEquals(3, pieces.size)
    }

    @Test
    fun `a piece is never a fragment when a full one is available`() {
        // Cutting at the first sentence would make a paragraph of short sentences
        // into forty utterances with a pause between each. The search starts from the
        // end and never goes past halfway.
        val text = "短句。".repeat(3000)

        val pieces = SpeechChunker.split(text)

        pieces.dropLast(1).forEach { piece ->
            assertTrue(
                "Not a sliver: ${piece.length}",
                piece.length > SpeechChunker.PLATFORM_LIMIT / 4,
            )
        }
    }

    @Test
    fun `english sentences break too`() {
        val text = "This is a sentence. ".repeat(2000)

        val pieces = SpeechChunker.split(text)

        assertTrue(pieces.size > 1)
        pieces.dropLast(1).forEach { piece ->
            assertTrue("Ends a sentence: ...${piece.takeLast(20)}", piece.trimEnd().endsWith("."))
        }
    }

    @Test
    fun `a newline is a better break than nothing`() {
        // Code and lists have no full stops and plenty of newlines, and the engine
        // reads a line break as a pause anyway.
        val text = ("a line of text with no full stop at all\n").repeat(200)

        val pieces = SpeechChunker.split(text)

        assertTrue(pieces.all { it.length <= SpeechChunker.PLATFORM_LIMIT })
        assertTrue(pieces.size > 1)
    }

    @Test
    fun `a zero or negative limit produces nothing rather than looping`() {
        assertTrue(SpeechChunker.split("some text", limit = 0).isEmpty())
        assertTrue(SpeechChunker.split("some text", limit = -1).isEmpty())
    }

    @Test
    fun `whitespace left at a boundary is not carried into a piece`() {
        val text = ("word. ").repeat(2000)

        val pieces = SpeechChunker.split(text)

        pieces.forEach { piece ->
            assertEquals(piece, piece.trim())
        }
    }
}
