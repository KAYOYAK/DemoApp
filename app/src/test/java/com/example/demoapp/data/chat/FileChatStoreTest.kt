package com.example.demoapp.data.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileChatStoreTest {

    private lateinit var root: File
    private lateinit var store: FileChatStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("chat-store-test").toFile()
        store = FileChatStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /**
     * A stored conversation whose key is gone is counted, not silently dropped.
     *
     * This is the one path in the store that cannot be reached on the JVM by
     * accident: `AndroidKeyStore` does not exist here, so `FileCipher.key()` returns
     * null, so a file carrying the marker cannot be decrypted -- which is exactly
     * the state a real device reaches when the keystore entry is cleared or the app
     * is restored onto another machine.
     *
     * Without the count the only observable outcome is that a conversation is not
     * in the list, which is what a deleted one looks like.
     */
    @Test
    fun `a conversation that cannot be decrypted is counted rather than dropped`(): Unit = runBlocking {
        store.save(conversation("c1", "keep me"))

        assertFalse("the fixture must produce a readable file", runBlocking { store.list() }.isEmpty())
        assertEquals("nothing is unreadable yet", 0, store.unreadable)

        // Mark the stored bytes as ciphertext without changing them otherwise. The
        // marker is the file's own declaration that it is encrypted; a key that
        // cannot open it is the case under test.
        val file = File(root, "c1.json")
        val original = file.readBytes()
        file.writeBytes(byteArrayOf(0x4E, 0x55, 0x4C, 0x4C, 0x01) + original)

        val listed = store.list()

        assertTrue("an unreadable file must not appear as a conversation", listed.isEmpty())
        assertEquals("and it must be reported", 1, store.unreadable)
    }

    private fun conversation(id: String, text: String, updatedAt: Long = 1000L) = Conversation(
        id = id,
        title = Conversation.titleFrom(text),
        modelId = "smollm2-360m",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        messages = listOf(
            ChatMessage("m1", MessageRole.USER, text, updatedAt),
            ChatMessage("m2", MessageRole.ASSISTANT, "a reply", updatedAt + 1, modelId = "smollm2-360m"),
        ),
    )

    @Test
    fun `a saved conversation comes back intact`() = runBlocking {
        val original = conversation("c1", "hello there")
        store.save(original)

        val loaded = store.load("c1")

        assertEquals(original, loaded)
    }

    @Test
    fun `an unknown id returns null rather than throwing`() = runBlocking {
        assertNull(store.load("does-not-exist"))
    }

    @Test
    fun `listing returns conversations newest first`() = runBlocking {
        store.save(conversation("old", "first", updatedAt = 1_000L))
        store.save(conversation("new", "second", updatedAt = 2_000L))

        val listed = store.list()

        assertEquals(listOf("new", "old"), listed.map { it.id })
    }

    @Test
    fun `delete removes the conversation`() = runBlocking {
        store.save(conversation("gone", "text"))
        assertNotNull(store.load("gone"))

        store.delete("gone")

        assertNull(store.load("gone"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `saving again replaces rather than appends`() = runBlocking {
        val first = conversation("c1", "one")
        store.save(first)
        val grown = first.copy(
            messages = first.messages + ChatMessage("m3", MessageRole.USER, "two", 3_000L),
            updatedAt = 3_000L,
        )

        store.save(grown)

        assertEquals(3, store.load("c1")?.messages?.size)
        assertEquals(
            "One conversation must be one file, not one file per save",
            1,
            root.listFiles()?.count { it.name.endsWith(".json") },
        )
    }

    @Test
    fun `a corrupt file is skipped rather than breaking the list`() = runBlocking {
        // One unreadable file must not make the whole history unusable. This is
        // the reason list() reads defensively instead of letting the parser throw.
        store.save(conversation("good", "fine"))
        File(root, "broken.json").writeText("{ this is not json")

        val listed = store.list()

        assertEquals(listOf("good"), listed.map { it.id })
    }

    @Test
    fun `an unknown field in a stored file does not break loading`() = runBlocking {
        // Forward compatibility: a file written by a newer build must still open
        // in an older one rather than being treated as corrupt.
        store.save(conversation("c1", "hello"))
        val file = File(root, "c1.json")
        file.writeText(file.readText().replaceFirst("{", "{\n  \"somethingNew\": 42,"))

        assertNotNull(store.load("c1"))
    }

    @Test
    fun `an unsafe conversation id is refused`() {
        val error = runCatching {
            runBlocking { store.save(conversation("../../escape", "text")) }
        }.exceptionOrNull()

        assertTrue(
            "A path that escapes the chat directory must be refused, got: $error",
            error is IllegalArgumentException,
        )
    }

    @Test
    fun `concurrent saves of one conversation never leave a corrupt file`() = runBlocking {
        // The ViewModel saves asynchronously from several places, so two writes
        // for the same conversation can overlap. Each write goes through a
        // temporary file; if those temporary files share a name, the writers
        // interleave into one of them and whichever moves it publishes a mixture
        // of both.
        val base = conversation("c1", "start")
        store.save(base)

        // Large payloads so the writes genuinely overlap rather than finishing
        // before the next begins.
        val payload = "x".repeat(200_000)
        val variants = (1..8).map { n ->
            base.copy(
                updatedAt = 1_000L + n,
                messages = base.messages + List(n) {
                    ChatMessage("m$n-$it", MessageRole.USER, payload, 1_000L + it)
                },
            )
        }

        val outcomes = variants.map { variant -> async(Dispatchers.IO) { runCatching { store.save(variant) } } }
            .awaitAll()

        val failures = outcomes.filter { it.isFailure }
        assertTrue(
            "A concurrent save must not fail: ${failures.map { it.exceptionOrNull() }}",
            failures.isEmpty(),
        )

        val loaded = store.load("c1")
        assertNotNull("Concurrent saves must not leave an unparseable file", loaded)
        assertTrue(
            "The file must be exactly one of the versions written, not a mixture of several",
            loaded in variants,
        )

        val leftovers = root.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue("Temporary files must not be left behind: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `no temporary files are left behind after saving`() = runBlocking {
        store.save(conversation("c1", "text"))

        val leftovers = root.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue("Atomic write must clean up after itself: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `titles are derived from the first thing the user said`() {
        assertEquals("What is a haiku?", Conversation.titleFrom("  What is a haiku?  "))
        assertEquals("新对话", Conversation.titleFrom("   "))
        assertEquals(
            "Whitespace inside a title is collapsed so a list row does not wrap",
            "one two three",
            Conversation.titleFrom("one\n\ntwo   three"),
        )

        val long = "x".repeat(200)
        val title = Conversation.titleFrom(long)
        assertTrue("Title must be truncated, got ${title.length} chars", title.length <= Conversation.MAX_TITLE_LENGTH)
        assertTrue("Truncation must be visible", title.endsWith("\u2026"))
    }

    // ------------------------------------------- deleting is not undone ---

    @Test
    fun `a save written before the delete does not bring the conversation back`() = runBlocking {
        // The path this protects: the user deletes a conversation and leaves the
        // app. Leaving saves the chat that was open, and without this the file the
        // user just removed reappears.
        val version = conversation("c1", "hello there", updatedAt = 1_000L)
        store.save(version)

        store.delete("c1")
        // The same version, arriving late.
        store.save(version)

        assertNull("What the user removed must stay removed", store.load("c1"))
        assertEquals(0, store.list().size)
    }

    @Test
    fun `deleting something that was never saved still refuses a late save`() = runBlocking {
        store.delete("never-saved")

        store.save(conversation("never-saved", "hello", updatedAt = 1_000L))

        assertNull(store.load("never-saved"))
    }

    @Test
    fun `continuing a deleted conversation brings it back`() = runBlocking {
        // The other half of the rule. A save whose content is newer than the
        // deletion is the user asking for the conversation again, and refusing
        // that would silently discard a message they watched being written.
        store.save(conversation("c1", "hello", updatedAt = 1_000L))
        store.delete("c1")

        val continued = conversation("c1", "hello", updatedAt = System.currentTimeMillis() + 1_000L)
        store.save(continued)

        assertEquals(
            "Typing again after deleting restores it, with the new content",
            continued,
            store.load("c1"),
        )
    }

    @Test
    fun `deleting one conversation does not silence another`() = runBlocking {
        store.save(conversation("a", "first", updatedAt = 1_000L))
        store.save(conversation("b", "second", updatedAt = 1_000L))

        store.delete("a")
        store.save(conversation("b", "second", updatedAt = 1_000L))

        assertNull(store.load("a"))
        assertNotNull("An unrelated conversation is untouched", store.load("b"))
    }

    @Test
    fun `a conversation restored after a delete can be deleted again`() = runBlocking {
        store.save(conversation("c1", "hello", updatedAt = 1_000L))
        store.delete("c1")
        store.save(conversation("c1", "hello", updatedAt = System.currentTimeMillis() + 1_000L))

        store.delete("c1")
        store.save(conversation("c1", "hello", updatedAt = System.currentTimeMillis() + 1_000L))

        assertNotNull("Continuing after the second delete restores it again", store.load("c1"))
    }

    // ----------------------------------------------------------------- pinning ---

    @Test
    fun `a pinned conversation is listed first`() = runBlocking {
        val older = conversation("older", "older", 1_000L)
        val newer = conversation("newer", "newer", 9_000L)
        store.save(older)
        store.save(newer)

        store.save(older.copy(pinnedAt = 5_000L))

        assertEquals(
            "The pin has to outrank being recent, or it means nothing",
            listOf("older", "newer"),
            store.list().map { it.id },
        )
    }

    @Test
    fun `pinned conversations keep the order they were pinned in`() = runBlocking {
        store.save(conversation("a", "a", 1_000L))
        store.save(conversation("b", "b", 2_000L))
        store.save(conversation("c", "c", 3_000L))

        // Pinned in this order, and then 'a' is used again -- which must not
        // reshuffle the group. A list that rearranges itself under the finger is
        // worse than one in the wrong order.
        store.save(conversation("b", "b", 2_000L).copy(pinnedAt = 100L))
        store.save(conversation("c", "c", 3_000L).copy(pinnedAt = 200L))
        store.save(conversation("a", "a", 99_000L).copy(pinnedAt = 50L))

        assertEquals(listOf("a", "b", "c"), store.list().map { it.id })
    }

    @Test
    fun `unpinning sends it back to where it belongs`() = runBlocking {
        store.save(conversation("older", "older", 1_000L))
        store.save(conversation("newer", "newer", 9_000L))
        store.save(conversation("older", "older", 1_000L).copy(pinnedAt = 5_000L))

        store.save(conversation("older", "older", 1_000L).copy(pinnedAt = null))

        assertEquals(listOf("newer", "older"), store.list().map { it.id })
    }

    @Test
    fun `a conversation written before pinning existed still loads`() = runBlocking {
        // The field is optional for this reason. A history screen that refused to
        // open because one field was missing would lose exactly the conversations
        // a user cares about most.
        val file = File(root, "written-by-an-older-build.json")
        file.writeText(
            """
            {
              "id": "written-by-an-older-build",
              "title": "旧版本写的",
              "modelId": "qwen3-0.6b",
              "createdAt": 1000,
              "updatedAt": 2000,
              "messages": []
            }
            """.trimIndent(),
        )

        val loaded = store.load("written-by-an-older-build")
        assertNotNull("An older file must still parse", loaded)
        assertNull(loaded!!.pinnedAt)
    }

    // ------------------------------------------------------------ archiving ---

    @Test
    fun `an archived conversation is last, not hidden`(): Unit = runBlocking {
        // Hidden would make it indistinguishable from deleted, and the user would
        // have no way to tell which they had done.
        val store = FileChatStore(root)
        store.save(conversation("a", "one", updatedAt = 3_000))
        store.save(conversation("b", "two", updatedAt = 2_000))
        store.save(conversation("c", "three", updatedAt = 1_000).copy(archivedAt = 500))

        val ids = store.list().map { it.id }

        assertEquals(listOf("a", "b", "c"), ids)
    }

    @Test
    fun `an archived conversation does not outrank a pinned one`(): Unit = runBlocking {
        val store = FileChatStore(root)
        store.save(conversation("pinned", "p", updatedAt = 1_000).copy(pinnedAt = 5_000))
        store.save(conversation("archived", "a", updatedAt = 9_000).copy(archivedAt = 9_000))

        val ids = store.list().map { it.id }

        assertEquals("The pin wins", listOf("pinned", "archived"), ids)
    }

    @Test
    fun `archiving survives a write and a read`(): Unit = runBlocking {
        val store = FileChatStore(root)
        store.save(conversation("a", "one").copy(archivedAt = 1_234L))

        assertEquals(1_234L, store.load("a")!!.archivedAt)
    }

    @Test
    fun `a file written before archiving existed still loads`(): Unit = runBlocking {
        // The field is new. An older conversation has no `archivedAt` key at all,
        // and refusing it would lose the conversation to keep a tidier schema.
        val file = java.io.File(root, "old.json")
        root.mkdirs()
        file.writeText(
            """{"id":"old","title":"T","modelId":"m","createdAt":1,"updatedAt":2,"messages":[]}""",
        )

        assertEquals(null, FileChatStore(root).load("old")?.archivedAt)
    }

    // -------------------------------------------------------------- restore ---

    @Test
    fun `restoring brings back a conversation that is not here`() = runBlocking {
        val report = store.restore(listOf(conversation("c1", "from the backup")))

        assertEquals(1, report.added)
        assertEquals(0, report.skipped)
        assertTrue("nothing failed: ${report.failed}", report.failed.isEmpty())
        assertEquals("from the backup", store.load("c1")?.messages?.first()?.text)
    }

    @Test
    fun `restoring never overwrites a conversation that is already here`() = runBlocking {
        // The rule this store exists to get right. A backup is older than the device
        // by definition -- the phone keeps being used after it was written -- so
        // replacing what is here with what the file holds would trade the newer
        // conversation for the older one, silently, on the one screen a user opens
        // to get their data back.
        //
        // The store keeps the device's copy and counts the skip, which is what the
        // screen then reports as "already on this device".
        store.save(conversation("c1", "newer, said on the phone", updatedAt = 9_000))

        val report =
            store.restore(listOf(conversation("c1", "older, from the backup", updatedAt = 1_000)))

        assertEquals(0, report.added)
        assertEquals(1, report.skipped)
        assertEquals(
            "the device's copy must survive",
            "newer, said on the phone",
            store.load("c1")!!.messages.first().text,
        )
    }

    @Test
    fun `restoring names an id it cannot store rather than dropping it quietly`() = runBlocking {
        // A user counting what came back deserves to know which one did not.
        val report =
            store.restore(
                listOf(
                    conversation("fine", "ok"),
                    conversation("../escape", "no"),
                ),
            )

        assertEquals(1, report.added)
        assertEquals(listOf("../escape"), report.failed)
        // Nothing may be written outside the store. Asserted against the directory
        // itself rather than through `load`, which refuses an id it cannot turn into
        // a filename before it ever reaches the disk.
        val written = root.listFiles().orEmpty().map { it.name }
        assertEquals("only the safe one was written: $written", listOf("fine.json"), written)
    }

    @Test
    fun `a restore counts each outcome separately`() = runBlocking {
        store.save(conversation("here", "already"))

        val report =
            store.restore(
                listOf(
                    conversation("here", "already"),
                    conversation("fresh", "new"),
                    conversation("bad/id", "no"),
                ),
            )

        assertEquals(1, report.added)
        assertEquals(1, report.skipped)
        assertEquals(listOf("bad/id"), report.failed)
    }

    /**
     * The audit counts the decision [FileChatStore.save] made.
     *
     * These four counts exist so a caller can tell a write that was refused from
     * a write that never happened, and the only reader is the timeout message in
     * `ChatViewModelTest.awaitSaved` -- a place that runs exactly when something is
     * already wrong. That is the worst possible place for a diagnostic to be the
     * first thing to find out it is broken, and it happened: the four counters were
     * declared, documented, and never incremented, so every failure reported
     * `attempted=0 written=0`, which reads as "the store was never asked" and sent
     * three separate investigations upstream of the store for a fault that had not
     * been located at all.
     */
    @Test
    fun `the save audit counts what save decided`() = runBlocking {
        val before = store.saveAudit()
        assertEquals("a store that has done nothing has counted nothing", 0, before.attempted)

        store.save(conversation("c1", "hello", updatedAt = 1_000L))

        val written = store.saveAudit()
        assertEquals(1, written.attempted)
        assertEquals(1, written.written)
        assertEquals(0, written.refusedAsOlder)
        assertEquals(0, written.refusedAsDeleted)

        // An older version of a conversation already on disk. Counted even though
        // nothing is written: this is the difference the audit was added for.
        store.save(conversation("c1", "stale", updatedAt = 1_000L).copy(updatedAt = 500L))

        val refused = store.saveAudit()
        assertEquals("both calls reached the store", 2, refused.attempted)
        assertEquals("and only the first wrote", 1, refused.written)
        assertEquals(1, refused.refusedAsOlder)
        assertEquals(0, refused.refusedAsDeleted)
    }

    @Test
    fun `the save audit tells a tombstone refusal from a write`() = runBlocking {
        store.save(conversation("gone", "text", updatedAt = 1_000L))
        store.delete("gone")

        // The timestamp is the one the user deleted, so this is the save that
        // arrives too late. The file stays gone and the count says why.
        store.save(conversation("gone", "text", updatedAt = 1_000L))

        val refused = store.saveAudit()
        assertEquals(2, refused.attempted)
        assertEquals("the first save wrote, the second was refused", 1, refused.written)
        assertEquals(1, refused.refusedAsDeleted)
        assertNull("and the conversation stays deleted", store.load("gone"))

        // A version genuinely newer than the deletion is written, and the
        // tombstone has done its job. Both halves are asserted because a store
        // that refused everything after a delete would pass the first half.
        val newer = System.currentTimeMillis() + 1_000L
        store.save(conversation("gone", "text", updatedAt = newer))

        val after = store.saveAudit()
        assertEquals(3, after.attempted)
        assertEquals(2, after.written)
        assertEquals(1, after.refusedAsDeleted)
        assertNotNull("continuing a deleted conversation brings it back", store.load("gone"))
    }
}