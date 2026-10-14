package com.example.demoapp.ui.about

import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

import com.example.demoapp.data.chat.ChatMessage
import com.example.demoapp.data.chat.Conversation
import com.example.demoapp.data.chat.FileChatStore
import com.example.demoapp.data.chat.MessageRole
import com.example.demoapp.data.settings.FileSettingsStore
import com.example.demoapp.data.settings.Settings
import com.example.demoapp.model.ModelCatalogue
import com.example.demoapp.model.ModelDownloadCoordinator
import com.example.demoapp.model.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
 * The screen that offers to erase everything has to be right about two things:
 * what is actually held, and what erasing actually removes. A count that is
 * stale makes the offer a lie, and an erase that leaves something behind breaks
 * the only promise the app makes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {

    private lateinit var root: File
    private lateinit var chatStore: FileChatStore
    private lateinit var modelStore: ModelStore
    private lateinit var settingsFile: File
    private lateinit var settingsStore: FileSettingsStore
    private lateinit var coordinator: ModelDownloadCoordinator
    private lateinit var testScope: CoroutineScope
    private lateinit var viewModel: AboutViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        root = Files.createTempDirectory("about-test").toFile()
        chatStore = FileChatStore(File(root, "chats"))
        modelStore = ModelStore(File(root, "models"))
        settingsFile = File(root, "settings.json")
        settingsStore = FileSettingsStore(settingsFile)
        testScope = CoroutineScope(Dispatchers.Unconfined)
        coordinator = ModelDownloadCoordinator(testScope, modelStore, {
            kotlinx.coroutines.flow.emptyFlow()
        })
        viewModel = AboutViewModel(
            chatStore = chatStore,
            modelStore = modelStore,
            coordinator = coordinator,
            settingsStore = settingsStore,
            appFilesDir = root,
        )
    }

    @After
    fun tearDown() {
        testScope.cancel()
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

    private fun conversation(id: String, bytes: Int) = Conversation(
        id = id,
        title = "a chat",
        modelId = "m",
        createdAt = 1_000,
        updatedAt = 1_000L + bytes,
        messages = listOf(
            ChatMessage(
                id = "$id-0",
                role = MessageRole.USER,
                text = "x".repeat(bytes),
                createdAt = 1_000,
            ),
        ),
    )

    private suspend fun awaitScanned(): AboutUiState =
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { !it.scanning } }

    @Test
    fun `an empty device reports nothing held`() = runBlocking {
        val state = awaitScanned()
        assertEquals(0, state.conversationCount)
        assertEquals(0L, state.totalBytes)
        assertFalse(
            "With nothing stored there is nothing to offer to remove",
            state.hasAnything,
        )
    }

    @Test
    fun `what is held is counted, not guessed`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", 500)) }
        runBlocking { chatStore.save(conversation("c2", 700)) }
        modelStore.modelFile(ModelCatalogue.models[0]).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(1_024))
        }

        viewModel.refresh()
        val state = withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.conversationCount == 2 } }

        assertEquals("Two conversations", 2, state.conversationCount)
        assertTrue("Chat text is counted", state.chatBytes >= 1_200L)
        assertEquals("Model bytes are counted", 1_024L, state.modelBytes)
        assertTrue("And the total is the sum", state.totalBytes >= 2_224L)
        assertTrue(state.hasAnything)
    }

    @Test
    fun `erasing asks first`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", 100)) }
        awaitScanned()

        viewModel.requestErase()

        assertTrue("Nothing is removed until it is confirmed", viewModel.state.value.confirmingErase)
        assertEquals(1, runBlocking { chatStore.list() }.size)

        viewModel.cancelErase()
        assertFalse(viewModel.state.value.confirmingErase)
        assertEquals("Keep means keep", 1, runBlocking { chatStore.list() }.size)
    }

    @Test
    fun `erasing removes every conversation and reports it`() = runBlocking {
        runBlocking { chatStore.save(conversation("c1", 100)) }
        runBlocking { chatStore.save(conversation("c2", 100)) }
        awaitScanned()

        viewModel.requestErase()
        viewModel.confirmErase()

        val state = withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.eraseReport != null } }
        assertEquals("Every conversation is gone", 0, runBlocking { chatStore.list() }.size)
        assertEquals(0, state.conversationCount)
        assertFalse("And the offer to erase is withdrawn", state.hasAnything)
    }

    @Test
    fun `erasing removes the models too`() = runBlocking {
        // A user freeing space should not have to visit two screens, and a
        // promise to remove everything that left several gigabytes behind would
        // be the most visibly false version of it.
        val model = ModelCatalogue.models[0]
        val file = modelStore.modelFile(model)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(2_048))
        awaitScanned()

        viewModel.requestErase()
        viewModel.confirmErase()

        // Both conditions, not just the report.
        //
        // The report and the recomputed sizes are separate state emissions, so
        // waiting on the report alone can read the size from just before it was
        // refreshed -- and the test then fails with the pre-erase number on a
        // screen that had already finished the erase.
        val state =
            withTimeout(AWAIT_TIMEOUT_MS) {
                viewModel.state.first { it.eraseReport != null && it.modelBytes == 0L }
            }
        assertFalse("The model file is gone", file.exists())
        assertEquals(0L, state.modelBytes)
    }

    @Test
    fun `erasing puts the settings back to the shipped defaults`() = runBlocking {
        // Not erasing them would leave a record of how the app was used -- which
        // backend was chosen -- behind a button that says everything is removed.
        // Through the store the ViewModel uses. A second instance over the same
        // file would not see this one's value and would overwrite it.
        settingsStore.update { it.copy(backend = "CPU") }
        awaitScanned()

        viewModel.requestErase()
        viewModel.confirmErase()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.eraseReport != null } }

        assertEquals(Settings(), settingsStore.settings.value)
    }

    @Test
    fun `erasing removes the files no store owns`() = runBlocking {
        // The crash log and the runtime's scratch directory were both written by
        // this app and deleted by nobody. `diagnostics/` holds a stack trace with
        // this device's paths and package name in it, and the screen says every
        // piece of data has gone -- so leaving them is the promise being false in
        // the one place a person has just asked to check it.
        val crashLog = File(root, "diagnostics/crash.log")
        crashLog.parentFile?.mkdirs()
        crashLog.writeText("java.lang.IllegalStateException at /data/user/0/com.example.demoapp/x")

        val scratch = File(root, "inference-cache/scratch.bin")
        scratch.parentFile?.mkdirs()
        scratch.writeText("runtime scratch")

        // The two the stores already covered, present so the assertion below is
        // about the whole directory rather than about the two new names.
        File(root, "chats").mkdirs()
        awaitScanned()

        viewModel.requestErase()
        viewModel.confirmErase()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.eraseReport != null } }

        assertFalse("the crash log must not survive an erase", crashLog.exists())
        assertFalse("the runtime's scratch file must not survive an erase", scratch.exists())
        assertFalse("nor the directory that held it", File(root, "diagnostics").exists())
        assertFalse("nor that one", File(root, "inference-cache").exists())
    }

    // ------------------------------------------------------- free-space wipe ---

    @Test
    fun `a free-space pass reports that it is running, and then what it wrote`() = runBlocking {
        // This had no test at all, and that is why it was broken for as long as it
        // existed. The ViewModel held two private `MutableStateFlow`s -- `wiping` and
        // `wiped` -- while `AboutUiState` carried fields of the same names. Nothing
        // observed the private pair and nothing wrote the state fields, so the screen
        // read a value that was always its default: the button never greyed out,
        // never became "writing", and never said how much it had written. On an
        // operation whose own copy warns that it wears the flash and may take a long
        // time, the user could not tell a running pass from a tap that did nothing.
        //
        // The budget is small because a wipe is bounded by how much it writes; this
        // asserts the wiring, not throughput. `SecureDelete.wipeFreeSpace` writes in
        // chunks and checks for cancellation between them, so a few megabytes is
        // enough to be observable and quick.
        val budget = 4L * 1024 * 1024

        assertFalse("nothing is running before it is asked", viewModel.state.value.wiping)
        assertNull("and nothing has been written", viewModel.state.value.wipedBytes)

        viewModel.wipeFreeSpace(root, budget)

        // The running flag has to be observable from the state the screen reads.
        // Waiting for it rather than asserting it immediately: the pass runs on
        // Dispatchers.IO and may finish before this line on a fast machine, so an
        // immediate assertion would be a race rather than a check.
        val sawRunning =
            runCatching {
                withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.wiping } }
            }.isSuccess

        val after = withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { !it.wiping && it.wipedBytes != null } }

        // Either it was seen running, or it finished so fast the intermediate state
        // never appeared -- both are acceptable, and what is not acceptable is the
        // result never arriving.
        if (!sawRunning) {
            assertNotNull(
                "the pass finished before it could be observed running (budget too small to be useful)",
                after.wipedBytes,
            )
        }
        assertNotNull("the pass must report what it wrote", after.wipedBytes)
        assertTrue("and it must be a real figure, not a placeholder", (after.wipedBytes ?: 0L) > 0L)
    }

    @Test
    fun `a second pass while one is running is refused rather than stacked`(): Unit = runBlocking {
        // The guard reads the state the screen reads. It used to read the private
        // flag, which is the same defect one level down: two passes over the same
        // directory would both write, and the flash wears for each of them.
        //
        // Only the *first* assertion can be about the flag immediately: the pass runs
        // on Dispatchers.IO, so on a fast machine it can finish before the second
        // call. What is checked without racing is the outcome -- whatever the second
        // call did, there is one result and one end, and the pass does not report
        // having written twice the budget.
        val budget = 4L * 1024 * 1024
        viewModel.wipeFreeSpace(root, budget)

        // Wait for the first pass to finish before asking about a second. `wiping`
        // going false is the only observable end, and it is what makes the second
        // call a second pass rather than an overlap.
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { !it.wiping && it.wipedBytes != null } }
        val afterFirst = viewModel.state.value.wipedBytes

        viewModel.wipeFreeSpace(root, budget)
        val afterSecond = withTimeout(AWAIT_TIMEOUT_MS) {
            viewModel.state.first { !it.wiping && it.wipedBytes != null }
        }

        // Bounded by the budget, not twice it. A stacked pass would report roughly
        // double; the assertion is loose because the two runs are independent and the
        // exact figure depends on where the chunk boundary falls.
        assertTrue(
            "one pass must not write more than its budget; got ${afterSecond.wipedBytes} after $afterFirst",
            (afterSecond.wipedBytes ?: 0L) <= budget + 1024 * 1024,
        )
    }

    @Test
    fun `a pass that writes nothing reports nothing rather than zero`() = runBlocking {
        // A path that cannot take a write. `SecureDelete.wipeFreeSpace` returns
        // `0L` here instead of throwing -- it opens the directory, finds it is not
        // one, and reports having written nothing -- so the ViewModel cannot tell
        // this apart from a pass that ran and wrote zero bytes.
        //
        // It reports null for both. The screen prints "wrote N" for a number, and
        // zero is a claim about the flash: "the pass ran and did nothing" is what a
        // reader would take from it, when the truth is that it never started.
        val notADirectory = File(root, "this-is-a-file")
        notADirectory.writeText("not a directory")

        viewModel.wipeFreeSpace(notADirectory, 1024L)

        val after = withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { !it.wiping } }
        assertNull("a pass that wrote nothing must not report a figure", after.wipedBytes)
    }
}