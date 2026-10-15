package com.example.demoapp.ui.chat

import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

import com.example.demoapp.ui.UiMessage

import com.example.demoapp.R

import com.example.demoapp.model.DeviceMemory
import com.example.demoapp.data.chat.ChatMessage
import com.example.demoapp.data.chat.ChatStore
import com.example.demoapp.data.chat.Conversation
import com.example.demoapp.data.chat.FileChatStore
import com.example.demoapp.data.chat.MessageRole
import com.example.demoapp.data.settings.InMemorySettingsStore
import com.example.demoapp.data.settings.Settings
import com.example.demoapp.inference.EngineState
import com.example.demoapp.inference.EngineTurn
import com.example.demoapp.inference.GenerationConfig
import com.example.demoapp.inference.GenerationEvent
import com.example.demoapp.inference.InferenceBackend
import com.example.demoapp.inference.LoadFailure
import com.example.demoapp.inference.LlmEngine
import com.example.demoapp.inference.LocalModel
import com.example.demoapp.model.ModelCatalogue
import com.example.demoapp.model.ModelStore
import kotlinx.serialization.json.Json
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

/**
 * How long a test waits for a condition to become true.
 *
 * Generous because these tests wait on real IO and on coroutines that hop
 * between dispatchers, and the first run after a build pays JVM warm-up. The
 * timeout does not weaken anything: it only bounds how long the test waits, and
 * the condition still has to hold.
 */
private const val AWAIT_TIMEOUT_MS = 15_000L

/**
 * How long teardown waits for the ViewModels a test built to stop.
 *
 * Far below [AWAIT_TIMEOUT_MS] on purpose: this is not a test's own deadline, it is
 * a bound on cleanup, and a suite where every teardown spent fifteen seconds failing
 * would hide the failure it is reporting.
 */
private const val QUIESCENCE_TIMEOUT_MS = 3_000L

/**
 * Makes a refusal the retries recovered from a failure in this class.
 *
 * Set by `scripts/stress-flake.ps1` through `-Pdemoapp.refusalWatch`, which
 * `app/build.gradle.kts` forwards into the test JVM. Without it a recovered refusal is
 * recorded in the save audit and reported in the timeout message, and no test fails --
 * which is what CI wants, because the save landed.
 */
private const val REFUSAL_WATCH_PROPERTY = "demoapp.refusalWatch"

/** Stands in for the real runtime: no model file, no native library. */
private class FakeEngine : LlmEngine {
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

        /**
     * Events the test tells the engine to produce.
     *
     * A Channel rather than a SharedFlow: generation now starts only after the
     * user's message has been written, so a test that emits immediately after
     * send() emits before anything is collecting. A SharedFlow with no
     * subscriber drops those events; a Channel buffers them until the flow is
     * consumed, which is what these tests need.
     */
    private val scripted = Channel<GenerationEvent>(Channel.UNLIMITED)

    fun emit(event: GenerationEvent) {
        check(scripted.trySend(event).isSuccess) { "engine event buffer rejected $event" }
    }

    var loadShouldFail = false
    var loadedModelId: String? = null
    var unloadCount = 0
    var resetCount = 0

    /**
     * When set, the load fails carrying this sentence, the way the real engine does
     * after every backend has been rejected.
     *
     * Null means a plain failure with no sentence of its own, which is the other
     * path through the same handler.
     */
    var loadFailureSentence: UiMessage? = null

    /** The history the last reset was seeded with. */
    var seeded: List<EngineTurn> = emptyList()

    /** Records the prompt the engine was given, so the test can assert on it. */
    var loadedSystemPrompt: String? = null

    /** And the sampling configuration, which reaches the model the same way. */
    var loadedSampling: GenerationConfig? = null

    /** The generation settings the last message was sent with. */
    var lastConfig: GenerationConfig? = null

    override suspend fun load(
        model: LocalModel,
        requested: InferenceBackend,
        systemPrompt: String,
        sampling: GenerationConfig,
    ): Result<InferenceBackend> {
        loadedSystemPrompt = systemPrompt
        loadedSampling = sampling
        loadedModelId = model.id
        if (loadShouldFail) {
            val cause = IllegalStateException("not enough memory")
            val sentence = loadFailureSentence
            return if (sentence == null) {
                // A plain failure, with no sentence of its own: `EngineState.Failed`
                // used to be set here as well, and nothing ever read it.
                Result.failure(cause)
            } else {
                Result.failure(
                    com.example.demoapp.inference.EngineLoadFailure(sentence, cause),
                )
            }
        }
        _state.value = EngineState.Ready(model.id, InferenceBackend.CPU)
        return Result.success(InferenceBackend.CPU)
    }

    override suspend fun unload() {
        unloadCount++
        loadedModelId = null
        _state.value = EngineState.Idle
    }

    override suspend fun resetConversation(history: List<EngineTurn>) {
        resetCount++
        seeded = history
    }

    /** The prompt of the most recent generation, for asserting what was asked. */
    var lastPrompt: String? = null

    override fun generate(prompt: String, config: GenerationConfig): Flow<GenerationEvent> = flow {
        lastConfig = config
        lastPrompt = prompt
        // Open-ended in the sense that the test decides when generation ends,
        // which is what makes a mid-generation state observable at all.
        //
        // Closed on a terminal event, because the real engine's flow ends there.
        // Without this every earlier generation keeps collecting, so a second
        // exchange sees the first one's events and the transcript is assembled
        // by two collectors at once.
        for (event in scripted) {
            emit(event)
            if (event is GenerationEvent.Done || event is GenerationEvent.Failed) return@flow
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private lateinit var root: File
    private lateinit var chatStore: FileChatStore
    private lateinit var modelStore: ModelStore
    private lateinit var engine: FakeEngine

    private val model = ModelCatalogue.models.first()

    @Before
    fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        root = Files.createTempDirectory("chat-vm-test").toFile()
        chatStore = FileChatStore(File(root, "chats"))
        modelStore = ModelStore(File(root, "models"))
        engine = FakeEngine()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        store.clear()

        // Wait for the ViewModels this test built to stop, and for the work they
        // handed to the IO and Default pools to finish, before the main dispatcher
        // is changed back.
        //
        // Closing a ViewModel's scope stops the coroutine, but a task already
        // dispatched to Dispatchers.IO still runs to completion. When it resumes it
        // tries to come back to Main -- and by then Main is the next test's
        // dispatcher, which fails as "Dispatchers.Main is used concurrently with
        // setting it", reported against whichever test happened to be running.
        //
        // The previous version of this drained by queueing one empty task on each
        // pool and waiting for it:
        //
        //     withContext(Dispatchers.IO) { }
        //     withContext(Dispatchers.Default) { }
        //
        // That is not a barrier on a pooled dispatcher, and this is the reason it
        // was worth replacing rather than re-tuning. `Dispatchers.IO` runs up to 64
        // threads, so an empty task completing proves *that* task has completed --
        // not that the tasks queued ahead of it have. Any of them can still be in
        // flight, can finish after the drain returns, and can dispatch the state
        // write that resumes on Main. Twelve tests failed intermittently and all
        // twelve were in this class, which is what a class-wide teardown hole looks
        // like when it is blamed on the machine.
        //
        // Waiting on the ViewModels themselves is exact: their work is the work
        // that matters, and `viewModelScope` gives it one root to watch.
        awaitQuiescence()

        Dispatchers.resetMain()
        root.deleteRecursively()

        // Last, so the saves this test started have landed and the read below sees a final
        // number, and so a failure here cannot leave the main dispatcher swapped for the
        // next test. See [refusalsAbsorbedByTheWindow].
        refusalsAbsorbedByTheWindow()
    }

    /**
     * Every ViewModel `tracked` built, so teardown can watch what they are doing.
     */
    private val built = java.util.Collections.synchronizedList(mutableListOf<androidx.lifecycle.ViewModel>())

    /**
     * Blocks until no tracked ViewModel has a coroutine that has not finished, or
     * the budget runs out.
     *
     * Bounded, because this is a test helper and a bug that leaves a coroutine
     * spinning should fail the test it is in rather than hang the suite. On expiry
     * it proceeds to the dispatcher swap anyway: the remaining work then fails the
     * way it did before, which is noise this is trying to remove rather than a new
     * way to be wrong.
     *
     * Polling rather than joining, because a ViewModel's work is a tree: a write on
     * IO finishes and schedules the reload that comes back to Main, so "nothing
     * running right now" and "done" are different instants. Two quiet turns in a
     * row is the cheap way to ask for both.
     */
    private suspend fun awaitQuiescence() {
        val deadline = System.nanoTime() + QUIESCENCE_TIMEOUT_MS * 1_000_000L
        var quietTurns = 0
        while (System.nanoTime() < deadline) {
            if (!built.any { it.hasWorkInFlight() }) {
                quietTurns++
                if (quietTurns >= 2) return
            } else {
                quietTurns = 0
            }
            // Hand the pools a chance to make progress rather than spinning on the
            // test thread. One empty task on each, which is what the old drain did --
            // the difference is that this asks a second time, and a third, until the
            // answer stops changing.
            withContext(Dispatchers.IO) { }
        }
    }

    /**
     * Whether this ViewModel still has a coroutine that has not finished.
     *
     * This asks `!isCompleted` rather than `isActive`, and that distinction is the
     * whole point. Teardown clears the store first, so the usual state at this
     * moment is a *cancelled* coroutine that has not unwound yet -- it reads as
     * inactive while its resumption back to the main dispatcher is still queued, so
     * the previous `isActive` version answered "quiet" and swapped the dispatcher
     * out from under it. That is what "Dispatchers.Main is used concurrently with
     * setting it" was: 2 runs in 40 of the stress batch, reported against whichever
     * test happened to be starting.
     *
     * `closingWrites` is watched as well because it is deliberately not part of
     * `viewModelScope`: an interrupted reply is saved there when the screen closes,
     * and while it used to be a throwaway scope built at the call site, nothing --
     * including this teardown -- could see that the write was still running.
     */
    private fun androidx.lifecycle.ViewModel.hasWorkInFlight(): Boolean {
        fun androidx.lifecycle.ViewModel.watching(job: kotlinx.coroutines.Job?): Boolean =
            job?.children?.any { !it.isCompleted } == true
        return watching(viewModelScope.coroutineContext[Job]) ||
            watching((this as? ChatViewModel)?.closingWrites?.coroutineContext?.get(Job))
    }

    /**
     * A store holding every ViewModel this test built.
     *
     * A ViewModel's scope is closed when its screen goes away; a test has no
     * screen. Without this a generation still collecting at the end of a test
     * outlives it, and the next test's `setMain` fails with "Dispatchers.Main is
     * used concurrently with setting it" -- reported against a different test
     * from the one that caused it, which is what makes it worth handling here.
     *
     * A store rather than a list because `ViewModelStore.clear()` is the public
     * way to do this, and `ViewModel.clear()` is not reachable at all.
     */
    private val store = androidx.lifecycle.ViewModelStore()
    private var slot = 0

    /** Puts a ViewModel under the store's care and hands it back. */
    private fun <T : androidx.lifecycle.ViewModel> tracked(make: () -> T, type: Class<T>): T {
        val made = make()
        // Registered before it is handed back, so a test that forgets to await
        // anything still has its work waited for in teardown.
        built.add(made)
        val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : androidx.lifecycle.ViewModel> create(modelClass: Class<VM>): VM =
                made as VM
        }
        androidx.lifecycle.ViewModelProvider(store, factory).get("slot${slot++}", type)
        return made
    }

    /** Writes a file of the exact catalogue size, which is what marks a model installed. */
    private fun installModel() {
        val file = modelStore.modelFile(model)
        file.parentFile?.mkdirs()
        java.io.RandomAccessFile(file, "rw").use { it.setLength(model.sizeBytes) }
    }

    private fun newViewModel(): ChatViewModel =
        tracked({ ChatViewModel(chatStore, engine, modelStore) }, ChatViewModel::class.java)

    /**
     * A ViewModel that reads a settings file a test can change.
     *
     * The settings that reach the model are taken at load, so a test about them
     * has to set the file before the model is loaded, not after.
     */
    private fun newViewModelWith(settingsStore: InMemorySettingsStore): ChatViewModel =
        tracked(
            { ChatViewModel(chatStore, engine, modelStore, settingsStore) },
            ChatViewModel::class.java,
        )

    /**
     * Waits for the ViewModel's start-up work, which runs on real dispatchers.
     *
     * Suspending, and the test supplies the only `runBlocking`. A helper with its
     * own `runBlocking` nested inside a test's blocked the thread's event loop, and
     * a save waiting on that loop never completed -- tests timed out after sixty
     * seconds with nothing wrong on disk. One loop per test, always.
     */
    private suspend fun ChatViewModel.await(predicate: (ChatUiState) -> Boolean): ChatUiState =
        withTimeout(AWAIT_TIMEOUT_MS) { state.first(predicate) }

    /**
     * Waits for a condition, and on timeout says what the state actually was.
     *
     * `await` throws a bare timeout, and for an intermittent failure that is the one
     * message that cannot be acted on.
     *
     * Not a `suspend` function on purpose. Every helper here runs its own
     * `runBlocking`, and a test that wrapped its body in `runBlocking` as well
     * nested the two: the outer one blocks the thread's event loop, so a save
     * waiting on that loop never completes and the test times out after sixty
     * seconds with nothing wrong on disk.
     */
    private suspend fun awaitOrReport(
        what: String,
        vm: ChatViewModel,
        predicate: (ChatUiState) -> Boolean,
    ) {
        try {
            vm.await(predicate)
        } catch (t: Throwable) {
            val seen = vm.state.value
            throw AssertionError(
                "Gave up waiting for $what. On disk: " +
                    listNow().map { "${it.id}(${it.messages.size} msgs, at ${it.updatedAt})" } +
                    "; screen: ${seen.messages.size} msgs, modelId=${seen.modelId}, " +
                    "loading=${seen.loadingModel}, generating=${seen.generating}, " +
                    "error=${seen.error}",
                t,
            )
        }
    }

    /** The store, from a test that is not a coroutine. One `runBlocking`, never nested. */
    private suspend fun listNow(): List<Conversation> = chatStore.list()

    private suspend fun saveNow(conversation: Conversation) = chatStore.save(conversation)

    /**
     * Waits for the saved history to catch up.
     *
     * Saving is asynchronous, so the screen can show a message a few
     * milliseconds before it reaches disk. A test about persistence has to wait
     * for the disk, not for the screen.
     *
     * The poll interval is flat and small, and that is the result of an experiment
     * rather than an oversight. Polling every 5 ms for fifteen seconds is about
     * three thousand calls to `chatStore.list()`, each of which is
     * `withContext(Dispatchers.IO)` reading and decrypting every conversation file
     * -- so the theory was that the waiter was starving the save it was waiting for
     * on the same pool, and that backing off would fix the intermittent failures.
     *
     * It was tried, and it did not: geometric backoff to a 50 ms ceiling left the
     * failure rate at about one every two runs (8 failures across 8 full runs,
     * against 2 across 5 before), *and* made every run slower. That is the shape of
     * a failed hypothesis rather than a noisy one -- if the write were merely slow,
     * polling less often would have helped it.
     *
     * What survived is the counter in the timeout message below. A failure now
     * reports how many polls it made before giving up, which is what makes the
     * cause checkable from a test report alone: around two hundred means the loop
     * ran its full budget and the write genuinely never landed, while a small number
     * would mean the waiter itself gave up early.
     */
    private suspend fun awaitSaved(predicate: (List<Conversation>) -> Boolean): List<Conversation> {
        var polls = 0
        return try {
            withTimeout(AWAIT_TIMEOUT_MS) {
                var list: List<Conversation>
                do {
                    delay(5)
                    list = chatStore.list()
                    polls++
                } while (!predicate(list))
                list
            }
        } catch (t: Throwable) {
            val found = chatStore.list()
            // The save audit, not just the disk. Three explanations for this timeout
            // were tried and all three were wrong, because the message could not tell
            // the two possible situations apart: a write that was refused by the
            // store, and a write that was never attempted. `ChatStore.saveAudit` is
            // the difference, and it is the first thing to read in this message.
            val audit = chatStore.saveAudit()
            // The ViewModel side of the same question. `saveAudit` says the store was
            // never asked to write; these two say whether the ViewModel asked and got
            // stuck on its own lock, or never reached the call. A `saveNowCalls` of
            // zero points at the caller; a non-zero one with the store at zero points
            // at `saveLock`.
            //
            // `error` is the third side, added after a run in which `attempted` was
            // greater than `written`: a save that entered the store and never finished.
            // If that write threw, `saveNow` caught it and put the reason here, which
            // this message did not used to print -- so the only record of the cause was
            // thrown away by the very assertion that needed it.
            val viewModel = built.filterIsInstance<ChatViewModel>().firstOrNull()
            val error = viewModel?.state?.value?.error
            val reached =
                if (viewModel == null) {
                    "viewModel: <none built>"
                } else {
                    "viewModel: saveNowCalls=${viewModel.saveNowCalls} " +
                        "throughLock=${viewModel.saveNowThroughLock} " +
                        "error=" +
                        (error?.let { "string#${it.textRes} detail='${it.detail}'" } ?: "<none>")
                }
            throw AssertionError(
                "Gave up waiting for the saved history after $polls polls. " +
                    "save: attempted=${audit.attempted} written=${audit.written} " +
                    "refusedAsOlder=${audit.refusedAsOlder} refusedAsDeleted=${audit.refusedAsDeleted}. " +
                    // The store's own account of a move that ran out of attempts, when
                    // there has been one. Without it this message said a save had not
                    // landed and left the operating system's objection unrecorded,
                    // which is what made the retry window a guess for three rounds.
                    (audit.lastMoveRefusal?.let { "The last refused move: $it. " } ?: "") +
                    // The other half of that account. A refusal the retries absorbed is
                    // not a failure and does not reach `lastMoveRefusal`, but it can be
                    // the reason a wait times out: a run whose moves are refused often
                    // enough spends its window sleeping. Without this the message reads
                    // as a timeout with nothing refusing anything.
                    (audit.lastAbsorbedMoveRefusal?.let {
                        "Refusals a later attempt made (${audit.absorbedMoveRefusals}): $it. "
                    } ?: "") +
                    "$reached. " +
                    (when {
                        audit.attempted == 0 && viewModel != null && viewModel.saveNowCalls == 0 ->
                            "<- the ViewModel never called saveNow; the loss is upstream of it"
                        audit.attempted == 0 && viewModel != null && viewModel.saveNowCalls > 0 ->
                            "<- saveNow ran but never reached the store; saveLock is the suspect"
                        audit.attempted == 0 ->
                            "<- save was NEVER CALLED for anything; the missing write is upstream of the store"
                        audit.written == 0 ->
                            "<- every save was refused; the store's guards are the cause"
                        audit.attempted > audit.written ->
                            "<- a save took the store's lock and had not finished at the " +
                                "timeout: the write itself is the suspect, and any exception " +
                                "it threw is in the error line above"
                        else ->
                            "<- every save the store was asked for was written whole; the " +
                                "missing content is what the caller passed, not the write"
                    }) +
                    ". On disk: " +
                    (if (found.isEmpty()) {
                        "<nothing>"
                    } else {
                        found.joinToString {
                            "${it.id}: ${it.messages.size} msgs, updated ${it.updatedAt}, " +
                                "pinned ${it.pinnedAt}"
                        }
                    }),
                t,
            )
        }
    }

    /**
     * Reports a refusal this test's saves recovered from, when the run is watching for one.
     *
     * A save that landed after the file system refused a move is not a failure: the user sees
     * nothing and the conversation is on disk. So this is silent by default and a green suite
     * keeps the meaning it had -- every save landed.
     *
     * [REFUSAL_WATCH_PROPERTY] is what turns it into the instrument the refusal never had.
     * `scripts/stress-flake.ps1` runs the batch with it, and the count below is then a
     * failure carrying the operating system's own words, instead of another clean run that
     * says nothing about refusals. A hundred and fifty of those were read as evidence
     * before this existed, and the retry window was widened three times on their strength.
     *
     * Called from [tearDown] so it covers every test in the class rather than the ones that
     * happen to wait on a save. It reads the store this class hands to the ViewModel, so a
     * refusal absorbed by a second store a test builds for itself is not seen here -- that
     * store's own audit is where it is recorded.
     *
     * The first batch run with this on reported seven recoveries, and two of the faults
     * behind them were the instrument's own. Five read `1 time(s) in this test: no
     * account`: the number and the account were two fields, written on the worker thread
     * that makes the move and read here, and the number's store became visible while the
     * account's had not -- `FileChatStore` publishes them as one value now. And this ran
     * first in [tearDown], before the class waited for the ViewModels it built, so it
     * could read a count while the save it was asking about was still retrying; it runs
     * last now. Both are recorded in `docs/OPEN-WIRING.md`, because a watch that can be
     * wrong about the number it reports is the same defect it was built to find.
     */
    private fun refusalsAbsorbedByTheWindow() {
        val audit = chatStore.saveAudit()
        if (audit.absorbedMoveRefusals == 0) return
        if (System.getProperty(REFUSAL_WATCH_PROPERTY) == null) return
        throw AssertionError(
            "The file system refused a move and the retry window absorbed it " +
                "(${audit.absorbedMoveRefusals} time(s) in this test): " +
                "${audit.lastAbsorbedMoveRefusal ?: "no account"}. " +
                "The save landed, so nothing failed here -- and this is the reason that was " +
                "never known when MOVE_RETRY_MILLIS was widened three times. A window short " +
                "enough to run out would have failed this test instead.",
        )
    }

    /**
     * Waits for the runtime to be seeded, and returns what with.
     *
     * The seed is not part of the screen's state -- it is the runtime's memory --
     * so a test about it has to watch the engine, not the ViewModel.
     */
    private suspend fun awaitSeeded(predicate: (List<EngineTurn>) -> Boolean): List<EngineTurn> =
        withTimeout(AWAIT_TIMEOUT_MS) {
            var seed: List<EngineTurn>
            do {
                delay(5)
                seed = engine.seeded
            } while (!predicate(seed))
            seed
        }

    @Test
    fun `the creativity setting reaches the model`(): Unit = runBlocking {
        // The end of this chain -- the sampler the runtime is given -- is only
        // reachable on a device, and is asserted there. What this covers is the
        // half that can be: the setting is read when the model is loaded, and it
        // is the value the engine is handed. A screen offering five choices that
        // never leave the file is worse than no screen.
        installModel()
        val settings = InMemorySettingsStore(Settings(temperatureTenths = 2))

        val vm = newViewModelWith(settings)
        vm.await { it.modelId != null }

        // The slider still reaches the model, now as part of the whole sampling
        // configuration rather than on its own.
        assertEquals(0.2, engine.loadedSampling?.temperature ?: -1.0, 0.0001)
    }

    @Test
    fun `the reply length is read at the moment of asking`(): Unit = runBlocking {
        // The other half of the same promise: this one does apply per message, so
        // a change takes effect without a reload.
        installModel()
        val settings = InMemorySettingsStore(Settings(maxReplyTokens = 128))
        val vm = newViewModelWith(settings)
        vm.await { it.modelId != null }

        vm.send("hello")
        vm.await { it.generating }

        // `generating` is set synchronously by send(); the model is not asked
        // anything until the question has been written to disk, so waiting for
        // that flag would read the config before it exists.
        val config = runBlocking {
            withTimeout(AWAIT_TIMEOUT_MS) {
                while (engine.lastConfig == null) delay(5)
                engine.lastConfig!!
            }
        }
        assertEquals(128, config.maxTokens)
    }

    // ------------------------------------------------------------ start-up ---

    @Test
    fun `with no model installed the screen asks for one`(): Unit = runBlocking {
        val vm = newViewModel()
        val state = vm.await { it.scanned && it.installedModels.isEmpty() && !it.loadingModel }

        assertTrue(state.needsAModel)
        assertFalse("Nothing can be sent without a model", state.canSend)
    }

    @Test
    fun `with a model installed it is loaded and chat becomes available`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        val state = vm.await { it.modelId != null }

        assertEquals(model.id, state.modelId)
        assertFalse(state.needsAModel)
        assertTrue(state.canSend)
        assertEquals("The engine must have been given the catalogue's own file", model.id, engine.loadedModelId)
    }

    @Test
    fun `a model that fails to load is reported and blocks sending`(): Unit = runBlocking {
        installModel()
        engine.loadShouldFail = true
        val vm = newViewModel()
        val state = vm.await { it.error != null }

        assertNull("No model is usable, so nothing may be sent", state.modelId)
        assertFalse(state.canSend)
        // The kind and the model's name, rather than a resource id.
        //
        // The id changed when the message became a diagnosis rather than a
        // sentence -- it used to say "could not load" and nothing else. What is
        // being asserted here is that the failure is reported as the right *sort*
        // of failure and that it names the model, because a user with seven
        // installed has no way to act on "a model failed".
        assertEquals(LoadFailure.Kind.ENGINE_FAILED, state.loadFailure?.kind)
        assertEquals(listOf(model.displayName), state.error!!.args)
        // The runtime's own account is kept for the report, not shown as the message.
        assertNotNull("The raw reason is carried", state.loadFailure?.detail)
    }

    @Test
    fun `a failure the engine could explain is shown as the engine explained it`(): Unit = runBlocking {
        installModel()
        engine.loadShouldFail = true
        engine.loadFailureSentence = UiMessage(R.string.load_fail_missing)
        val vm = newViewModel()
        val state = vm.await { it.error != null }

        assertNull("No model is usable, so nothing may be sent", state.modelId)
        assertFalse(state.canSend)
        // The engine's sentence, not the classifier's.
        //
        // Both are available here and they disagree on purpose. The cause is an
        // IllegalStateException, which `LoadFailure.of` files under ENGINE_FAILED --
        // and the engine, which is the only layer that saw every backend rejected,
        // said "missing". That disagreement is the whole reason the sentence is
        // carried up: re-classifying from the raw text would report the second
        // answer and lose the first.
        assertEquals(
            "the engine's own sentence must reach the screen",
            R.string.load_fail_missing,
            state.error!!.textRes,
        )
        // Not `assertNotEquals`: this JUnit's Assert has no such overload, and the
        // failure message matters more than the shape of the call.
        assertTrue(
            "the classifier's answer must not win over the engine's, but error was " +
                "${state.error!!.textRes}",
            state.error!!.textRes != R.string.load_fail_engine,
        )
        // The report keeps the classified kind either way, so a bug report still
        // carries something a reader can act on.
        assertEquals(LoadFailure.Kind.ENGINE_FAILED, state.loadFailure?.kind)
    }

    // ---------------------------------------------------------- messaging ---

    @Test
    fun `sending records the question before the reply arrives`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }

        vm.send("Name one colour, one word only.")

        val state = vm.await { it.messages.isNotEmpty() }
        assertEquals(1, state.messages.size)
        assertEquals(MessageRole.USER, state.messages.first().role)
        assertTrue("Generation should be running", state.generating)
    }

    @Test
    fun `the question is on disk before the answer exists`(): Unit = runBlocking {
        // If the process dies while the model is thinking, the question must
        // survive. Losing the answer is recoverable; losing the question is not.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }

        vm.send("What is a haiku?")
        vm.await { it.messages.isNotEmpty() }
        // Saving is asynchronous, so the screen shows the question before the
        // file exists. A test about persistence has to wait for the disk.
        //
        // The wait has to name the question, not merely name a file. Loading a
        // model persists this same conversation with no messages yet
        // (`ChatModels` on a successful load -> `persist`), so a predicate that
        // only asks whether a conversation exists can be satisfied by that empty
        // version -- and `messages.first()` below then throws
        // `java.util.NoSuchElementException: List is empty`. A stress batch of
        // one hundred and fifty runs caught exactly that once, and the write it
        // was waiting for was never missing.
        val asked = "What is a haiku?"
        val saved = awaitSaved { list -> list.any { c -> c.messages.any { m -> m.text == asked } } }
            .first { c -> c.messages.any { m -> m.text == asked } }
        assertEquals("What is a haiku?", saved.messages.first().text)
        assertEquals("The title comes from the first question", "What is a haiku?", saved.title)
    }

    @Test
    fun `tokens appear as they are produced`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("count")

        engine.emit(GenerationEvent.Token("one "))
        val first = vm.await { it.streaming.isNotEmpty() }
        assertEquals("one ", first.streaming)

        engine.emit(GenerationEvent.Token("two"))
        val second = vm.await { it.streaming == "one two" }
        assertEquals("one two", second.streaming)
        assertTrue("The partial reply is not history yet", second.messages.size == 1)
    }

    @Test
    fun `completion commits the engine's text, not the accumulated tokens`(): Unit = runBlocking {
        // The engine reports the authoritative reply. If the two ever disagree,
        // the saved history must match what the engine produced, or reopening a
        // chat would show something the model never said.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("count")

        engine.emit(GenerationEvent.Token("partial"))
        vm.await { it.streaming == "partial" }
        engine.emit(GenerationEvent.Done("the authoritative reply", 1200, 3))

        val state = vm.await { !it.generating && it.messages.size == 2 }
        assertEquals("", state.streaming)
        assertEquals("the authoritative reply", state.messages.last().text)
        assertEquals(MessageRole.ASSISTANT, state.messages.last().role)
        assertEquals(model.id, state.messages.last().modelId)
        assertEquals(1200L, state.messages.last().elapsedMillis)
    }

    @Test
    fun `a completed reply is persisted`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("hi", 10, 1))
        vm.await { it.messages.size == 2 }
        // Saving is asynchronous, so the screen shows the reply before the file
        // contains it. A test about persistence has to wait for the disk.
        val saved = awaitSaved { it.firstOrNull()?.messages?.size == 2 }.first()

        assertEquals(listOf("hello", "hi"), saved.messages.map { it.text })
    }

    @Test
    fun `a failure is reported and does not leave the screen generating`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Failed(java.io.IOException("out of memory")))

        val state = vm.await { !it.generating && it.error != null }
        // The sentence is a resource; the exception's text is the detail beside it.
        // Asserting the wording would pin this to strings.xml and catch nothing.
        assertEquals(R.string.err_generation_failed, state.error!!.textRes)
        assertEquals("out of memory", state.error!!.detail)
        assertEquals("", state.streaming)
        assertEquals("The user's question stays", 1, state.messages.size)
    }

    @Test
    fun `stopping keeps what was produced and marks it interrupted`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("tell me a long story")
        engine.emit(GenerationEvent.Token("Once upon a time"))
        vm.await { it.streaming == "Once upon a time" }

        vm.stop()

        val state = vm.await { !it.generating }
        assertEquals(2, state.messages.size)
        assertEquals("Once upon a time", state.messages.last().text)
        assertTrue(
            "A partial reply must not look complete when the chat is reopened",
            state.messages.last().interrupted,
        )
    }

    @Test
    fun `stopping before anything was produced leaves no empty message`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")

        vm.stop()

        val state = vm.await { !it.generating }
        assertEquals("One empty reply must not be recorded", 1, state.messages.size)
    }

    @Test
    fun `blank input is ignored`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }

        vm.send("   ")

        assertTrue("Nothing should have been recorded", vm.state.value.messages.isEmpty())
        assertFalse(vm.state.value.generating)
    }

    // -------------------------------------------------------- conversations ---

    @Test
    fun `a new chat starts empty and leaves the previous one on disk`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("hi", 10, 1))
        vm.await { it.messages.size == 2 }

        vm.newChat()

        assertTrue("The screen is empty again", vm.state.value.messages.isEmpty())
        // Saving is asynchronous, so the file is written after the screen
        // changes. Reading the directory here raced the write and sometimes
        // found nothing at all.
        //
        // The wait names the two messages, because the conversation that is on
        // disk while a model is loading has none of them yet, and a predicate
        // that only asked whether something was there would read that one.
        val onDisk = awaitSaved { list -> list.any { it.messages.size == 2 } }
        assertEquals("The old conversation must not be lost", 1, onDisk.size)
        assertEquals(2, onDisk.first().messages.size)
    }

    @Test
    fun `reopening continues the most recent conversation`(): Unit = runBlocking {
        installModel()
        val first = newViewModel()
        first.await { it.modelId != null }
        first.send("remember this")
        engine.emit(GenerationEvent.Done("noted", 10, 1))

        // Each wait reports what it was looking at. This test failed roughly once
        // in twelve runs and the timeout alone said nothing about which of the
        // three waits it was, or what the state was when it gave up.
        awaitOrReport("the reply to arrive", first) { it.messages.size == 2 }
        awaitSaved { it.firstOrNull()?.messages?.size == 2 }

        // A fresh ViewModel is what a relaunch looks like. Built through the same
        // tracking as every other one: a ViewModel whose scope nobody closes holds
        // a coroutine that outlives the test.
        val second =
            tracked({ ChatViewModel(chatStore, FakeEngine(), modelStore) }, ChatViewModel::class.java)
        awaitOrReport("the relaunch to open the conversation", second) { it.messages.isNotEmpty() }

        assertEquals(
            listOf("remember this", "noted"),
            second.state.value.messages.map { it.text },
        )
    }

    // ------------------------------------------------- regressions from review ---

    @Test
    fun `a queued save is written to the conversation that scheduled it`(): Unit = runBlocking {
        // The write must carry its conversation. An earlier version read the open
        // conversation when the write finally ran, so starting a new chat while a
        // save was still queued redirected it: the old conversation lost its
        // message and an empty new one was written in its place.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }

        vm.send("this belongs to the first conversation")
        vm.newChat()

        val saved = awaitSaved { list -> list.any { it.messages.any { m -> m.text == "this belongs to the first conversation" } } }
        val holder = saved.first { it.messages.isNotEmpty() }
        assertEquals(
            "The question must be stored with the conversation it was asked in",
            "this belongs to the first conversation",
            holder.messages.first().text,
        )
    }

    @Test
    fun `a reply is never committed into a different conversation`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }

        vm.send("question one")
        vm.await { it.generating }
        engine.emit(GenerationEvent.Token("partial answer"))
        vm.await { it.streaming == "partial answer" }

        vm.newChat()
        engine.emit(GenerationEvent.Done("late answer", 10, 1))

        assertTrue(
            "The new chat must stay empty: the reply belongs to the previous one",
            vm.state.value.messages.none { it.text == "late answer" },
        )
    }

    @Test
    fun `a failed generation keeps whatever was streamed`(): Unit = runBlocking {
        // The user watched those tokens arrive. Discarding them on failure is the
        // same loss stop() exists to prevent.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        vm.await { it.generating }
        engine.emit(GenerationEvent.Token("half an ans"))
        vm.await { it.streaming == "half an ans" }

        engine.emit(GenerationEvent.Failed(java.io.IOException("delegate crashed")))

        val state = vm.await { !it.generating && it.error != null }
        assertEquals("half an ans", state.messages.last().text)
        assertTrue("A partial reply must be marked as interrupted", state.messages.last().interrupted)
    }

    @Test
    fun `leaving mid-reply writes the part that arrived`(): Unit = runBlocking {
        // Back instead of Stop is not a statement that the tokens were unwanted. The
        // write happens after viewModelScope has been cancelled, which is why it goes
        // to closingWrites and not there -- and why this test can see it finish: the
        // scope it used to build at the call site was invisible to everything,
        // including the harness that swaps the dispatcher.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        vm.await { it.generating }
        engine.emit(GenerationEvent.Token("half an ans"))
        vm.await { it.streaming == "half an ans" }

        vm.onScreenClosed()

        val saved = awaitSaved { conversations ->
            conversations.any { it.messages.lastOrNull()?.text == "half an ans" }
        }.first { it.messages.lastOrNull()?.text == "half an ans" }
        assertTrue(
            "A reply cut short by the screen closing must be marked as interrupted",
            saved.messages.last().interrupted,
        )
    }

    @Test
    fun `uninstalling the last model disables the composer`(): Unit = runBlocking {
        // Leaving the model id set kept the composer enabled while nothing was
        // loaded, so a message could be sent that had nothing to answer it.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        assertTrue(vm.state.value.canSend)

        java.io.File(modelStore.modelFile(model).absolutePath).delete()
        vm.refresh()

        val state = vm.await { it.modelId == null }
        assertFalse("Nothing can be answered without a model", state.canSend)
        assertTrue(state.needsAModel)
    }

    @Test
    fun `refresh does not roll a conversation back to an older file copy`(): Unit = runBlocking {
        // A save can be in flight while refresh() reads. Accepting the older copy
        // would make a committed reply vanish from the screen, and the next write
        // would erase it from disk too.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("hi", 10, 1))
        vm.await { it.messages.size == 2 }

        // Put an older version of the same conversation on disk.
        //
        // Written straight to the file, not through the store. The store refuses
        // to write anything older than what it holds, so `chatStore.save(older)`
        // is silently dropped -- which is what an earlier version of this test
        // did, leaving it to assert on a state that was never created and
        // exercising a guard it never reached.
        //
        // Read through the awaiting helper: saving is asynchronous, so reading the
        // directory right after the screen updates raced the write and sometimes
        // found nothing at all.
        val current = awaitSaved { it.firstOrNull()?.messages?.size == 2 }.first()
        val file = File(root, "chats/${current.id}.json")
        assertTrue("The conversation file should exist", file.isFile)
        file.writeText(
            Json.encodeToString(current.copy(messages = emptyList(), updatedAt = 1L))
        )

        vm.refresh()

        Thread.sleep(200)
        assertEquals(
            "The newer in-memory transcript must win over an older file",
            2,
            vm.state.value.messages.size,
        )
    }

    @Test
    fun `starting a new chat resets the runtime conversation`(): Unit = runBlocking {
        // The runtime keeps one conversation carrying the model's memory. Without
        // a reset the model keeps answering from the previous chat while the
        // screen shows an empty one.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        val before = engine.resetCount

        vm.newChat()

        assertTrue("newChat must reset the runtime's conversation", engine.resetCount > before)
    }

    @Test
    fun `a storage failure is reported rather than crashing`(): Unit = runBlocking {
        // Saving is asynchronous and its coroutine has no exception handler, so an
        // unhandled write error would take the process down and lose the
        // conversation with it.
        installModel()

        // A real store for everything except writing, so the failure is the only
        // difference from production.
        val real = FileChatStore(File(root, "chats"))
        val failing = object : ChatStore {
            override val unreadable: Int get() = real.unreadable
            override suspend fun list() = real.list()
            override suspend fun load(id: String) = real.load(id)
            override suspend fun save(conversation: Conversation) =
                throw java.io.IOException("no space left on device")

            override suspend fun delete(id: String) = real.delete(id)
            override suspend fun restore(conversations: List<Conversation>) =
                throw java.io.IOException("no space left on device")

            override suspend fun bytesUsed() = real.bytesUsed()
            override suspend fun sizes() = real.sizes()
        }

        val vm = ChatViewModel(failing, engine, modelStore)
        vm.await { it.modelId != null }

        vm.send("hello")

        val state = vm.await { it.error != null }
        assertEquals(
            "The failure must reach the user as the save message",
            R.string.err_conversation_save,
            state.error!!.textRes,
        )
    }

    // ---------------------------------------------------- round-2 regressions ---

    @Test
    fun `a recreated view model adopts a model the engine has already loaded`(): Unit = runBlocking {
        // The engine is application-scoped, so it outlives the ViewModel. One
        // recreated while the engine is still Ready used to publish no model id
        // at all: the composer stayed disabled for the rest of the session, and
        // with a single model installed the picker is disabled too, so there was
        // no way back to a working app.
        installModel()
        val first = newViewModel()
        first.await { it.modelId != null }

        // A second ViewModel over the same engine is what a relaunch looks like
        // while the process is still cached.
        val second =
            tracked({ ChatViewModel(chatStore, engine, modelStore) }, ChatViewModel::class.java)
        val adopted = second.await { it.modelId != null }

        assertEquals("The loaded model must be adopted, not ignored", model.id, adopted.modelId)
        assertTrue("The composer must work again", adopted.canSend)
    }

    @Test
    fun `a reply stays the newer version when the clock moves backwards`(): Unit = runBlocking {
        // `updatedAt` is the store's ordering key. With a plain clock reading, a
        // device whose time jumps back makes the reply's save look older than the
        // question's, and the store keeps the older one: the reply is on screen
        // and not on disk.
        //
        // The clock is driven, not read. Without that this test passed even with
        // the fix removed, because a real run never produces a backwards jump --
        // which is exactly what an earlier version of it did.
        installModel()
        var now = 10_000L
        val vm = ChatViewModel(chatStore, engine, modelStore) { now }
        vm.await { it.modelId != null }

        vm.send("hello")
        val afterQuestion = awaitSaved { it.firstOrNull()?.messages?.size == 1 }.first()
        // The driven clock is in force, not the system one. Not an exact value:
        // the conversation itself was created at 10_000, and stamping a change
        // never reuses the timestamp it replaces.
        assertTrue(
            "The driven clock must be the one in force: was ${afterQuestion.updatedAt}",
            afterQuestion.updatedAt in 10_000L..10_100L,
        )

        // The clock jumps back before the reply is committed.
        now = 1L
        engine.emit(GenerationEvent.Done("hi", 10, 1))
        vm.await { it.messages.size == 2 }

        val onDisk = awaitSaved { it.firstOrNull()?.messages?.size == 2 }.first()
        assertTrue(
            "The reply must be stamped later than the question it follows, even " +
                "though the clock went backwards: was ${onDisk.updatedAt}",
            onDisk.updatedAt > afterQuestion.updatedAt,
        )
        assertEquals(2, onDisk.messages.size)
    }

    @Test
    fun `a save cannot be reverted by an older copy already on disk`(): Unit = runBlocking {
        // An out-of-order write must not revert the file: the store keeps the
        // newer of the two versions.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("hi", 10, 1))
        vm.await { it.messages.size == 2 }
        val current = awaitSaved { it.firstOrNull()?.messages?.size == 2 }.first()

        saveNow(current.copy(messages = emptyList(), updatedAt = 1L))

        val onDisk = listNow().first()
        assertEquals("An older write must not reach the file", 2, onDisk.messages.size)
    }
    @Test
    fun `opening a conversation from history seeds the model with it`(): Unit = runBlocking {
        // Opening an older conversation used to leave the model with no memory of
        // a transcript the screen was showing: it answered a twenty-message chat
        // as if it were blank, so the same question got a different answer before
        // and after a restart.
        installModel()
        val first = newViewModel()
        first.await { it.modelId != null }
        first.send("what colour is the sky")
        engine.emit(GenerationEvent.Done("blue", 10, 1))
        first.await { it.messages.size == 2 }
        val saved = awaitSaved { it.firstOrNull()?.messages?.size == 2 }.first()

        // A fresh ViewModel is what a relaunch looks like. It opens the most
        // recent conversation by itself, so start a new one first -- otherwise
        // openConversation would correctly do nothing.
        val second =
            tracked({ ChatViewModel(chatStore, engine, modelStore) }, ChatViewModel::class.java)
        second.await { it.modelId != null }
        second.newChat()
        assertTrue("The new chat is empty", second.state.value.messages.isEmpty())

        second.openConversation(saved.id)

        val opened = second.await { it.messages.size == 2 }
        assertEquals(listOf("what colour is the sky", "blue"), opened.messages.map { it.text })
        // The transcript appears before the runtime is rebuilt, so waiting on it
        // is not waiting for this. Wait for the seed itself.
        val seeded = runBlocking {
            withTimeout(AWAIT_TIMEOUT_MS) {
                while (engine.seeded.isEmpty()) delay(5)
                engine.seeded
            }
        }
        assertEquals(
            "The model must be told what the screen is showing",
            listOf("what colour is the sky", "blue"),
            seeded.map { it.text },
        )
        assertEquals(
            "A question is from the user and a reply is not",
            listOf(true, false),
            seeded.map { it.fromUser },
        )
    }

    @Test
    fun `opening a conversation that is gone leaves the screen alone`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("something")
        engine.emit(GenerationEvent.Done("a reply", 10, 1))
        vm.await { it.messages.size == 2 }

        vm.openConversation("a-conversation-that-does-not-exist")

        val state = vm.await { it.error != null }
        assertEquals(
            "The transcript must survive a missing file rather than blanking",
            2,
            state.messages.size,
        )
    }

    @Test
    fun `opening the conversation that is already open changes nothing`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("hi", 10, 1))
        vm.await { it.messages.size == 2 }
        val id = awaitSaved { it.firstOrNull()?.messages?.size == 2 }.first().id
        val resetsBefore = engine.resetCount

        vm.openConversation(id)

        assertEquals(
            "Re-opening the same conversation must not rebuild the runtime's",
            resetsBefore,
            engine.resetCount,
        )
    }

    // -------------------------------------------------- asking and re-asking ---

    @Test
    fun `asking again drops the old reply before generating`(): Unit = runBlocking {
        // The replaced reply must not be in the context of its replacement, or the
        // model answers its own previous answer rather than the question.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("what colour is the sky")
        engine.emit(GenerationEvent.Done("Blue.", 10, 1))
        vm.await { it.messages.size == 2 }

        vm.regenerate()
        vm.await { it.generating }
        engine.emit(GenerationEvent.Done("A deep blue.", 10, 1))

        val state = vm.await { !it.generating && it.messages.size == 2 }
        assertEquals(
            "Only the new answer remains",
            listOf("what colour is the sky", "A deep blue."),
            state.messages.map { it.text },
        )
    }

    @Test
    fun `asking again with nothing said does nothing`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }

        vm.regenerate()

        assertFalse(vm.state.value.generating)
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun `asking again is refused while a reply is arriving`(): Unit = runBlocking {
        // Two generations on one conversation would interleave their tokens.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        vm.await { it.generating }
        engine.emit(GenerationEvent.Token("par"))
        vm.await { it.streaming == "par" }

        vm.regenerate()

        assertEquals("The reply in progress is untouched", "par", vm.state.value.streaming)
    }

    // ---------------------------------------------------------- deleting one ---

    @Test
    fun `deleting a question deletes the answer that belongs to it`(): Unit = runBlocking {
        // A reply whose question is gone is a reply to nothing.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("hi", 10, 1))
        vm.await { it.messages.size == 2 }

        val questionId = vm.state.value.messages.first().id
        vm.deleteMessage(questionId)

        assertTrue("Both are gone", vm.state.value.messages.isEmpty())
    }

    @Test
    fun `deleting a reply leaves the question`(): Unit = runBlocking {
        // Which is the state asking again starts from.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("hi", 10, 1))
        vm.await { it.messages.size == 2 }

        val replyId = vm.state.value.messages.last().id
        vm.deleteMessage(replyId)

        assertEquals(listOf("hello"), vm.state.value.messages.map { it.text })
    }

    @Test
    fun `deleting one exchange leaves the others alone`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("first")
        engine.emit(GenerationEvent.Done("one", 10, 1))
        vm.await { it.messages.size == 2 }
        vm.send("second")
        engine.emit(GenerationEvent.Done("two", 10, 1))
        vm.await { it.messages.size == 4 }

        val secondQuestion = vm.state.value.messages[2].id
        vm.deleteMessage(secondQuestion)

        assertEquals(
            listOf("first", "one"),
            vm.state.value.messages.map { it.text },
        )
    }

    @Test
    fun `deleting a message that is not there changes nothing`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("hi", 10, 1))
        vm.await { it.messages.size == 2 }

        vm.deleteMessage("no-such-message")

        assertEquals(2, vm.state.value.messages.size)
    }

    @Test
    fun `a deletion is written to disk`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("hi", 10, 1))
        vm.await { it.messages.size == 2 }
        awaitSaved { it.firstOrNull()?.messages?.size == 2 }

        vm.deleteMessage(vm.state.value.messages.last().id)

        val onDisk = awaitSaved { it.firstOrNull()?.messages?.size == 1 }.first()
        assertEquals(listOf("hello"), onDisk.messages.map { it.text })
    }

    // ------------------------------------------------------ the title's owner ---

    @Test
    fun `a save from here does not put back a title deleted upstream`(): Unit = runBlocking {
        // The path: open a conversation, rename it on the history screen while
        // this screen still holds the copy it read, then say something else. A
        // save carries the whole conversation, so without the deferral in
        // saveNow() the rename is silently undone by the next message.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("the first thing")
        engine.emit(GenerationEvent.Done("a reply", 10, 1))
        vm.await { it.messages.size == 2 }
        val saved = awaitSaved { it.firstOrNull()?.messages?.size == 2 }.first()

        // The rename, as the history screen writes it.
        saveNow(saved.copy(title = "A better name"))

        vm.send("the second thing")
        engine.emit(GenerationEvent.Done("another reply", 10, 1))

        val after = awaitSaved { it.firstOrNull()?.messages?.size == 4 }.first()
        assertEquals("The rename survives", "A better name", after.title)
    }

    @Test
    fun `the first message still names a conversation that has no name yet`(): Unit = runBlocking {
        // The other half: this screen does decide the title, once, and that must
        // keep working.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }

        vm.send("What is a haiku?")
        engine.emit(GenerationEvent.Done("A short poem.", 10, 1))

        val saved = awaitSaved { it.firstOrNull()?.messages?.size == 2 }.first()
        assertEquals("What is a haiku?", saved.title)
    }

    // ------------------------------------------- the runtime's memory ---

    @Test
    fun `loading a model seeds the runtime with the conversation on screen`(): Unit = runBlocking {
        // The cold-start path, and the one that mattered most: the screen restores
        // the most recent conversation and then loads a model. Emptying the
        // runtime there left a long transcript on screen and nothing in the model,
        // so the next question was answered as if the chat had never happened --
        // and re-picking the same conversation was a no-op, so the user could not
        // repair it.
        installModel()
        saveNow(
            Conversation(
                id = "c1",
                title = "Earlier",
                modelId = "smollm2-360m",
                createdAt = 1_000,
                updatedAt = 2_000,
                messages = listOf(
                    ChatMessage("m1", MessageRole.USER, "an earlier question", 1_000),
                    ChatMessage("m2", MessageRole.ASSISTANT, "an earlier answer", 2_000, modelId = model.id),
                ),
            ),
        )

        val vm = newViewModel()
        vm.await { it.modelId != null }

        val seed = awaitSeeded { it.isNotEmpty() }
        assertEquals(
            listOf("an earlier question", "an earlier answer"),
            seed.map { it.text },
        )
    }

    @Test
    fun `asking again seeds everything except the question it is about to send`(): Unit = runBlocking {
        // Seeding with the question included put it in the runtime twice -- once
        // in the history, once as the prompt -- so the model answered from a
        // context the app neither shows nor stores.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("the first question")
        engine.emit(GenerationEvent.Done("the first answer", 10, 1))
        vm.await { it.messages.size == 2 }

        val resetsBefore = engine.resetCount
        vm.regenerate()
        // Waited for the reset itself. The seed starts empty, so waiting for it to
        // be empty would pass before the reset had happened at all.
        val seed = awaitSeeded { engine.resetCount > resetsBefore }

        assertTrue(
            "Nothing before the first question, which is what a fresh send has too: " +
                seed.map { it.text },
            seed.isEmpty(),
        )
    }

    @Test
    fun `asking again is refused when no model is loaded`(): Unit = runBlocking {
        // A failed load leaves the transcript on screen with an error banner, so
        // "ask again" is offered and enabled. Without this guard it trimmed the
        // reply out of the transcript and off disk, then failed to produce a
        // replacement: the question was left unanswered and the old answer gone,
        // with nothing to undo it.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("a question")
        engine.emit(GenerationEvent.Done("an answer", 10, 1))
        vm.await { it.messages.size == 2 }
        awaitSaved { it.firstOrNull()?.messages?.size == 2 }

        // The model goes away underneath the screen.
        runBlocking { engine.unload() }

        vm.regenerate()

        assertEquals(
            "The answer is still there",
            listOf("a question", "an answer"),
            vm.state.value.messages.map { it.text },
        )
        val onDisk = awaitSaved { list -> list.any { it.messages.size == 2 } }
            .first { it.messages.size == 2 }
        assertEquals("And still on disk", listOf("a question", "an answer"), onDisk.messages.map { it.text })
    }

    @Test
    fun `a rename survives a reply committed after it`(): Unit = runBlocking {
        // The rename happens on the history screen while this one is mid-reply,
        // which is exactly when it is easiest to do and hardest to notice.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("a question")
        engine.emit(GenerationEvent.Done("an answer", 10, 1))
        vm.await { it.messages.size == 2 }
        val saved = awaitSaved { it.firstOrNull()?.messages?.size == 2 }.first()

        saveNow(saved.copy(title = "Renamed while you were reading"))

        vm.send("a second question")
        engine.emit(GenerationEvent.Done("a second answer", 10, 1))

        val after = awaitSaved { it.firstOrNull()?.messages?.size == 4 }.first()
        assertEquals("Renamed while you were reading", after.title)
    }

    @Test
    fun `stopping mid-reply rebuilds the runtime from what is on screen`(): Unit = runBlocking {
        // The runtime was cancelled mid-turn and what it holds afterwards is its
        // own business. The transcript has just gained a message it was never told
        // about, so the two are put back into agreement.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("a question")
        vm.await { it.generating }
        engine.emit(GenerationEvent.Token("a partial"))
        // Emitted is not the same as collected. Stopping before the fragment
        // arrives keeps nothing, which is a different test from this one.
        vm.await { it.streaming == "a partial" }

        vm.stop()

        val seed = awaitSeeded { it.size == 2 }
        assertEquals(
            "The question and the part of the answer that survived",
            listOf("a question", "a partial"),
            seed.map { it.text },
        )
    }

    // ------------------------------------------------------- editing a question ---

    @Test
    fun `editing the last question asks the new one`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("what colour is the sky")
        engine.emit(GenerationEvent.Done("blue", 10, 1))
        vm.await { it.messages.size == 2 }
        val question = vm.await { it.messages.size == 2 }.messages.first()

        engine.lastPrompt = null
        vm.editAndResend(question.id, "中国的首都是哪里")

        assertEquals("中国的首都是哪里", engine.lastPrompt)
    }

    @Test
    fun `the edited question replaces the old one on screen`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("old question")
        engine.emit(GenerationEvent.Done("an answer", 10, 1))
        vm.await { it.messages.size == 2 }
        val question = vm.await { it.messages.size == 2 }.messages.first()

        vm.editAndResend(question.id, "new question")

        assertEquals(
            "The old wording must not still be on screen",
            "new question",
            vm.await { it.messages.first().text == "new question" }.messages.first().text,
        )
    }

    @Test
    fun `the old answer is dropped before the new question is asked`(): Unit = runBlocking {
        // The reply was to a question the user has just corrected. Leaving it in
        // the context would have the model answering its own previous answer.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("old question")
        engine.emit(GenerationEvent.Done("an answer", 10, 1))
        vm.await { it.messages.size == 2 }
        val question = vm.await { it.messages.size == 2 }.messages.first()

        engine.lastPrompt = null
        vm.editAndResend(question.id, "new question")

        vm.await { it.generating }
        assertTrue(
            "The old answer must be gone from the screen before the new one arrives",
            !vm.state.value.messages.any { it.text == "an answer" },
        )
    }

    @Test
    fun `editing the first question updates the conversation's title`(): Unit = runBlocking {
        // The title comes from the first question, so the history list keeps
        // naming the conversation after something the user changed their mind
        // about unless this follows.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("original title")
        engine.emit(GenerationEvent.Done("ok", 10, 1))
        vm.await { it.messages.size == 2 }
        val question = vm.await { it.messages.size == 2 }.messages.first()

        vm.editAndResend(question.id, "改名之后")

        // Give the save a moment, then read what is actually on disk. Waiting with
        // a predicate and then reporting only "it timed out" hides the answer.
        Thread.sleep(600)
        val stored = listNow()
        assertTrue(
            "The stored title is " + stored.map { it.title } + ", and the screen has " +
                vm.state.value.messages.map { it.role },
            stored.any { it.title == "改名之后" },
        )
    }

    @Test
    fun `an earlier question cannot be edited`(): Unit = runBlocking {
        // Changing an earlier question invalidates every answer after it, and
        // discarding those is a bigger thing than the menu item can offer. The
        // ViewModel must refuse even if the screen offers it by mistake.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("first")
        engine.emit(GenerationEvent.Done("a", 10, 1))
        vm.await { it.messages.size == 2 }
        vm.send("second")
        engine.emit(GenerationEvent.Done("b", 10, 1))
        vm.await { it.messages.size == 4 }
        val first = vm.state.value.messages.first()

        engine.lastPrompt = null
        vm.editAndResend(first.id, "changed")

        assertNull("An earlier question must not be resendable", engine.lastPrompt)
        assertEquals("first", vm.state.value.messages.first().text)
    }

    @Test
    fun `an empty edit does nothing`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("a question")
        engine.emit(GenerationEvent.Done("an answer", 10, 1))
        vm.await { it.messages.size == 2 }
        val question = vm.await { it.messages.size == 2 }.messages.first()

        engine.lastPrompt = null
        vm.editAndResend(question.id, "   ")

        assertNull("Blank text must not be sent", engine.lastPrompt)
        assertEquals("a question", vm.state.value.messages.first().text)
    }

    @Test
    fun `editing while an answer is being written is ignored`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("a question")
        engine.emit(GenerationEvent.Done("an answer", 10, 1))
        vm.await { it.messages.size == 2 }
        val question = vm.await { it.messages.size == 2 }.messages.first()

        vm.send("second")
        vm.await { it.generating }
        engine.lastPrompt = null
        vm.editAndResend(question.id, "changed")

        assertNull("Editing mid-generation must not start a second request", engine.lastPrompt)
    }

    // ------------------------------------------------------------ pinned ---

    @Test
    fun `sending a message does not undo a pin`(): Unit = runBlocking {
        // The chat screen saves the whole conversation, and it never sets the pin
        // -- so without the guard in withCurrentTitle it writes back whatever the
        // pin was when the conversation was opened, which is nothing. The user
        // pins a conversation, types one message, and watches the pin disappear.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("first")
        engine.emit(GenerationEvent.Done("a reply", 10, 1))
        vm.await { it.messages.size == 2 }

        val saved = awaitSaved { it.firstOrNull()?.messages?.size == 2 }.first()
        saveNow(saved.copy(pinnedAt = 1_700_000_000_000L))

        vm.send("second")
        engine.emit(GenerationEvent.Done("another reply", 10, 1))

        val after = awaitSaved { it.firstOrNull()?.messages?.size == 4 }.first()
        assertEquals(
            "The pin must survive a save from a screen that does not own it",
            1_700_000_000_000L,
            after.pinnedAt,
        )
    }

    @Test
    fun `a pin set while this screen is open is picked up`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("first")
        engine.emit(GenerationEvent.Done("a reply", 10, 1))
        vm.await { it.messages.size == 2 }
        val saved = awaitSaved { it.firstOrNull()?.messages?.size == 2 }.first()

        saveNow(saved.copy(pinnedAt = 42L))
        vm.refresh()

        vm.send("second")
        engine.emit(GenerationEvent.Done("another", 10, 1))

        val after = awaitSaved { it.firstOrNull()?.messages?.size == 4 }.first()
        assertEquals(42L, after.pinnedAt)
    }

    // ------------------------------------------------------------ searching ---

    @Test
    fun `searching shows only what matched, and the count says so`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("the sky is blue")
        engine.emit(GenerationEvent.Done("because of scattering", 10, 1))
        vm.await { it.messages.size == 2 }
        vm.send("what about grass")
        engine.emit(GenerationEvent.Done("chlorophyll", 10, 1))
        vm.await { it.messages.size == 4 }

        vm.setSearchQuery("sky")

        val state = vm.await { it.searching }
        assertEquals("Only the matching message is shown", 1, state.visibleMessages.size)
        assertEquals(4, state.messages.size)
        assertTrue("The count must be computable from the state", state.visibleMessages.size < state.messages.size)
    }

    @Test
    fun `clearing the search puts the whole conversation back`(): Unit = runBlocking {
        // The transcript is never actually lost -- this is a filter over the same
        // list -- but a user who cannot see their messages back has no way to know
        // that, and this is the test that says the way out works.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("alpha")
        engine.emit(GenerationEvent.Done("beta", 10, 1))
        vm.await { it.messages.size == 2 }

        vm.setSearchQuery("alpha")
        vm.await { it.visibleMessages.size == 1 }
        vm.setSearchQuery("")

        val state = vm.await { !it.searching }
        assertEquals(2, state.visibleMessages.size)
    }

    @Test
    fun `a search that matches nothing says so rather than looking empty`(): Unit = runBlocking {
        // "No messages" and "no messages matched" are different, and a user who
        // searched their own conversation is entitled to know which one they are
        // looking at.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("alpha")
        engine.emit(GenerationEvent.Done("beta", 10, 1))
        vm.await { it.messages.size == 2 }

        vm.setSearchQuery("nothing here matches this")

        val state = vm.await { it.searchFoundNothing }
        assertTrue(state.searching)
        assertTrue("The conversation is still there", state.messages.size == 2)
    }

    @Test
    fun `searching does not touch the runtime or the stored conversation`(): Unit = runBlocking {
        // Finding a word is not asking a question. A search that reseeded the
        // runtime would make it cost the same as a turn, and one that wrote to disk
        // would make a read look like a change.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("alpha")
        engine.emit(GenerationEvent.Done("beta", 10, 1))
        vm.await { it.messages.size == 2 }

        // Wait for the save, not just for the screen. The screen shows a message
        // a few milliseconds before it reaches disk, and reading straight away
        // finds an empty directory rather than an unchanged one.
        //
        // Both reads name the two-message conversation for the same reason: the
        // version on disk while the model is loading has no messages, and a
        // predicate that accepted it would compare zero with zero and pass
        // without having looked at the conversation at all.
        val resetsBefore = engine.resetCount
        val onDiskBefore = awaitSaved { list -> list.any { it.messages.size == 2 } }
            .first { it.messages.size == 2 }
            .messages.size

        vm.setSearchQuery("alpha")
        vm.await { it.searching }

        assertEquals("A search must not rebuild the runtime", resetsBefore, engine.resetCount)
        val onDiskAfter = awaitSaved { list -> list.any { it.messages.size == 2 } }
            .first { it.messages.size == 2 }
            .messages.size
        assertEquals(onDiskBefore, onDiskAfter)
    }

    @Test
    fun `a search ignores case`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("Alpha Beta")
        engine.emit(GenerationEvent.Done("gamma", 10, 1))
        vm.await { it.messages.size == 2 }

        vm.setSearchQuery("alpha beta")

        assertEquals(1, vm.await { it.searching }.visibleMessages.size)
    }

    // ------------------------------------------------------------ reasoning ---

    @Test
    fun `a reasoning trace is kept on the reply and out of the answer`(): Unit = runBlocking {
        // The bug this covers: a reasoning model's working arrived as ordinary text
        // and was shown as the reply, with the actual answer buried in it.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }

        vm.send("what is 6 times 7")
        engine.emit(
            GenerationEvent.Done(
                fullText = "42.",
                elapsedMillis = 10,
                tokenCount = 4,
                reasoning = "6 times 7 is 42, and the answer is just the number",
            ),
        )

        val state = vm.await { it.messages.size == 2 }
        val reply = state.messages.last()
        assertEquals("42.", reply.text)
        assertEquals(
            "The trace is kept, not discarded",
            "6 times 7 is 42, and the answer is just the number",
            reply.reasoning,
        )
    }

    @Test
    fun `a reply with no trace carries none`(): Unit = runBlocking {
        // Null, not an empty string: the screen decides whether to render the
        // section by looking for a trace, and an empty one would draw an empty box.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("hi", 10, 1))

        val state = vm.await { it.messages.size == 2 }
        assertEquals(null, state.messages.last().reasoning)
    }

    @Test
    fun `thinking without answering is reported rather than silent`(): Unit = runBlocking {
        // Measured behaviour of reasoning models on a small token budget: the whole
        // budget goes on the trace and no answer arrives. Before this the message
        // was dropped, leaving a stopped spinner and an unchanged transcript --
        // indistinguishable from the app having ignored the question.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }

        vm.send("a hard question")
        engine.emit(
            GenerationEvent.Done(
                fullText = "",
                elapsedMillis = 10,
                tokenCount = 40,
                reasoning = "let me think about this",
            ),
        )

        val state = vm.await { it.error != null }
        assertEquals(R.string.chat_reasoned_only, state.error!!.textRes)
        assertEquals("Nothing is recorded, because there is no answer", 1, state.messages.size)
        assertFalse("The screen must not be left generating", state.generating)
    }

    @Test
    fun `an empty reply with no trace stays quiet`(): Unit = runBlocking {
        // A model that produced nothing at all is not the same as one that thought
        // and stopped, and telling the user it "thought" would be a fabrication.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("", 10, 0))

        val state = vm.await { !it.generating }
        assertEquals(null, state.error)
    }

    // ------------------------------------------------------- reply versions ---

    @Test
    fun `regenerating keeps the answer it replaced`(): Unit = runBlocking {
        // It used to delete it -- from the transcript and from disk, with nothing
        // to undo it. That is the only irreversible thing on this screen, and it
        // made asking again a decision the user could not take back.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("what is 6 times 7")
        engine.emit(GenerationEvent.Done("42", 10, 1))
        vm.await { it.messages.size == 2 }

        vm.regenerate()
        engine.emit(GenerationEvent.Done("forty-two", 10, 1))

        val state = vm.await { it.messages.size == 2 && it.messages.last().text == "forty-two" }
        val reply = state.messages.last()
        assertEquals("The new answer is shown", "forty-two", reply.text)
        assertEquals(
            "The replaced answer is still there",
            listOf("42", "forty-two"),
            reply.versions,
        )
        assertEquals(1, reply.versionIndex)
    }

    @Test
    fun `switching back to an earlier version changes what is shown`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("first", 10, 1))
        vm.await { it.messages.size == 2 }
        vm.regenerate()
        engine.emit(GenerationEvent.Done("second", 10, 1))
        val id = vm.await { it.messages.last().versions.size == 2 }.messages.last().id
        // Wait for the regeneration to reach disk before flipping. Without
        // this the version change races the save that was already in flight
        // for the reply it is changing.
        awaitSaved { it.firstOrNull()?.messages?.last()?.text == "second" }

        vm.selectVersion(id, 0)

        val state = vm.await { it.messages.last().text == "first" }
        assertEquals(0, state.messages.last().versionIndex)
        assertEquals(listOf("first", "second"), state.messages.last().versions)
    }

    @Test
    fun `switching a version rebuilds the runtime from what is on screen`(): Unit = runBlocking {
        // Otherwise the model keeps answering from whichever version it was last
        // told about, and the next reply contradicts the one above it with nothing
        // on screen explaining why.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("first", 10, 1))
        vm.await { it.messages.size == 2 }
        vm.regenerate()
        engine.emit(GenerationEvent.Done("second", 10, 1))
        val id = vm.await { it.messages.last().versions.size == 2 }.messages.last().id
        val resetsBefore = engine.resetCount

        vm.selectVersion(id, 0)
        vm.await { it.messages.last().text == "first" }

        assertTrue("The runtime must be rebuilt", engine.resetCount > resetsBefore)
        assertEquals(
            "Seeded with the version now on screen",
            listOf("hello", "first"),
            engine.seeded.map { it.text },
        )
    }

    @Test
    fun `a version change is written to disk`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("first", 10, 1))
        vm.await { it.messages.size == 2 }
        vm.regenerate()
        engine.emit(GenerationEvent.Done("second", 10, 1))
        val id = vm.await { it.messages.last().versions.size == 2 }.messages.last().id
        // Wait for the regeneration to reach disk before flipping. Without
        // this the version change races the save that was already in flight
        // for the reply it is changing.
        awaitSaved { it.firstOrNull()?.messages?.last()?.text == "second" }

        vm.selectVersion(id, 0)

        val saved = awaitSaved { it.firstOrNull()?.messages?.last()?.text == "first" }.first()
        assertEquals(listOf("first", "second"), saved.messages.last().versions)
    }

    @Test
    fun `editing a question does not offer the old answer as a version`(): Unit = runBlocking {
        // The question changed, so the old answer answers a different question.
        // Offering it as a version of this one would put two unrelated answers side
        // by side and call them alternatives.
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("question one")
        engine.emit(GenerationEvent.Done("answer one", 10, 1))
        val questionId = vm.await { it.messages.size == 2 }.messages.first().id

        vm.editAndResend(questionId, "question two")
        engine.emit(GenerationEvent.Done("answer two", 10, 1))

        val state = vm.await { it.messages.last().text == "answer two" }
        assertTrue(
            "No version history, because there is no earlier version of this answer",
            state.messages.last().versions.isEmpty(),
        )
    }

    @Test
    fun `an out-of-range version selection does nothing`(): Unit = runBlocking {
        installModel()
        val vm = newViewModel()
        vm.await { it.modelId != null }
        vm.send("hello")
        engine.emit(GenerationEvent.Done("only", 10, 1))
        val id = vm.await { it.messages.size == 2 }.messages.last().id

        vm.selectVersion(id, 7)
        vm.selectVersion(id, -1)

        assertEquals("only", vm.await { !it.generating }.messages.last().text)
    }

    // --------------------------------------------------------- memory check ---

    @Test
    fun `a model too big for the device is asked about rather than loaded`(): Unit = runBlocking {
        // A load that runs out of memory can end the process with nothing on
        // screen to explain it, and that cannot be caught from here. Asking first
        // can -- and the ask has to carry both numbers, or the user cannot decide.
        installModel()
        val vm = tracked(
            {
                ChatViewModel(
                    chatStore,
                    engine,
                    modelStore,
                    deviceMemory = DeviceMemory(2L * 1024 * 1024 * 1024),
                )
            },
            ChatViewModel::class.java,
        )
        // Waiting for the default model to finish loading first. `modelId == null`
        // returns immediately -- the initial state is null -- and selectModel is
        // refused while a load is in flight, so the selection would be dropped.
        vm.await { it.modelId != null }

        // The catalogue's largest model needs ~2.9 GB; a 2 GB device's budget is
        // ~1.2 GB.
        val biggest = ModelCatalogue.models.maxByOrNull { it.sizeBytes }!!
        vm.selectModel(biggest)

        val state = vm.await { it.ramWarning != null }
        assertEquals(biggest.id, state.ramWarning!!.descriptor.id)
        assertTrue("It says what the model needs", state.ramWarning!!.requiredBytes > 0)
        assertTrue("It says what the device allows", state.ramWarning!!.budgetBytes > 0)
        assertFalse("Nothing is loading until the user says so", state.loadingModel)
    }

    @Test
    fun `proceeding with the warning asks for the load`(): Unit = runBlocking {
        // The user knows things the estimate does not -- what else is running, and
        // whether they are willing to have it fail. Refusing outright would take
        // away a choice they can reasonably make.
        installModel()
        val vm = tracked(
            {
                ChatViewModel(
                    chatStore,
                    engine,
                    modelStore,
                    deviceMemory = DeviceMemory(2L * 1024 * 1024 * 1024),
                )
            },
            ChatViewModel::class.java,
        )
        vm.await { it.modelId != null }
        val biggest = ModelCatalogue.models.maxByOrNull { it.sizeBytes }!!
        vm.selectModel(biggest)
        vm.await { it.ramWarning != null }

        vm.confirmRamWarning()

        val state = vm.await { it.ramWarning == null }
        assertTrue("The load is under way", state.loadingModel || state.modelId != null)
    }

    @Test
    fun `dismissing the warning loads nothing`(): Unit = runBlocking {
        installModel()
        val vm = tracked(
            {
                ChatViewModel(
                    chatStore,
                    engine,
                    modelStore,
                    deviceMemory = DeviceMemory(2L * 1024 * 1024 * 1024),
                )
            },
            ChatViewModel::class.java,
        )
        vm.await { it.modelId != null }
        vm.selectModel(ModelCatalogue.models.maxByOrNull { it.sizeBytes }!!)
        vm.await { it.ramWarning != null }

        vm.dismissRamWarning()

        val state = vm.await { it.ramWarning == null }
        assertFalse("Nothing new is loading", state.loadingModel)
        assertTrue(
            "The big model was not loaded",
            state.modelId != ModelCatalogue.models.maxByOrNull { it.sizeBytes }!!.id,
        )
    }

    @Test
    fun `a device with room is not asked about anything`(): Unit = runBlocking {
        // The common case has to stay quiet. A dialog on every load is one the
        // user learns to dismiss without reading.
        installModel()
        val vm = tracked(
            {
                ChatViewModel(
                    chatStore,
                    engine,
                    modelStore,
                    deviceMemory = DeviceMemory(16L * 1024 * 1024 * 1024),
                )
            },
            ChatViewModel::class.java,
        )

        val state = vm.await { it.modelId != null }
        assertEquals(null, state.ramWarning)
    }

    @Test
    fun `a device that will not report its memory asks nothing`(): Unit = runBlocking {
        // Zero means "not known", not "no memory". Asking on every load because the
        // total could not be read would be worse than not asking at all.
        installModel()
        val vm = newViewModel()

        val state = vm.await { it.modelId != null }
        assertEquals(null, state.ramWarning)
    }
}