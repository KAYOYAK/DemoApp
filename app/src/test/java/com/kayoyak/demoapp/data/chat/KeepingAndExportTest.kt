package com.kayoyak.demoapp.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeping an answer, and writing a conversation out as Markdown.
 *
 * Both are ways of taking something out of the app: one keeps it where it is, the
 * other hands it to somewhere else. The failures that matter are the quiet ones --
 * a bookmark that does not survive, an export that silently drops what made the
 * answer worth keeping.
 */
class KeepingAndExportTest {

    /**
     * Copy for the export. The labels are injected rather than held in the export
     * layer, so the test supplies its own and can assert on them -- which is also
     * what proves the injection works: a hardcoded string in `toMarkdown` would
     * survive this.
     */
    private val labels = MarkdownLabels(
        model = "model:",
        tokens = "tokens:",
        rate = { "%.1f tok/s".format(it) },
        clock = { "12:00" },
    )

    private fun message(
        role: MessageRole,
        text: String,
        at: Long = 1_000,
        modelId: String? = null,
    ) = ChatMessage(
        id = "m$at",
        role = role,
        text = text,
        createdAt = at,
        modelId = modelId,
    )

    private fun conversation(vararg messages: ChatMessage) = Conversation(
        id = "c1",
        title = "T",
        modelId = "qwen3-0.6b-int4",
        createdAt = 1_000,
        updatedAt = 2_000,
        messages = messages.toList(),
    )

    // ------------------------------------------------------------- keeping ---

    @Test
    fun `keeping marks the message with when it was kept`() {
        val kept = message(MessageRole.ASSISTANT, "an answer").withKeeping(keep = true, at = 5_000)

        assertEquals(5_000L, kept.keptAt)
    }

    @Test
    fun `unkeeping removes the mark`() {
        val kept = message(MessageRole.ASSISTANT, "an answer").withKeeping(keep = true, at = 5_000)

        assertNull(kept.withKeeping(keep = false, at = 6_000).keptAt)
    }

    @Test
    fun `keeping does not change the text`() {
        // A bookmark that edited the thing it marked would be a different feature.
        val original = message(MessageRole.ASSISTANT, "an answer")

        assertEquals(original.text, original.withKeeping(keep = true, at = 5_000).text)
    }

    @Test
    fun `a kept message survives a write and a read`() {
        val stored = conversation(
            message(MessageRole.USER, "a question", at = 1_000),
            message(MessageRole.ASSISTANT, "an answer", at = 2_000).withKeeping(keep = true, at = 3_000),
        )

        val text = ConversationBackupCodec.encode(listOf(stored), now = 9_000)

        val read = ConversationBackupCodec.decode(text).let {
            (it as ConversationBackupCodec.ReadResult.Ok).conversations.single()
        }

        assertEquals(3_000L, read.messages[1].keptAt)
    }

    @Test
    fun `a file written before keeping existed still loads`() {
        // The field is new. A conversation saved without it has no `keptAt` key, and
        // refusing it would lose the conversation to keep a tidier schema.
        // The real envelope, with a message that has no `keptAt` key at all -- which
        // is what every conversation saved before this feature looks like.
        val text =
            """{"version":1,"exportedAt":9,"conversations":[{"id":"old","title":"T",""" +
                """"modelId":"m","createdAt":1,"updatedAt":2,"messages":[""" +
                """{"id":"m1","role":"ASSISTANT","text":"x","createdAt":3}]}]}"""

        val read = ConversationBackupCodec.decode(text).let {
            (it as ConversationBackupCodec.ReadResult.Ok).conversations.single()
        }

        assertNull(read.messages[0].keptAt)
    }

    // ------------------------------------------------------------ markdown ---

    @Test
    fun `a question is a heading and an answer is a smaller one`() {
        val markdown = conversation(
            message(MessageRole.USER, "什么是光合作用"),
            message(MessageRole.ASSISTANT, "把光变成化学能"),
        ).toMarkdown(labels)

        assertTrue(markdown.contains("## 什么是光合作用"))
        assertTrue(markdown.contains("### 把光变成化学能"))
    }

    @Test
    fun `markdown in the reply is not escaped away`() {
        // The conversation already is Markdown -- the model writes it, the app
        // renders it. Stripping the formatting to produce an export loses the
        // tables and code blocks that were the reason to keep the answer.
        val body = "| a | b |\n|---|---|\n| 1 | 2 |\n\n```kotlin\nval x = 1\n```"

        val markdown = conversation(message(MessageRole.ASSISTANT, body)).toMarkdown(labels)

        assertTrue("The table survives", markdown.contains("| a | b |"))
        assertTrue("The code block survives", markdown.contains("```kotlin"))
    }

    @Test
    fun `the blockquote under a reply uses the labels it was given`() {
        // The test's own labels above happen to read like the literals this used to
        // hold, so they cannot tell injection from a hardcoded string. These can:
        // every word here is distinctive, and if any part of the export goes back to
        // writing its own copy, one of these assertions fails and says which.
        val unusual = MarkdownLabels(
            model = "<<model>>",
            tokens = "<<tokens>>",
            rate = { "<<rate " + it + ">>" },
            clock = { "<<clock>>" },
        )

        val markdown = conversation(
            message(MessageRole.ASSISTANT, "an answer", modelId = "qwen3-1.7b"),
        ).toMarkdown(unusual)

        assertTrue("The model label is the caller's", markdown.contains("<<model>> qwen3-1.7b"))
        assertTrue("The clock is the caller's", markdown.contains("<<clock>>"))
        assertTrue(
            "No copy of this layer's own reached the file",
            !markdown.contains("model:") && !markdown.contains("tok/s"),
        )
    }

    @Test
    fun `an answer says which model produced it`() {
        // So an answer copied out of the app can still say where it came from.
        val markdown = conversation(
            message(MessageRole.ASSISTANT, "an answer", modelId = "qwen3-1.7b"),
        ).toMarkdown(labels)

        assertTrue(markdown.contains("qwen3-1.7b"))
    }

    @Test
    fun `nothing that was not said is added`() {
        // No header, no footer, no name of this app. An export that introduces
        // itself is one that has to be edited before it can be used.
        val markdown = conversation(message(MessageRole.USER, "a question")).toMarkdown(labels)

        assertTrue(markdown.startsWith("## a question"))
        assertTrue(markdown.none { it == '\uFEFF' })
    }

    @Test
    fun `an empty conversation exports as nothing much, not as a crash`() {
        val markdown = conversation().toMarkdown(labels)

        assertEquals("\n", markdown)
    }

    @Test
    fun `a kept answer is ordinary markdown`() {
        // Keeping is a fact about the app's own list, not about the content. An
        // export that marked it would be telling the reader something they did not
        // ask to be told.
        val markdown = conversation(
            message(MessageRole.ASSISTANT, "an answer").withKeeping(keep = true, at = 5_000),
        ).toMarkdown(labels)

        assertTrue(markdown.contains("an answer"))
        assertTrue(!markdown.contains("5000"))
    }
}
