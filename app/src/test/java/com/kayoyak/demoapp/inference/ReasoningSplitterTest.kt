package com.kayoyak.demoapp.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reasoning splitter, tested by the cases that make it worth having.
 *
 * Every test here corresponds to a way a naive implementation fails. A per-fragment
 * `replace` passes the obvious case and fails almost all of these, so the plain
 * case is the least interesting one.
 */
class ReasoningSplitterTest {

    /** Feeds [fragments] and returns what the caller would have shown, joined. */
    private fun visible(vararg fragments: String): String {
        val splitter = ReasoningSplitter()
        val shown = StringBuilder()
        fragments.forEach { shown.append(splitter.accept(it)) }
        shown.append(splitter.finish())
        return shown.toString()
    }

    private fun trace(vararg fragments: String): String {
        val splitter = ReasoningSplitter()
        fragments.forEach { splitter.accept(it) }
        splitter.finish()
        return splitter.trace
    }

    @Test
    fun `a block in one fragment is separated`() {
        assertEquals("42.", visible("<think>6 times 7 is 42</think>42."))
        assertEquals("6 times 7 is 42", trace("<think>6 times 7 is 42</think>42."))
    }

    @Test
    fun `a marker split across fragments still matches`() {
        // The case a per-fragment replace cannot see: neither half contains the
        // whole tag, so both halves would be shown to the user.
        assertEquals(
            "42.",
            visible("<thi", "nk>", "6 times 7 is 42", "</thi", "nk>", "42."),
        )
    }

    @Test
    fun `a marker split one character at a time still matches`() {
        val text = "<think>secret</think>answer"
        assertEquals("answer", visible(*text.map { it.toString() }.toTypedArray()))
        assertEquals("secret", trace(*text.map { it.toString() }.toTypedArray()))
    }

    @Test
    fun `a near miss is released rather than swallowed`() {
        // `<thinking out loud>` begins with `<think` and is not the tag. A prefix
        // check that is too eager holds it forever and the reply loses its start.
        assertEquals(
            "<thinking out loud>",
            visible("<thinking out loud>"),
        )
    }

    @Test
    fun `a near miss arriving in two fragments is still released`() {
        // The trap: `<thi` is held because it could become `<think>`. The next
        // fragment rules that out, and everything held has to come back out.
        assertEquals("<thinking out loud>", visible("<thi", "nking out loud>"))
    }

    @Test
    fun `an unterminated block is never shown as the answer`() {
        // The model was still reasoning when it stopped. Half a thought is not an
        // answer, and showing it is the bug this class exists to prevent.
        val splitter = ReasoningSplitter()
        val shown = StringBuilder()
        shown.append(splitter.accept("<think>I am still working on"))
        shown.append(splitter.finish())

        assertEquals("", shown.toString())
        assertEquals("I am still working on", splitter.trace)
        assertTrue(splitter.reasoningWithoutAnswer)
    }

    @Test
    fun `several blocks are all separated`() {
        assertEquals(
            "first second",
            visible("<think>one</think>first <think>two</think>second"),
        )
    }

    @Test
    fun `text before the block is kept`() {
        assertEquals("Here: 42.", visible("Here: <think>working</think>42."))
    }

    @Test
    fun `the gemma channel pair is separated too`() {
        assertEquals("answer", visible("<|channel>thought<channel|>answer"))
    }

    @Test
    fun `a longer tag is not matched as a shorter one`() {
        // `<thinking>` starts with `<think`; matching the shorter marker would leave
        // the `ing>` outside the block and print it as the answer.
        assertEquals("answer", visible("<thinking>hmm</thinking>answer"))
    }

    @Test
    fun `text that merely contains a tag character is untouched`() {
        assertEquals("a < b and c > d", visible("a < b and c > d"))
        assertEquals("use <think as a literal", visible("use <think as a literal"))
    }

    @Test
    fun `empty fragments do not change anything`() {
        assertEquals("answer", visible("", "<think>", "", "x", "", "</think>", "", "answer", ""))
    }

    @Test
    fun `chinese reasoning is separated the same way`() {
        // The app is Chinese-first; a splitter that only handled ASCII would show
        // the whole thought on every reply from the models this app ships.
        assertEquals("四十二。", visible("<think>六乘七等于四十二</think>四十二。"))
        assertEquals("六乘七等于四十二", trace("<think>六乘七等于四十二</think>四十二。"))
    }

    @Test
    fun `an answer with no block at all is unchanged`() {
        assertEquals("just an answer", visible("just an answer"))
        assertEquals("", trace("just an answer"))
    }

    @Test
    fun `the visible text never contains the tag`() {
        // The property rather than an example: whatever else happens, the marker
        // must not reach the user.
        val fragments = listOf("<", "t", "h", "i", "n", "k", ">", "x", "<", "/", "t", "h", "i", "n", "k", ">", "y")
        val shown = visible(*fragments.toTypedArray())
        assertFalse(shown.contains("<think>"))
        assertFalse(shown.contains("</think>"))
        assertEquals("y", shown)
    }

    @Test
    fun `nothing is lost when there is no marker`() {
        // The other property: a splitter that holds back text must give all of it
        // back when no marker ever arrives. Losing a character here would be a
        // truncated reply with no explanation.
        val text = "The quick brown fox jumps over the lazy dog. 0123456789."
        val splitter = ReasoningSplitter()
        val shown = StringBuilder()
        text.forEach { shown.append(splitter.accept(it.toString())) }
        shown.append(splitter.finish())

        assertEquals(text, shown.toString())
    }
}
