package com.kayoyak.demoapp.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export is the one place the app's contents leave the device, which makes it
 * the one place where what is written matters.
 */
class ConversationExportTest {

    /**
     * Fixed words, so these assertions are about the structure of the export and
     * not about the app's copy. The copy lives in strings.xml and is not this
     * test's business; what is, is that each part lands where it should.
     */
    private val LABELS = ConversationExport.ExportLabels(
        you = "SEP-YOU",
        model = "SEP-MODEL",
        stoppedHere = "SEP-STOPPED",
        footer = "SEP-FOOTER",
        exportedAt = { "SEP-EXPORTED $it" },
    )

    private val conversation = Conversation(
        id = "c1",
        title = "Three primary colours",
        modelId = "smollm2-360m",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
        messages = listOf(
            ChatMessage(
                id = "m1",
                role = MessageRole.USER,
                text = "Name three primary colours",
                createdAt = 1_700_000_000_000L,
            ),
            ChatMessage(
                id = "m2",
                role = MessageRole.ASSISTANT,
                text = "Red, blue and yellow.",
                createdAt = 1_700_000_100_000L,
                elapsedMillis = 4_200L,
            ),
        ),
    )

    @Test
    fun `the text carries the title and every message in order`() {
        val text = ConversationExport.asText(conversation, LABELS, now = 1_700_000_200_000L)

        assertTrue("The title", text.startsWith("Three primary colours"))
        val question = text.indexOf("Name three primary colours")
        val answer = text.indexOf("Red, blue and yellow.")
        assertTrue("Both messages are there", question > 0 && answer > 0)
        assertTrue("In the order they were said", question < answer)
    }

    @Test
    fun `who said what is marked`() {
        val text = ConversationExport.asText(conversation, LABELS)
        assertTrue("The question is the user's", text.contains("SEP-YOU"))
        assertTrue("The answer is not", text.contains("SEP-MODEL"))
    }

    @Test
    fun `an interrupted reply says so`() {
        // A reply that stops mid-sentence looks like a mistake in the export.
        val stopped = conversation.copy(
            messages = conversation.messages + ChatMessage(
                id = "m3",
                role = MessageRole.ASSISTANT,
                text = "The three primary colours are",
                createdAt = 1_700_000_200_000L,
                interrupted = true,
            ),
        )

        val text = ConversationExport.asText(stopped, LABELS)
        assertTrue(text.contains("SEP-STOPPED"))
    }

    @Test
    fun `the export says it was made on the device`() {
        // The reader of a shared chat has no way to know where it came from, and
        // the claim is the point of the app.
        val text = ConversationExport.asText(conversation, LABELS)
        assertTrue("The footer must be the export's own, not a literal", text.contains("SEP-FOOTER"))
    }

    @Test
    fun `a file name is safe and readable`() {
        val name = ConversationExport.fileNameFor(conversation)
        assertEquals("Three-primary-colours.txt", name)
        assertFalse("No separators", name.contains('/'))
        assertFalse("No separators", name.contains('\\'))
    }

    @Test
    fun `an awkward title does not produce an empty or escaping file name`() {
        assertEquals(
            "A title of only punctuation still names a file",
            "conversation.txt",
            ConversationExport.fileNameFor(conversation.copy(title = "///")),
        )
        val traversal = ConversationExport.fileNameFor(conversation.copy(title = "../../../etc/passwd"))
        assertFalse(traversal.contains(".."))
        assertFalse(traversal.contains('/'))
    }

    @Test
    fun `a very long title is bounded`() {
        val name = ConversationExport.fileNameFor(conversation.copy(title = "x".repeat(500)))
        assertTrue("A file name has limits", name.length <= 44)
    }
}
