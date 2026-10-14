package com.example.demoapp.data.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup is the only thing standing between a user and losing every conversation
 * they have had. What is tested here is not that the format round-trips -- that is
 * the easy half -- but that the ways it can go wrong produce an answer rather than
 * silence.
 */
class ConversationBackupTest {

    private fun conversation(id: String, text: String, updatedAt: Long = 1_000L) = Conversation(
        id = id,
        title = Conversation.titleFrom(text),
        modelId = "qwen3-0.6b",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        messages = listOf(
            ChatMessage("m1", MessageRole.USER, text, updatedAt),
            ChatMessage("m2", MessageRole.ASSISTANT, "a reply", updatedAt + 1, modelId = "qwen3-0.6b"),
        ),
    )

    @Test
    fun `a backup round-trips every field a conversation is judged by`() {
        // Not just the text. Which model said what, when, and whether a reply was
        // cut off are the things that make a restored conversation the same one --
        // a restore that loses them hands back a plausible transcript that is not
        // the user's.
        val original = conversation("c1", "hello").copy(
            pinnedAt = 4_242L,
            messages = listOf(
                ChatMessage("m1", MessageRole.USER, "hello", 1_000L),
                ChatMessage("m2", MessageRole.ASSISTANT, "a reply", 1_001L, modelId = "m", interrupted = true),
            ),
        )

        val restored = ConversationBackupCodec.decode(
            ConversationBackupCodec.encode(listOf(original)),
        )
        val back = (restored as ConversationBackupCodec.ReadResult.Ok).conversations.single()

        assertEquals(original.id, back.id)
        assertEquals(original.title, back.title)
        assertEquals(original.modelId, back.modelId)
        assertEquals(original.createdAt, back.createdAt)
        assertEquals(original.updatedAt, back.updatedAt)
        assertEquals(original.pinnedAt, back.pinnedAt)
        assertEquals(original.messages, back.messages)
    }

    @Test
    fun `a backup from a newer build is refused by name`() {
        // Half-parsing it would produce a conversation that is subtly missing
        // messages, and the user has no way to tell that from having lost them.
        val text = """{"version": 99, "exportedAt": 1, "conversations": []}"""

        val result = ConversationBackupCodec.decode(text)
        assertTrue(result is ConversationBackupCodec.ReadResult.TooNew)
        assertEquals(99, (result as ConversationBackupCodec.ReadResult.TooNew).version)
    }

    @Test
    fun `this build's own version is not refused`() {
        val text = """
            {"version": ${ConversationBackup.CURRENT_VERSION}, "exportedAt": 1, "conversations": []}
        """.trimIndent()
        assertTrue(
            "The version check must not reject the version it writes",
            ConversationBackupCodec.decode(text) is ConversationBackupCodec.ReadResult.Ok,
        )
    }

    @Test
    fun `a file that is not a backup says so rather than throwing`() {
        // The user picked this file. "That is not one of ours" is an answer; an
        // exception on the history screen is a crash.
        val result = ConversationBackupCodec.decode("this is somebody's shopping list")
        assertTrue(result is ConversationBackupCodec.ReadResult.Unreadable)
    }

    @Test
    fun `an empty file is unreadable rather than an empty restore`() {
        val result = ConversationBackupCodec.decode("")
        assertTrue(result is ConversationBackupCodec.ReadResult.Unreadable)
    }

    @Test
    fun `a conversation with no messages is dropped from a restore`() {
        // A row that opens to nothing is worse than not being there. Dropped, and
        // the rest of the backup still comes back -- half a backup is worth more
        // than none.
        val text = """
            {
              "version": 1,
              "exportedAt": 1,
              "conversations": [
                {"id":"empty","title":"Empty","modelId":"m","createdAt":1,"updatedAt":1,"messages":[]},
                {"id":"real","title":"Real","modelId":"m","createdAt":1,"updatedAt":1,
                 "messages":[{"id":"m1","role":"USER","text":"hi","createdAt":1}]}
              ]
            }
        """.trimIndent()

        val result = ConversationBackupCodec.decode(text)
        val ok = result as ConversationBackupCodec.ReadResult.Ok
        assertEquals(listOf("real"), ok.conversations.map { it.id })
    }

    @Test
    fun `the empty conversations that were dropped are counted`() {
        // Dropped silently, they are indistinguishable from conversations the user
        // never had: the restore simply brings back fewer than the file holds.
        // `ChatStore.restore` cannot report this one -- an empty conversation is
        // filtered out while the file is being read and never reaches it -- so the
        // count has to come from here or nobody says it.
        val text = """
            {
              "version": 1,
              "exportedAt": 1,
              "conversations": [
                {"id":"e1","title":"E","modelId":"m","createdAt":1,"updatedAt":1,"messages":[]},
                {"id":"e2","title":"E","modelId":"m","createdAt":1,"updatedAt":1,"messages":[]},
                {"id":"real","title":"R","modelId":"m","createdAt":1,"updatedAt":1,
                 "messages":[{"id":"m1","role":"USER","text":"hi","createdAt":1}]}
              ]
            }
        """.trimIndent()

        val ok = ConversationBackupCodec.decode(text) as ConversationBackupCodec.ReadResult.Ok

        assertEquals(2, ok.emptyDropped)
        assertEquals(listOf("real"), ok.conversations.map { it.id })
    }

    @Test
    fun `a backup holding only empty conversations reports how many`() {
        // The two "nothing came back" answers mean different things to someone who
        // just picked a file: a backup with no usable conversations is a damaged or
        // foreign file, and one holding only empty rows is a backup from which
        // nothing was lost. Telling them apart needs the count to be exactly the
        // number of empties rather than a flag.
        val text = """
            {
              "version": 1,
              "exportedAt": 1,
              "conversations": [
                {"id":"e1","title":"E","modelId":"m","createdAt":1,"updatedAt":1,"messages":[]}
              ]
            }
        """.trimIndent()

        val ok = ConversationBackupCodec.decode(text) as ConversationBackupCodec.ReadResult.Ok

        assertTrue("nothing usable survived", ok.conversations.isEmpty())
        assertEquals(1, ok.emptyDropped)
    }

    @Test
    fun `an unknown field does not stop a restore`() {
        // A backup written by a build that added a field still restores what this
        // one understands, rather than being refused whole.
        val text = """
            {
              "version": 1,
              "exportedAt": 1,
              "somethingNewer": true,
              "conversations": [
                {"id":"c1","title":"T","modelId":"m","createdAt":1,"updatedAt":1,
                 "unknownField": 7,
                 "messages":[{"id":"m1","role":"USER","text":"hi","createdAt":1}]}
              ]
            }
        """.trimIndent()

        val result = ConversationBackupCodec.decode(text)
        assertTrue(result is ConversationBackupCodec.ReadResult.Ok)
        assertEquals(1, (result as ConversationBackupCodec.ReadResult.Ok).conversations.size)
    }

    @Test
    fun `the encoded form is valid JSON on its own terms`() {
        // Not decoration: a backup the user pastes somewhere is read by a human and
        // by another copy of this app, and a format that is only readable by the
        // exact code that wrote it is one bad release from being lost.
        val text = ConversationBackupCodec.encode(listOf(conversation("c1", "hello")))
        val parsed = Json.parseToJsonElement(text)
        assertTrue(parsed.toString().contains("conversations"))
    }
}
