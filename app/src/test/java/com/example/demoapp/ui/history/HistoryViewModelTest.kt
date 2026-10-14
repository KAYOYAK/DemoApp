package com.example.demoapp.ui.history

import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

import com.example.demoapp.data.chat.ConversationExport

import com.example.demoapp.data.chat.ChatMessage
import com.example.demoapp.data.chat.Conversation
import com.example.demoapp.data.chat.FileChatStore
import com.example.demoapp.data.chat.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

private const val AWAIT_TIMEOUT_MS = 15_000L

/**
 * The history screen reads the store directly, so these exercise the real
 * `FileChatStore` over a temporary directory rather than a fake -- the ordering
 * and the file naming are part of what is being checked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    /** Fixed words: these tests are about where each part lands, not the copy. */
    private val TEST_LABELS = ConversationExport.ExportLabels(
        you = "SEP-YOU",
        model = "SEP-MODEL",
        stoppedHere = "SEP-STOPPED",
        footer = "SEP-FOOTER",
        exportedAt = { "SEP-EXPORTED $it" },
    )

    private lateinit var root: File
    private lateinit var chatStore: FileChatStore
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        root = Files.createTempDirectory("history-test").toFile()
        chatStore = FileChatStore(File(root, "chats"))
        viewModel = HistoryViewModel(chatStore)
    }

    @After
    fun tearDown() {
        // Wait for work already handed to the IO dispatcher before the main
        // dispatcher is changed back.
        //
        // Closing a scope stops the coroutine, but a task already dispatched to
        // Dispatchers.IO still runs, and when it resumes it comes back to Main --
        // by then the next test's dispatcher, which fails as "Dispatchers.Main is
        // used concurrently with setting it", reported against whichever test
        // happened to be running. Queueing one more IO task and waiting for it
        // drains everything ahead of it.
        runBlocking {
            // Both pools. A ViewModel's work goes to IO, but a sharing coroutine or
            // a flow operator may be on Default, and either one resuming after the
            // main dispatcher has been swapped is the same failure.
            withContext(Dispatchers.IO) { }
            withContext(Dispatchers.Default) { }
            // Then let anything those completions dispatched run before the swap.
            yield()
            yield()
        }
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    private fun conversation(id: String, title: String, updatedAt: Long, messages: Int = 2) =
        Conversation(
            id = id,
            title = title,
            modelId = "m",
            createdAt = updatedAt,
            updatedAt = updatedAt,
            messages = (0 until messages).map {
                ChatMessage(
                    id = "$id-$it",
                    role = if (it % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                    text = "message $it",
                    createdAt = updatedAt + it,
                )
            },
        )

    private suspend fun awaitScanned(): HistoryUiState =
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.scanned } }

    // -------------------------------------------------------------- listing ---

    @Test
    fun `an empty device reads as empty, and only after it has been read`() = runBlocking {
        val state = awaitScanned()
        assertTrue("The device must have been read", state.scanned)
        assertTrue("Nothing is there", state.isEmpty)
        assertFalse("'empty' and 'not known yet' are different states", state.scanning)
    }

    @Test
    fun `conversations on disk are listed newest first`() = runBlocking {
        // Name order is the reverse of the expected order, so a listing that
        // forgot to sort would fail rather than passing by filesystem accident.
        runBlocking { chatStore.save(conversation("a-old", "Older", updatedAt = 1_000)) }
        runBlocking { chatStore.save(conversation("z-new", "Newer", updatedAt = 2_000)) }

        viewModel.reload()
        val state = withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.scanned && it.rows.size == 2 } }

        assertEquals(
            "Newest first, not directory order",
            listOf("z-new", "a-old"),
            state.rows.map { it.conversation.id },
        )
    }

    @Test
    fun `each row carries the space its conversation takes`() = runBlocking {
        // The row shows this, `HistoryUiState` says why -- "which one is using the
        // space has an answer before deleting one" -- and `ChatStore.sizes()` was
        // written for it. None of it worked: `bytes` was left at its null default,
        // so the size never appeared, and `sizes()` had no caller in the app at all
        // (only the test double in `ChatViewModelTest` delegated to it). A
        // conversation with a message is not zero bytes, so this asserts the wiring
        // rather than the arithmetic.
        runBlocking { chatStore.save(conversation("c1", "A chat", 1_000, messages = 3)) }

        viewModel.reload()
        val state = withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.scanned && it.rows.size == 1 } }

        val bytes = state.rows.single().bytes
        assertNotNull("The row must carry the conversation's size", bytes)
        assertTrue("A conversation with messages is not empty, was $bytes", (bytes ?: 0L) > 0L)
    }

    // -------------------------------------------------------------- deleting ---
    @Test
    fun `deleting asks first, then removes the file`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", "One", updatedAt = 1_000)) }
        viewModel.reload()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.isNotEmpty() } }

        viewModel.requestDelete("c1")
        val asked = viewModel.state.value.rows.first { it.conversation.id == "c1" }
        assertTrue("The row must ask before removing anything", asked.confirmingDelete)
        assertTrue("Nothing is removed until confirmed", File(root, "chats/c1.json").isFile)

        viewModel.confirmDelete()

        val after = withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.isEmpty() } }
        assertTrue(after.isEmpty)
        assertFalse(File(root, "chats/c1.json").exists())
    }

    @Test
    fun `cancelling the confirmation changes nothing`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", "One", updatedAt = 1_000)) }
        viewModel.reload()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.isNotEmpty() } }

        viewModel.requestDelete("c1")
        viewModel.cancelDelete()

        assertNull(
            "No row may still be asking",
            viewModel.state.value.rows.firstOrNull { it.confirmingDelete },
        )
        assertTrue("Keep means keep", File(root, "chats/c1.json").isFile)
    }

    @Test
    fun `confirming with nothing selected does nothing`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", "One", updatedAt = 1_000)) }
        viewModel.reload()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.isNotEmpty() } }

        viewModel.confirmDelete()

        assertTrue("A stray confirm must not delete anything", File(root, "chats/c1.json").isFile)
    }

    // ----------------------------------------------------------- open marker ---

    @Test
    fun `the open conversation is marked`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", "One", updatedAt = 1_000)) }
        viewModel.reload()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.isNotEmpty() } }

        viewModel.setOpenConversation("c1")

        assertEquals("c1", viewModel.state.value.openConversationId)
    }
    // -------------------------------------------------------------- renaming ---

    @Test
    fun `renaming asks first and then writes the new title`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", "hi", updatedAt = 1_000)) }
        viewModel.reload()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.isNotEmpty() } }

        viewModel.requestRename("c1")
        assertEquals("The dialog opens on the current title", "hi", viewModel.state.value.renaming?.currentTitle)

        viewModel.confirmRename("Three primary colours")

        val renamed = withTimeout(AWAIT_TIMEOUT_MS) {
            viewModel.state.first { it.rows.firstOrNull()?.conversation?.title == "Three primary colours" }
        }
        assertEquals("Three primary colours", renamed.rows.first().conversation.title)
        assertNull("The dialog must close", renamed.renaming)
        assertEquals(
            "And it is on disk, not just on screen",
            "Three primary colours",
            runBlocking { chatStore.load("c1") }?.title,
        )
    }

    @Test
    fun `cancelling a rename changes nothing`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", "hi", updatedAt = 1_000)) }
        viewModel.reload()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.isNotEmpty() } }

        viewModel.requestRename("c1")
        viewModel.cancelRename()

        assertNull(viewModel.state.value.renaming)
        assertEquals("hi", runBlocking { chatStore.load("c1") }?.title)
    }

    @Test
    fun `a blank title is refused rather than written`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", "hi", updatedAt = 1_000)) }
        viewModel.reload()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.isNotEmpty() } }

        viewModel.requestRename("c1")
        viewModel.confirmRename("   ")

        assertNull("The dialog still closes", viewModel.state.value.renaming)
        assertEquals(
            "A conversation named with spaces is one no list can show",
            "hi",
            runBlocking { chatStore.load("c1") }?.title,
        )
    }

    @Test
    fun `a title keeps the conversation's place in the list`() = runBlocking {
        // Renaming must not reorder anything. It writes the title and leaves the
        // conversation's position alone, which is determined by when it was last
        // spoken in rather than when it was named.
        runBlocking { chatStore.save(conversation("a-old", "Older", updatedAt = 1_000)) }
        runBlocking { chatStore.save(conversation("z-new", "Newer", updatedAt = 2_000)) }
        viewModel.reload()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.size == 2 } }

        viewModel.requestRename("a-old")
        viewModel.confirmRename("Renamed but still older")

        val state = withTimeout(AWAIT_TIMEOUT_MS) {
            viewModel.state.first { it.rows.any { row -> row.conversation.title == "Renamed but still older" } }
        }
        assertEquals(
            "The renamed conversation stays where it was",
            listOf("z-new", "a-old"),
            state.rows.map { it.conversation.id },
        )
    }

    @Test
    fun `renaming a conversation that is gone does nothing`() = runBlocking {
        viewModel.requestRename("never-existed")
        assertNull("There is nothing to rename", viewModel.state.value.renaming)
    }
    // ------------------------------------------------------------ searching ---

    @Test
    fun `searching matches the title`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", "Three primary colours", 1_000)) }
        runBlocking { chatStore.save(conversation("c2", "Sorting a list", 2_000)) }
        viewModel.reload()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.size == 2 } }

        viewModel.setSearchQuery("primary")

        assertEquals(listOf("c1"), viewModel.state.value.visibleRows.map { it.conversation.id })
    }

    @Test
    fun `searching matches what was said, not only the title`() = runBlocking {
        // A title is one sentence from the beginning of a conversation, and what
        // someone remembers is usually something said later.
        runBlocking { chatStore.save(conversation("c1", "A chat", 1_000, messages = 2)) }
        runBlocking { chatStore.save(conversation("c2", "Another", 2_000, messages = 2)) }
        viewModel.reload()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.size == 2 } }

        // "message 0" appears in both; "message 1" does too, so narrow to one by
        // giving the conversations different content.
        viewModel.setSearchQuery("message")
        assertEquals("Both contain it", 2, viewModel.state.value.visibleRows.size)

        viewModel.setSearchQuery("nothing says this")
        assertTrue(viewModel.state.value.visibleRows.isEmpty())
    }

    @Test
    fun `an empty search shows everything`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", "One", 1_000)) }
        runBlocking { chatStore.save(conversation("c2", "Two", 2_000)) }
        viewModel.reload()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.size == 2 } }

        viewModel.setSearchQuery("one")
        assertEquals(1, viewModel.state.value.visibleRows.size)

        viewModel.setSearchQuery("")
        assertEquals("Clearing brings them back", 2, viewModel.state.value.visibleRows.size)
    }

    @Test
    fun `searching distinguishes nothing found from nothing there`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", "One", 1_000)) }
        viewModel.reload()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.isNotEmpty() } }

        viewModel.setSearchQuery("zzz")

        assertTrue("Hiding everything", viewModel.state.value.searchFoundNothing)
        assertFalse("Not the same as an empty device", viewModel.state.value.isEmpty)

        viewModel.setSearchQuery("")
        assertFalse(viewModel.state.value.searchFoundNothing)
    }

    @Test
    fun `searching ignores case`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", "Three Primary Colours", 1_000)) }
        viewModel.reload()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.isNotEmpty() } }

        viewModel.setSearchQuery("PRIMARY")

        assertEquals(1, viewModel.state.value.visibleRows.size)
    }

    // --------------------------------------------------------------- export ---

    @Test
    fun `exporting hands back text with the conversation in it`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", "A chat", 1_000, messages = 2)) }
        viewModel.reload()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.rows.isNotEmpty() } }

        var title: String? = null
        var fileName: String? = null
        var text: String? = null
        viewModel.prepareExport("c1", TEST_LABELS) { t, f, body -> title = t; fileName = f; text = body }

        withTimeout(AWAIT_TIMEOUT_MS) { while (text == null) kotlinx.coroutines.delay(5) }
        assertEquals("A chat", title)
        assertEquals("A-chat.txt", fileName)
        assertTrue("The messages are in it", text!!.contains("message 0"))
    }

    @Test
    fun `exporting something that is gone reports it rather than sharing nothing`() = runBlocking {
        var called = false
        viewModel.prepareExport("never-existed", TEST_LABELS) { _, _, _ -> called = true }

        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.error != null } }
        assertFalse("Nothing is handed out", called)
    }
}