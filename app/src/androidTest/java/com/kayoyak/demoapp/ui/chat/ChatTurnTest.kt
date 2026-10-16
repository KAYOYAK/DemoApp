package com.kayoyak.demoapp.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kayoyak.demoapp.data.chat.Conversation
import com.kayoyak.demoapp.data.chat.FileChatStore
import com.kayoyak.demoapp.data.settings.InMemorySettingsStore
import com.kayoyak.demoapp.inference.LiteRtLlmEngine
import com.kayoyak.demoapp.inference.TestModel
import com.kayoyak.demoapp.model.DeviceMemory
import com.kayoyak.demoapp.model.InMemoryModelRegistry
import com.kayoyak.demoapp.model.ModelCatalogue
import com.kayoyak.demoapp.model.ModelStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The app's own chat pipeline, on a model, on a device.
 *
 * Every test in `app/src/test/.../ui/chat` drives a `FakeEngine`, and the instrumented
 * tests in `inference` build a `LiteRtLlmEngine` themselves. Neither covers the
 * composition: `ChatViewModel.send` -> history -> the runtime's own conversation ->
 * the reply reaching the transcript -> the encrypted store. That composition is where
 * this project's defects have actually lived -- a fake engine that answered differently
 * from the real one, a channel the real runtime filled and the engine did not read --
 * so this class runs two turns through the real objects and then reads them back off
 * the disk.
 *
 * What it does *not* check is whether the answers are any good: they are printed
 * instead, and the assertions are about the mechanism -- the model loads, both turns
 * produce text, the second turn's context is larger than the first's (which is the
 * only mechanical evidence available here that the earlier turn reached the model),
 * and the conversation reads back with both turns intact.
 *
 * The model is whichever catalogue entry [TestModel] resolves for the run, so this
 * runs on the file the app's first-run downloader fetches unless the run names
 * another one. A named file with no catalogue entry is skipped: the app's own load
 * path takes a descriptor, and inventing one here would test something the app
 * cannot do.
 */
@RunWith(AndroidJUnit4::class)
class ChatTurnTest {

    /** The prefix every line this test prints carries, so a batch's log names it. */
    private val log = "CHATTURN"

    @Test
    fun twoTurnsThroughTheAppSOwnPipelineOnARealModel(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val named = TestModel.orNull(log) ?: return@runBlocking
        val descriptor = ModelCatalogue.models.firstOrNull { it.fileName == named.file.name }
        if (descriptor == null) {
            println(
                "$log: SKIPPED - ${named.file.name} has no catalogue entry, so the app's own " +
                    "load path cannot take it; run with a catalogue model instead.",
            )
            return@runBlocking
        }

        val models = ModelStore(File(context.filesDir, "models"))
        val file = models.modelFile(descriptor)
        if (!file.isFile || file.length() == 0L) {
            println("$log: SKIPPED - ${file.absolutePath} is not on this device.")
            return@runBlocking
        }
        println(
            "$log: driving the app's own pipeline on ${descriptor.id} " +
                "(${file.name}, ${file.length()} bytes, from ${file.parentFile?.absolutePath})",
        )

        // A directory of its own, so a run never touches the chats the app is holding.
        val root = File(context.filesDir, "chat-turn-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val store = FileChatStore(File(root, "chats"))
        val engine = LiteRtLlmEngine(cacheDir = File(root, "inference-cache").apply { mkdirs() })
        val viewModel = ChatViewModel(
            chatStore = store,
            engine = engine,
            modelStore = models,
            settingsStore = InMemorySettingsStore(),
            registry = InMemoryModelRegistry(),
            deviceMemory = DeviceMemory(0L),
        )

        viewModel.newChat()
        val loadStarted = System.currentTimeMillis()
        viewModel.loadModel(descriptor)
        val loaded = awaitState(viewModel, timeoutMs = 300_000) { it.modelId != null }
        println(
            "$log: loaded ${loaded.modelId} in ${System.currentTimeMillis() - loadStarted} ms" +
                (if (loaded.loadFailure != null) " LOAD FAILURE reported" else ""),
        )
        assertNotNull("the catalogue model must load; the log says why it did not", loaded.modelId)

        val first = ask(viewModel, "用一句话说明什么是光合作用。")
        val second = ask(viewModel, "再用一句话说明什么是呼吸作用。")

        val messages = viewModel.state.value.messages
        assertEquals("two questions and two answers", 4, messages.size)
        assertTrue("the first answer must not be blank", first.text.isNotBlank())
        assertTrue("the second answer must not be blank", second.text.isNotBlank())
        println("$log: first =>>>${first.text}<<<")
        println("$log: second=>>>${second.text}<<<")
        println(
            "$log: context tokens: first=${first.contextTokens} second=${second.contextTokens} " +
                "(the second generation carries the first turn)",
        )
        assertTrue(
            "the second turn's context must be larger than the first's; " +
                "if it is not, the earlier turn never reached the model",
            (second.contextTokens ?: 0) > (first.contextTokens ?: 0),
        )

        // Read back off the disk, through the same encrypted store the app uses, with
        // the key the device holds. This is the part a unit test cannot do.
        val id = viewModel.state.value.conversationId
        assertNotNull("the conversation must have an id to read back", id)
        val stored = awaitSaved(store, id!!, expected = 4, viewModel = viewModel)
        assertNotNull("the conversation must reach the disk after two turns", stored)
        assertEquals("both turns persisted", 4, stored!!.messages.size)
        assertEquals(
            "the text on the disk must be the text on the screen",
            first.text,
            stored.messages[1].text,
        )
        println("$log: ${stored.messages.size} messages read back from the encrypted store ($id)")
    }

    /** Sends [prompt] and waits for the transcript to hold its answer. */
    private suspend fun ask(viewModel: ChatViewModel, prompt: String): Answer {
        val before = viewModel.state.value.messages.size
        viewModel.send(prompt)
        val settled = awaitState(viewModel, timeoutMs = 300_000) {
            !it.generating && it.messages.size == before + 2
        }
        settled.error?.let {
            println("$log: the screen reported an error after '$prompt'")
        }
        val answer = settled.messages.last()
        return Answer(answer.text, settled.contextTokens)
    }

    /** One answer and the context it was produced with. */
    private data class Answer(val text: String, val contextTokens: Int?)

    /**
     * Waits for the store to hold [expected] messages, and says what it found if it does not.
     *
     * Saving is fire-and-forget -- `ChatSaving.persist` launches it -- so the disk lags the
     * screen by however long a write takes, and on a device a write is a cipher, a temporary
     * file and a move. The first version of this test read the store straight after the second
     * answer and found three messages: the save carrying that answer had been launched and had
     * not finished. `awaitSaved` is the same wait the unit tests use, and the audit counters
     * are the ones this project keeps for telling a lost save apart from a slow one.
     */
    private suspend fun awaitSaved(
        store: FileChatStore,
        id: String,
        expected: Int,
        viewModel: ChatViewModel,
    ): Conversation? {
        val deadline = System.currentTimeMillis() + 60_000
        var seen: Conversation? = null
        while (System.currentTimeMillis() < deadline) {
            seen = store.load(id)
            if (seen != null && seen.messages.size >= expected) return seen
            delay(100)
        }
        val audit = store.saveAudit()
        println(
            "$log: the store holds ${seen?.messages?.size ?: 0} of $expected messages after 60 s; " +
                "store: attempted=${audit.attempted}, written=${audit.written}; " +
                "viewModel: saveNowCalls=${viewModel.saveNowCalls}, " +
                "throughLock=${viewModel.saveNowThroughLock}",
        )
        return seen
    }

    /** Polls the screen's state until [predicate] holds, or gives up with a line. */
    private suspend fun awaitState(
        viewModel: ChatViewModel,
        timeoutMs: Long,
        predicate: (ChatUiState) -> Boolean,
    ): ChatUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var seen = viewModel.state.value
        while (System.currentTimeMillis() < deadline) {
            seen = viewModel.state.value
            if (predicate(seen)) return seen
            delay(50)
        }
        println(
            "$log: gave up after $timeoutMs ms: generating=${seen.generating}, " +
                "messages=${seen.messages.size}, loadingModel=${seen.loadingModel}, " +
                "error=${seen.error != null}, modelId=${seen.modelId}",
        )
        return seen
    }
}
