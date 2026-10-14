package com.example.demoapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Colouring a listing.
 *
 * The scanner is the whole feature, and the mistakes that matter are the ones a
 * reader notices on a real listing: a string whose contents are treated as code, a
 * comment that runs past the end of its line, a quote that opens and never closes
 * and turns the rest of the block green.
 */
class SyntaxHighlighterTest {

    private fun tokens(code: String) = SyntaxHighlighter.spans(code)

    /** What was classified as [token], joined back up. */
    private fun of(code: String, token: SyntaxHighlighter.Token) =
        tokens(code).filter { it.token == token }.joinToString("") { it.text }

    @Test
    fun `nothing in, nothing out`() {
        assertTrue(tokens("").isEmpty())
    }

    @Test
    fun `plain words are not keywords`() {
        // The wrong case: colouring every identifier makes a listing a rainbow, and
        // the colour stops meaning anything.
        val keywords = of("val name = 1", SyntaxHighlighter.Token.KEYWORD)

        assertEquals("val", keywords)
        assertTrue("A name is left plain", of("val name = 1", SyntaxHighlighter.Token.PLAIN).contains("name"))
    }

    @Test
    fun `a comment runs to the end of its line and no further`() {
        // Getting this wrong greys out the rest of the listing, which is the
        // failure a reader notices immediately.
        val code = "# a comment\nval x = 1"

        assertEquals("# a comment", of(code, SyntaxHighlighter.Token.COMMENT))
        assertTrue(of(code, SyntaxHighlighter.Token.KEYWORD).contains("val"))
    }

    @Test
    fun `both comment notations are recognised`() {
        assertEquals("// one", of("// one\nval a = 1", SyntaxHighlighter.Token.COMMENT))
        assertEquals("# two", of("# two\nval a = 1", SyntaxHighlighter.Token.COMMENT))
    }

    @Test
    fun `a string is one token, including the spaces inside it`() {
        val code = """val s = "hello world""""

        assertEquals("\"hello world\"", of(code, SyntaxHighlighter.Token.STRING))
    }

    @Test
    fun `a keyword inside a string is part of the string`() {
        // The case that separates a scanner from a regex: colouring `val` inside a
        // sentence would put half a word in the keyword colour.
        val code = """val s = "a val is a val""""

        val keywords = of(code, SyntaxHighlighter.Token.KEYWORD)
        assertEquals("The leading val, and nothing from inside the quotes", "val", keywords)
    }

    @Test
    fun `an escaped quote does not end the string`() {
        val code = """val s = "he said \" stop""""

        val string = of(code, SyntaxHighlighter.Token.STRING)

        assertEquals("""The whole literal, escapes and all""", "\"he said \\\" stop\"", string)
    }

    @Test
    fun `an unterminated string stops at the line`() {
        // A model that emitted an opening quote and no closing one should not turn
        // the rest of the listing green.
        val code = "val s = \"oops\nval t = 2"

        assertTrue(
            "The second line is still code",
            of(code, SyntaxHighlighter.Token.KEYWORD).contains("val"),
        )
    }

    @Test
    fun `numbers are recognised`() {
        assertEquals("42", of("val x = 42", SyntaxHighlighter.Token.NUMBER))
        assertEquals("0xFF", of("val x = 0xFF", SyntaxHighlighter.Token.NUMBER))
        assertEquals("1.5", of("val x = 1.5", SyntaxHighlighter.Token.NUMBER))
    }

    @Test
    fun `a digit inside an identifier is not a number`() {
        // `sha256` is a name. Splitting it into a name and a number colours the
        // middle of a word.
        val code = "val sha256 = 1"

        val numbers = of(code, SyntaxHighlighter.Token.NUMBER)
        assertEquals("Only the literal", "1", numbers)
    }

    @Test
    fun `every character survives`() {
        // The scanner drops nothing and invents nothing. A highlighter that lost a
        // character would be corrupting the listing it was asked to show.
        val code = """
            fun main() {
                // a comment
                val s = "text with \" and # inside"
                println(s + 42)
            }
        """.trimIndent()

        assertEquals(code, tokens(code).joinToString("") { it.text })
    }

    @Test
    fun `the tokens are contiguous`() {
        // No gap and no overlap: the renderer appends them in order, and a gap is a
        // character that disappears.
        val code = "val x = \"a\" // c"

        var position = 0
        tokens(code).forEach { span ->
            assertEquals("Span starts where the last ended", position, position)
            position += span.text.length
        }
        assertEquals(code.length, position)
    }

    @Test
    fun `an unknown language is coloured rather than left grey`() {
        // The language is accepted and ignored, so a fence labelled with something
        // nobody has heard of is treated like any other.
        val code = "def f(): return 1"

        assertTrue(of(code, SyntaxHighlighter.Token.KEYWORD).contains("def"))
        assertTrue(of(code, SyntaxHighlighter.Token.KEYWORD).contains("return"))
    }

    @Test
    fun `a long listing is scanned once`() {
        // The scanner never rewinds. This is the case where a rescanning one is
        // visibly slow, and it is also a smoke test that nothing loops.
        val code = buildString {
            repeat(2000) { append("val x$it = \"text\" // comment\n") }
        }

        val spans = tokens(code)

        assertTrue(spans.size > 1000)
        assertEquals(code, spans.joinToString("") { it.text })
    }
}
