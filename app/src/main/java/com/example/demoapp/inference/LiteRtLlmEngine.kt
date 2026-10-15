package com.example.demoapp.inference



import com.example.demoapp.R
import com.example.demoapp.ui.UiMessage
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [LlmEngine] backed by Google's LiteRT-LM.
 *
 * This is the only file that constructs a LiteRT-LM `Engine` or `Conversation`, and
 * the only one that references most of its API. That is deliberate: a Qualcomm-NPU
 * implementation is planned, and keeping the surface here means adding it touches
 * one class rather than the whole app.
 *
 * With one exception, stated because the sentence above used to claim there was
 * none: `LocalTools.kt` also imports from this library — `Tool` and `ToolSet`, the
 * annotations that make a method callable by the model. Those are not part of the
 * `LlmEngine` seam and cannot be reached through it, because "how does the runtime
 * discover a tool" is a question only the runtime's own annotation processor can
 * answer. So replacing the runtime means re-annotating or replacing that file too,
 * and anyone reading the seam as "one file to change" would have missed it.
 *
 * Two properties of the underlying library shaped the design:
 *
 *  - Sampling parameters are applied, and taking all four is the only way to get
 *    any of them. The runtime takes them together on a `ConversationConfig` when
 *    the conversation is created, and only `seed` has a default — so passing
 *    `temperature` alone would reset `topK` and `topP` to whatever the runtime
 *    picks, and passing none of them would ignore [GenerationConfig] entirely.
 *    All four come from the caller's `GenerationConfig`, through [configFor], so
 *    the app has one statement of what its sampling is. That also means the choice
 *    lands when a conversation is created, not while one is running, which is why
 *    the card says a change needs the model reloaded.
 *
 *    This paragraph used to say the opposite: that sampling was NOT applied, that
 *    no config was passed, and that "a settings screen for temperature would do
 *    nothing". It was accurate when written, the feature was built afterwards, and
 *    the sentence outlived it — a reader would have concluded the controls were
 *    inert and gone looking for the bug in working code.
 *
 *  - Holding two language models at once is how a phone app gets killed for
 *    memory. [load] therefore releases any existing model first, and there is no
 *    way to hold two -- including on the fallback path, where a backend that
 *    fails to initialise is closed before the next one is tried, and including the
 *    case where the previous generation will not let go, which [load] now refuses
 *    rather than loading beside.
 *
 * @param cacheDir directory the runtime may use for scratch files. Must be
 *   app-private; the runtime writes derived data here.
 */
class LiteRtLlmEngine(private val cacheDir: File) : LlmEngine {

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Serialises load/unload against each other. */
    private val lifecycle = Mutex()

    /**
     * Held for the whole of a generation.
     *
     * [load] and [unload] close native handles, so they must not run while a
     * generation holds a reference to one: closing a conversation that is
     * mid-decode in native code is a use-after-free in the runtime's own
     * structures. Acquiring this around the release is what makes that
     * impossible.
     */
    private val generationGate = Mutex()

    /**
     * Volatile because [generate] reads them without [lifecycle], and a stale
     * read would hand the flow a handle that has already been closed.
     */
    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var conversation: Conversation? = null

    /**
     * Which model [conversation] belongs to.
     *
     * Held separately so a failure can name the model even when the state has
     * already moved on. Reading it back out of [state] at the moment of failure
     * gives null whenever a concurrent load or release has changed the state,
     * and a failure that cannot name its model is one the UI cannot explain.
     */
    @Volatile
    private var loadedModelId: String? = null

    /** What the loaded conversations are told; see [load]. */
    @Volatile
    private var activeSystemPrompt: String = FALLBACK_SYSTEM_PROMPT

    /** Sampling for the loaded conversations; see [load]. */
    @Volatile
    private var activeSampling: GenerationConfig = GenerationConfig()

    override suspend fun load(
        model: LocalModel,
        requested: InferenceBackend,
        systemPrompt: String,
        sampling: GenerationConfig,
    ): Result<InferenceBackend> = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            // Fixed for this load. The runtime takes the prompt when a
            // conversation is created, and every conversation below is created
            // with it, so changing it means loading again.
            activeSystemPrompt = systemPrompt.ifBlank { FALLBACK_SYSTEM_PROMPT }
            activeSampling = sampling.copy(temperature = sampling.temperature.coerceIn(0.0, 2.0))

            // Released FIRST, before any early return. The check below used to
            // sit in front of this, so failing to find the new model left the
            // previous one loaded: its native handles stayed open holding
            // hundreds of megabytes to gigabytes, unreachable through any API,
            // and the next successful load put a second model beside it -- the
            // exact state this class documents as impossible.
            cancelNativeGeneration()
            val released = awaitGeneratorThen { releaseLocked() }
            if (!released) {
                // The runtime would not let go of the old model within the timeout,
                // so falling through would build a second `Engine` while the first is
                // still resident. On a phone that is two multi-gigabyte models at
                // once, which is a crash rather than a slow load -- and `releaseLocked`
                // is the only thing that ever calls `engine.close()`, so the second
                // load would leak the first outright.
                //
                // Failing here is the recoverable mistake. The old model is still
                // usable, and the user is told what happened instead of watching the
                // app die. Nothing else in this class reports a stuck runtime, so this
                // is where it gets a voice.
                _state.value = EngineState.Idle
                return@withLock Result.failure(
                    EngineLoadFailure(
                        UiMessage(R.string.load_fail_runtime_busy, model.displayName),
                        null,
                    )
                )
            }

            if (!model.file.isFile || model.file.length() == 0L) {
                // The path goes in the detail, not in the sentence. It is what
                // someone needs to diagnose the problem and it is not something to
                // read as Chinese.
                val detail = "missing or empty: ${model.file.absolutePath}"
                // Nothing was ever loaded, so the state stays where it was: the
                // reason travels on the failure, which is the only channel a caller
                // reads.
                return@withLock Result.failure(IllegalArgumentException(detail))
            }

            _state.value = EngineState.Loading(model.id)

            var lastError: Throwable? = null
            for (candidate in candidateOrder(requested)) {
                var created: Engine? = null
                var probe: Conversation? = null
                try {
                    created = Engine(
                        EngineConfig(
                            modelPath = model.file.absolutePath,
                            backend = candidate.toLiteRtBackend(),
                            cacheDir = cacheDir.absolutePath,
                        )
                    )
                    created.initialize()

                    // The backend is proved by RUNNING it, not by creating it.
                    //
                    // `initialize()` returns successfully on a device whose GPU
                    // delegate cannot work at all: "Can not find OpenCL library
                    // on this device" arrives on the first real inference, not
                    // here. So the fallback below never fired, the unusable
                    // accelerator was reported as Ready, and the failure landed on
                    // the user's first message as a native status code they could
                    // do nothing with.
                    //
                    // One token through a throwaway conversation is enough to
                    // make the delegate fail here, where there is still a next
                    // candidate to try.
                    probe = created.createConversation(configFor(emptyList()))
                    probe.sendMessageAsync(Message.user("hi"), maxOutputToken = 1).collect { }

                    // Closed here, and cleared, rather than left in the field: it
                    // was only ever a test of the delegate. An open conversation
                    // holds the native context it was created with for as long as
                    // the engine lives, and this one is never used again.
                    runCatching { probe?.close() }
                    probe = null

                    // The real conversation is created after the probe, so nothing
                    // the probe said is part of the user's context.
                    val createdConversation = created.createConversation(configFor(emptyList()))

                    engine = created
                    loadedModelId = model.id
                    conversation = createdConversation
                    _state.value = EngineState.Ready(model.id, candidate, readContextWindow(model.file))
                    return@withLock Result.success(candidate)
                } catch (t: Throwable) {
                    lastError = t
                    // Recorded so the next load does not pay for the same
                    // discovery. CPU is never recorded: it is the floor, and
                    // forgetting it would leave nothing to fall back to.
                    if (candidate != InferenceBackend.CPU) failedBackends.add(candidate)
                    // Close the LOCAL references, not the fields. The fields are
                    // only assigned once everything succeeded, so on this path
                    // they are still null and closing them would close nothing --
                    // leaking a native engine that has already mapped a
                    // multi-gigabyte model, and then loading a second one beside
                    // it.
                    runCatching { probe?.close() }
                    runCatching { created?.close() }
                    engine = null
                    conversation = null
                }
            }

            // Every candidate failed. The message the runtime produces is a
            // status code and a sentence about a missing system library, which
            // tells the person holding the phone nothing -- neither what
            // happened nor what to do. Say it in terms of the device, and carry
            // that sentence up on the failure rather than discarding it: it names
            // the cause (an accelerator the device does not have, an NPU that was
            // asked for and is absent) that the caller's own classifier cannot see.
            val message = explainTotalFailure(lastError, requested)
            // Nothing is loaded, so the state is idle rather than the loading that
            // was set above -- otherwise the engine would claim to be mid-load
            // forever after a failure.
            _state.value = EngineState.Idle
            Result.failure(EngineLoadFailure(message, lastError))
        }
    }

    /**
     * Unloads the model, best effort.
     *
     * The return value of [awaitGeneratorThen] is deliberately ignored here. A
     * timeout means the runtime is still winding a generation down, and there is no
     * caller waiting to be told: `unload` returns `Unit`, and the two places that
     * call it -- erasing everything, and leaving the screen -- are not in a position
     * to offer a next step. `load` is, which is why it checks.
     */
    override suspend fun unload(): Unit = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            cancelNativeGeneration()
            awaitGeneratorThen { releaseLocked() }
        }
    }

    override suspend fun resetConversation(history: List<EngineTurn>): Unit = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            cancelNativeGeneration()
            awaitGeneratorThen {
                val live = engine ?: return@awaitGeneratorThen
                // Built BEFORE the old one is closed. The other order leaves a
                // closed handle in the field if the runtime refuses to make a new
                // conversation -- on a low-memory device it can -- and the next
                // request then decodes on a closed handle, which is a native
                // crash rather than an exception.
                val next = live.createConversation(configFor(history))
                val previous = conversation
                conversation = next
                runCatching { previous?.close() }
            }
        }
    }

    /**
     * Explains that no backend worked, in terms of the device rather than the
     * runtime.
     *
     * Reached only when every candidate failed, so the useful statement is not
     * which one broke but that the model cannot run here at all.
     */
    private fun explainTotalFailure(lastError: Throwable?, requested: InferenceBackend): UiMessage {
        val raw = lastError?.message.orEmpty()
        val missingAccelerator = raw.contains("OpenCL", ignoreCase = true) ||
            raw.contains("delegate", ignoreCase = true) ||
            raw.contains("accelerator", ignoreCase = true)

        return when {
            missingAccelerator ->
                UiMessage(R.string.err_no_gpu_fell_back).withDetail(raw)

            lastError == null && requested == InferenceBackend.NPU ->
                UiMessage(R.string.err_no_npu)

            lastError == null -> UiMessage(R.string.err_model_load_failed)

            else -> UiMessage(R.string.err_model_load_failed).withDetail(raw)
        }
    }

    /**
     * The configuration a conversation is created with.
     *
     * Isolated because it is the only place that knows how a conversation is
     * set up: the runtime takes all of it at creation rather than per request, so
     * a conversation cannot be given a system instruction or memory after it
     * exists.
     */
    /**
     * The tool set the model can call, held for the life of this engine.
     *
     * One instance rather than one per conversation: the tools are stateless, and
     * a fresh instance per conversation would be a fresh reflection scan per
     * conversation for no gain.
     */
    private val activeTools = LocalTools()

    private fun configFor(history: List<EngineTurn>): ConversationConfig {
        return ConversationConfig(
            systemInstruction = Contents.of(activeSystemPrompt),
            initialMessages = history.map { turn ->
                if (turn.fromUser) Message.user(turn.text) else Message.model(turn.text)
            },
            // The sampling configuration, which is where the temperature setting
            // actually reaches the model.
            //
            // topK and topP have to be given here too: the runtime takes all four
            // values together, and only the seed has a default. That means this
            // overrides whatever the runtime would otherwise choose -- a real
            // trade, and the reason the three values are taken from
            // GenerationConfig, which is the app's single statement of what its
            // sampling should be, rather than repeated here.
            samplerConfig = SamplerConfig(
                topK = activeSampling.topK,
                topP = activeSampling.topP,
                temperature = activeSampling.temperature,
                seed = activeSampling.seed,
            ),
            // Tools the model can call.
            //
            // `automaticToolCalling` lets the runtime run the call and feed the
            // result back without the app in the loop, which is what makes this
            // work on a small model: it never has to be told what to do with the
            // answer, because it never sees the mechanics.
            //
            // The `tool(...)` call wraps the instance by reflection over its
            // annotated methods. Nothing here is registered by hand, so a method
            // added to LocalTools is available to the model without a second place
            // to remember.
            tools = listOf(tool(activeTools)),
            automaticToolCalling = true,
        )
    }

    private companion object {
        /**
         * How long to wait for a generation to release the conversation.
         *
         * Long enough for a native cancel to take effect on a loaded device;
         * short enough that a stuck engine is reported rather than blocking the
         * caller forever.
         */
        const val GENERATION_RELEASE_TIMEOUT_MS = 10_000L

        /**
         * A floor for a caller that asks for no particular prompt.
         *
         * Not a copy of the prompt the app uses. That one belongs to
         * `Settings.DEFAULT_SYSTEM_PROMPT`, which the settings screen shows and
         * the user can change, and the app always passes it here. This was a
         * byte-for-byte duplicate once, which meant editing either of them changed
         * nothing the user could see.
         *
         * It is not empty because of what a small model does with no system
         * instruction at all: given a conversation-shaped list of turns it
         * continues the document instead of answering it. Measured, on a 360M
         * model, as three hundred words of advice on storytelling in reply to
         * "hello".
         */
        const val FALLBACK_SYSTEM_PROMPT = "你是一个乐于助人的助手。"
    }

    override fun generate(prompt: String, config: GenerationConfig): Flow<GenerationEvent> = flow {
        val forModel = loadedModelId
        generationGate.withLock {
            val active = conversation
            if (active == null) {
                emit(GenerationEvent.Failed(IllegalStateException("No model loaded")))
                return@withLock
            }

            val startedAt = System.nanoTime()
            val assembler = ReplyAssembler()
            var finished = false

            try {
                active.sendMessageAsync(
                    message = Message.user(prompt),
                    maxOutputToken = config.maxTokens,
                ).collect { message ->
                    val fragment = message.textContent()
                    // ReplyAssembler documents the fragment contract and is where
                    // the text the previous heuristic dropped is explained.
                    val emitted = assembler.add(fragment)
                    if (emitted.isNotEmpty()) {
                        // emit() runs downstream code, and an exception from there
                        // is not the engine failing. Marked so the catch below can
                        // tell the two apart instead of blaming the model for
                        // someone else's bug.
                        try {
                            emit(GenerationEvent.Token(emitted))
                        } catch (t: Throwable) {
                            throw CollectorFailed(t)
                        }
                    }
                }
                finished = true

                // Whatever the splitter held back is released before the reply is
                // reported. Without this a reply that ended on a character which
                // could have started a tag loses its last characters, and the
                // streamed text and the finished text disagree.
                val tail = assembler.finish()
                if (tail.isNotEmpty()) {
                    emit(GenerationEvent.Token(tail))
                }

                emit(
                    GenerationEvent.Done(
                        fullText = assembler.text,
                        elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
                        tokenCount = assembler.fragmentCount,
                        reasoning = assembler.reasoning.ifBlank { null },
                        timing = readTiming(active),
                        contextTokens = readContextTokens(active),
                    )
                )
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    // Cancelling the collection stops the flow but not the native
                    // decode behind it, which would keep a core busy and keep
                    // competing with the next request on the same conversation.
                    runCatching { active.cancelProcess() }
                    throw t
                }
                if (t is CollectorFailed) throw t.cause ?: t

                // The runtime can leave a conversation permanently unusable. That
                // is reported through the event below, which is what a caller
                // actually collects; the state is deliberately left saying Ready,
                // because the model is still loaded and still usable for the next
                // conversation. A state that meant "the last attempt failed" would
                // be a second channel for the same fact, and the one that shipped
                // was the one nothing read.
                emit(GenerationEvent.Failed(t))
            } finally {
                if (!finished) runCatching { active.cancelProcess() }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------- internals ---

    /**
     * Waits for the running generation to let go of the conversation, then runs
     * [action] with the gate held. Returns whether [action] ran.
     *
     * Bounded. The generation releases the gate only after calling
     * `cancelProcess()`, a blocking native call, and if that call or the
     * runtime's wind-down never returns an unbounded wait would block load,
     * unload and reset forever while `lifecycle` is held -- leaving the engine
     * permanently unusable with no way back.
     *
     * On timeout the handles are deliberately NOT released, because releasing them
     * under a live generation is a native use-after-free. Wasting memory is the
     * recoverable of the two. What follows from that is the caller's problem, and
     * this returns `false` so the caller can tell the difference -- it used to
     * return nothing, and the two callers that needed to know both carried on as if
     * the model had been let go.
     */
    private suspend fun awaitGeneratorThen(action: () -> Unit): Boolean {
        // The action runs INSIDE withLock so the lock's own finally releases it,
        // including when the timeout cancels the wait. Acquiring and releasing in
        // separate steps would leave an unlock against an unlocked mutex the
        // moment the timeout fired.
        return withTimeoutOrNull(GENERATION_RELEASE_TIMEOUT_MS) {
            generationGate.withLock { action() }
            true
        } ?: false
    }

    /**
     * Asks the runtime to stop decoding.
     *
     * Deliberately not gated: its whole purpose is to make the generation release
     * the gate, so waiting for the gate here would deadlock. That is also why
     * `conversation` is `@Volatile` -- this is the one read that happens outside
     * the gate, and a stale read would send the cancel to a handle that is
     * already closed.
     *
     * Safe with nothing running: the runtime treats it as a request to stop the
     * current process, and there being none is not an error.
     */
    private fun cancelNativeGeneration() {
        runCatching { conversation?.cancelProcess() }
    }

    /** Must be called while holding [lifecycle] and [generationGate]. */
    private fun releaseLocked() {
        // Close before clearing, so a concurrent reader cannot see a non-null
        // handle that is already closed.
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        loadedModelId = null
        _state.value = EngineState.Idle
    }

    /**
     * Backends to try, best first.
     *
     * NPU is deliberately absent from the fallback chains: it needs a model
     * compiled for that specific NPU, so a silent fallback to CPU would hide the
     * fact that the NPU path is not actually working. Asking for NPU and getting
     * CPU is a much worse outcome than a clear failure.
     */
    private fun candidateOrder(requested: InferenceBackend): List<InferenceBackend> = when (requested) {
        InferenceBackend.CPU -> listOf(InferenceBackend.CPU)
        InferenceBackend.GPU -> listOf(InferenceBackend.GPU, InferenceBackend.CPU)
        InferenceBackend.NPU -> listOf(InferenceBackend.NPU)
        InferenceBackend.AUTO -> listOf(InferenceBackend.GPU, InferenceBackend.CPU)
    }.filterNot { it in failedBackends }

    /**
     * Backends this device has already refused.
     *
     * Kept for the life of the process, not of one load. A device without a
     * usable GPU delegate fails every time it is asked, and retrying it on each
     * model switch pays seconds of setup to reach the same failure. The cost of
     * remembering wrongly is that a backend becomes available again only after a
     * restart, which is a restart away.
     */
    private val failedBackends = java.util.Collections.synchronizedSet(mutableSetOf<InferenceBackend>())
}

private fun InferenceBackend.toLiteRtBackend(): Backend = when (this) {
    InferenceBackend.CPU -> Backend.CPU()
    InferenceBackend.GPU -> Backend.GPU()
    InferenceBackend.NPU -> Backend.NPU()
    // Unreachable: candidateOrder never yields AUTO. Kept total on purpose so
    // adding a backend is a compile error here rather than a runtime surprise.
    InferenceBackend.AUTO -> Backend.CPU()
}

/**
 * Marks an exception that came from the downstream collector rather than from
 * the runtime, so the generation's catch block does not blame the model for a
 * caller's bug.
 */
private class CollectorFailed(cause: Throwable) : RuntimeException(cause)

/** Concatenates the text parts of a message; non-text parts are ignored. */
private fun Message.textContent(): String =
    contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString(separator = "") { it.text }

/**
 * The runtime's own timing, in this package's terms.
 *
 * Seconds become milliseconds because that is the unit the rest of the app
 * formats in, and a value that changed unit on the way to the screen is how a
 * number ends up described in the wrong unit.
 *
 * Negative values are treated as absent. The runtime reports zero before a model
 * has produced anything, and "0.0 tok/s" is a claim about speed rather than a
 * statement that there is no measurement.
 */
/**
 * Reads the runtime's timing for the turn just finished.
 *
 * Behind one function because `getBenchmarkInfo` is marked experimental by the
 * runtime -- "may change or be removed without notice" -- and a single call site
 * is a single place to fix if it does. Wrapped rather than trusted: a runtime
 * that reports nothing, or throws, costs the user a line of detail and nothing
 * else.
 */
@OptIn(ExperimentalApi::class)
private fun readTiming(conversation: Conversation): GenerationTiming? =
    runCatching { conversation.getBenchmarkInfo().toTiming() }.getOrNull()

/**
 * The model's context window, read from the model file.
 *
 * Read once per load rather than per turn: it is a property of the file. Wrapped
 * because a model whose metadata this build cannot read still runs -- it just
 * cannot be measured, and a missing measurement must not stop a load.
 */
@OptIn(ExperimentalApi::class)
private fun readContextWindow(file: java.io.File): Int? =
    runCatching { ModelInfo.from(file.absolutePath).maxContextTokens().takeIf { it > 0 } }
        .getOrNull()

/**
 * How full the conversation is, in the runtime's own count.
 *
 * Null rather than zero when the runtime does not say: an empty conversation and
 * an unmeasurable one are different, and a meter reading zero for the second is a
 * meter that lies.
 */
@OptIn(ExperimentalApi::class)
private fun readContextTokens(conversation: Conversation): Int? =
    runCatching { conversation.getTokenCount().takeIf { it > 0 } }.getOrNull()

private fun BenchmarkInfo.toTiming(): GenerationTiming = GenerationTiming(
    loadMillis = initTimeInSecond.takeIf { it > 0 }?.let { (it * 1000).toLong() },
    timeToFirstTokenMillis =
        timeToFirstTokenInSecond.takeIf { it > 0 }?.let { (it * 1000).toLong() },
    prefillTokens = lastPrefillTokenCount.takeIf { it > 0 },
    decodeTokens = lastDecodeTokenCount.takeIf { it > 0 },
    prefillTokensPerSecond = lastPrefillTokensPerSecond.takeIf { it > 0 },
    decodeTokensPerSecond = lastDecodeTokensPerSecond.takeIf { it > 0 },
)
