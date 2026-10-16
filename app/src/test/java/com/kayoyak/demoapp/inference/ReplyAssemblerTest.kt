package com.kayoyak.demoapp.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins down how the runtime's stream of fragments becomes a reply.
 *
 * The case these exist for is the one an earlier implementation got wrong:
 * fragments that happen to look like a growing whole. Sub-word tokenisation
 * produces exactly that shape by accident, so the failure is not exotic.
 */
class ReplyAssemblerTest {

    @Test
    fun `fragments concatenate into the whole reply`() {
        val assembler = ReplyAssembler()
        listOf("Sure", ",", " here", "'s", " the", " code", ":").forEach { assembler.add(it) }

        assertEquals("Sure, here's the code:", assembler.text)
        assertEquals(7, assembler.fragmentCount)
    }

    @Test
    fun `a fragment that begins with the text so far is not treated as cumulative`() {
        // The exact sequence the old heuristic dropped text on: after "a", the
        // fragment "abc" begins with "a" and is longer, so it was read as "the
        // whole reply so far" and only "bc" was emitted.
        val assembler = ReplyAssembler()
        val emitted = buildList {
            add(assembler.add("a"))
            add(assembler.add("abc"))
        }

        assertEquals(listOf("a", "abc"), emitted)
        assertEquals("Every fragment must be emitted as-is", "aabc", assembler.text)
    }

    @Test
    fun `a reply that grows by whole words keeps every character`() {
        // The other shape the old heuristic mishandled: each emission is the
        // complete text so far. Both readings must produce the emitted text the
        // caller sees; only the runtime's own final text is authoritative.
        val assembler = ReplyAssembler()
        listOf("The", " sky", " is").forEach { assembler.add(it) }

        assertEquals("The sky is", assembler.text)
    }

    @Test
    fun `empty fragments are ignored and not counted`() {
        val assembler = ReplyAssembler()
        assembler.add("")
        assembler.add("hi")

        assertEquals("hi", assembler.text)
        assertEquals("An empty fragment is not a token", 1, assembler.fragmentCount)
    }

    @Test
    fun `a fresh assembler reports nothing`() {
        val assembler = ReplyAssembler()
        assertTrue(assembler.isEmpty)
        assertEquals("", assembler.text)
        assertEquals(0, assembler.fragmentCount)
    }

    @Test
    fun `channel text becomes the reasoning trace, never the answer`() {
        val assembler = ReplyAssembler()
        assembler.add("1 2 3")
        assembler.addChannelText("Okay, the user wants me to count")
        assembler.add(" 4 5 6")

        assertEquals("1 2 3 4 5 6", assembler.text)
        assertEquals("Okay, the user wants me to count", assembler.reasoning)
        assertEquals(3, assembler.fragmentCount)
        assertFalse(assembler.reasoningWithoutAnswer)
    }

    @Test
    fun `a reply that arrives only in a channel is a trace and no answer`() {
        // What the samples of the catalogue's own model looked like from outside: text
        // on a channel, nothing in the answer, so six runs in a row read as a model that
        // generates nothing. The screen has a message for this state, which is why it is
        // worth having a name here.
        val assembler = ReplyAssembler()
        assembler.addChannelText("Thinking")
        assembler.addChannelText(" about it")

        assertTrue("no answer", assembler.text.isEmpty())
        assertEquals("Thinking about it", assembler.reasoning)
        assertTrue("reasoned, and never reached an answer", assembler.reasoningWithoutAnswer)
        assertEquals("a channel fragment is a token", 2, assembler.fragmentCount)
    }

    @Test
    fun `channel text is not scanned for reasoning markers`() {
        // The runtime has already separated this text; scanning it again would search
        // for markers that cannot be there, and a channel ending on the start of one
        // would be held back until the stream ended.
        val assembler = ReplyAssembler()
        assembler.addChannelText("<think>already separated</think>")

        assertEquals("", assembler.text)
        assertEquals("<think>already separated</think>", assembler.reasoning)
    }

    @Test
    fun `an empty channel fragment is ignored and not counted`() {
        val assembler = ReplyAssembler()
        assembler.addChannelText("")
        assembler.add("hi")

        assertEquals("hi", assembler.text)
        assertEquals(1, assembler.fragmentCount)
    }

    @Test
    fun `a long reply is assembled without loss`() {
        // Guards against a truncation bug that only shows up past some size.
        val assembler = ReplyAssembler()
        val fragments = (1..5_000).map { "f$it " }
        fragments.forEach { assembler.add(it) }

        assertEquals(fragments.joinToString(""), assembler.text)
        assertFalse(assembler.isEmpty)
    }
}
