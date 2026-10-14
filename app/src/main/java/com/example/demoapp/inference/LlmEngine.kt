package com.example.demoapp.inference

import com.example.demoapp.data.settings.AppTheme
import com.example.demoapp.model.ModelLanguage

import com.example.demoapp.ui.UiMessage
import com.example.demoapp.R

import androidx.annotation.StringRes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One earlier turn handed to the runtime when a conversation is opened.
 *
 * Deliberately not the app's own message type: this is the seam, and it holds no
 * types from the runtime either. An implementation maps it to whatever the
 * runtime wants.
 */
data class EngineTurn(
    val fromUser: Boolean,
    val text: String,
)

/**
 * Reads a backend name that was written to disk.
 *
 * Falls back to [InferenceBackend.AUTO] rather than throwing. The name comes from
 * a file the user cannot see and might have been written by an older build that
 * knew a backend this one does not; refusing to start over that would be a
 * self-inflicted failure.
 */
fun inferenceBackendNamed(name: String): InferenceBackend =
    InferenceBackend.entries.firstOrNull { it.name == name } ?: InferenceBackend.AUTO

/**
 * How a backend is named to the user, and what it means for them.
 *
 * Resource ids rather than text, so the words live with the other words. The
 * `when` is still exhaustive over the enum, so adding a backend is a compile
 * error here rather than a menu item that reads as blank.
 */
@StringRes
fun InferenceBackend.describe(): Int = when (this) {
    InferenceBackend.AUTO -> R.string.backend_auto
    InferenceBackend.CPU -> R.string.backend_cpu
    InferenceBackend.GPU -> R.string.backend_gpu
    InferenceBackend.NPU -> R.string.backend_npu
}

/** What choosing this backend does, in the user's terms rather than the API's. */
@StringRes
fun InferenceBackend.explain(): Int = when (this) {
    InferenceBackend.AUTO -> R.string.backend_auto_explain
    InferenceBackend.CPU -> R.string.backend_cpu_explain
    InferenceBackend.GPU -> R.string.backend_gpu_explain
    InferenceBackend.NPU -> R.string.backend_npu_explain
}

/** How a model's languages are named, for a tag on its row. */
@StringRes
fun ModelLanguage.labelRes(): Int = when (this) {
    ModelLanguage.CHINESE -> R.string.language_chinese
    ModelLanguage.ENGLISH -> R.string.language_english
    ModelLanguage.MULTILINGUAL -> R.string.language_multilingual
}

/** How an appearance choice is named. */
@StringRes
fun AppTheme.labelRes(): Int = when (this) {
    AppTheme.SYSTEM -> R.string.settings_theme_system
    AppTheme.LIGHT -> R.string.settings_theme_light
    AppTheme.DARK -> R.string.settings_theme_dark
}

/**
 * Which compute unit the model runs on.
 *
 * This enum belongs to the application, not to the inference library. The
 * library's own backend types are deliberately not exposed here: the whole
 * point of this seam is that the implementation behind it can be replaced.
 *
 * The medium-term plan is a pure-NPU path, which today would be a different
 * runtime entirely rather than a flag on this one. If the interface leaked
 * `Backend.NPU`, that replacement would ripple through the UI and data layers.
 */
enum class InferenceBackend {
    /** Let the implementation choose. */
    AUTO,

    /** Always available. Slowest, and the only option on an x86_64 emulator. */
    CPU,

    /** Usually the best trade-off on a modern phone. */
    GPU,

    /**
     * Vendor NPU. Requires a model compiled for that NPU and a device that has
     * one; the plain conversion of a model will not run here.
     */
    NPU,
}

/** Lifecycle of the loaded model. Drives what the UI is allowed to offer. */
sealed interface EngineState {
    data object Idle : EngineState

    data class Loading(val modelId: String) : EngineState

    data class Ready(
        val modelId: String,
        /** The backend actually in use, which may differ from what was asked for. */
        val backendInUse: InferenceBackend,
        /**
         * How many tokens the model can hold, when the runtime reports it.
         *
         * From the model file itself. The screen needs it to say how full the
         * conversation is, and without it a user has no way to tell a model that
         * forgot the beginning of a chat from one that never read it.
         */
        val contextWindow: Int? = null,
    ) : EngineState
}

/**
 * A load that failed for a reason the engine already knows how to say.
 *
 * The engine sees what no layer above it can: that every candidate backend was
 * rejected, and the runtime's own account of why. A device whose accelerator is
 * missing produces a message about OpenCL or a delegate -- text that is readable
 * to whoever wrote the library and to nobody else, and which the classifier above
 * would file under a general "the model would not load".
 *
 * So the sentence is built where the evidence is, carried up on the failure, and
 * used in preference to re-classifying from the raw text. It is an exception
 * rather than a return type because `load` answers with a `Result`, and the
 * alternative -- an `EngineState.Failed` that the state flow publishes -- was
 * written four times and read nowhere: two mechanisms for one message, and only
 * one of them reached the screen.
 */
class EngineLoadFailure(
    /** The sentence to show, already in the app's language. */
    val sentence: UiMessage,
    cause: Throwable?,
) : Exception(sentence.detail, cause)

/**
 * How a reply's time was spent.
 *
 * Two numbers rather than one, because they are two complaints. Reading the
 * question is the wait before anything appears, and it grows with the length of
 * the conversation -- so a chat that has become slow to start is one where the
 * transcript is getting long. Writing the answer is the speed the text arrives
 * at, and it barely changes.
 *
 * A single tokens-per-second figure hides which of the two the user is living
 * with, and the fixes are opposite: the first wants a shorter conversation or a
 * smaller model, the second wants a different model.
 */
data class GenerationTiming(
    /** Loading the model, when this reply had to load it. */
    val loadMillis: Long? = null,
    /** From sending the question to the first token appearing. */
    val timeToFirstTokenMillis: Long? = null,
    val prefillTokens: Int? = null,
    val decodeTokens: Int? = null,
    val prefillTokensPerSecond: Double? = null,
    val decodeTokensPerSecond: Double? = null,
)

/** One step of a generation. */
sealed interface GenerationEvent {
    /** A fragment of output. Arrives many times per reply. */
    data class Token(val text: String) : GenerationEvent

    /**
     * Terminal, on success. Carries the whole reply so the caller need not
     * re-assemble.
     */
    data class Done(
        val fullText: String,
        val elapsedMillis: Long,
        val tokenCount: Int,
        /**
         * The model's reasoning, when it produced any and the runtime did not
         * separate it itself.
         *
         * Carried to the caller rather than discarded here. The engine is a seam,
         * not a policy: whether a trace is worth showing is a decision for the
         * screen, which is the only place that knows what the user asked.
         */
        val reasoning: String? = null,
        /** How the time was spent, when the runtime reports it. */
        val timing: GenerationTiming? = null,
        /**
         * Tokens the conversation occupies after this turn.
         *
         * Read from the runtime rather than estimated from the text: an estimate
         * is off by the tokeniser, and a meter that is wrong is worse than
         * none because it is believed.
         */
        val contextTokens: Int? = null,
    ) : GenerationEvent

    /** Terminal, on failure. */
    data class Failed(val error: Throwable) : GenerationEvent
}

/**
 * Sampling parameters.
 *
 * ## Where these numbers come from
 *
 * The comment here used to say "deliberately conservative" and left it at that.
 * Two of them were not conservative; they were the runtime's own defaults, and
 * nothing in this project had ever argued for them. The runtime's defaults are
 * chosen for a general audience, and this app's catalogue is small models on
 * phones, so the publisher's guidance for the models actually offered beats it.
 *
 * `topK = 20` rather than 40. Qwen publishes a recommended set for
 * Qwen3.5-0.8B — a model in this catalogue — as `top_k=20`, and `top_k` is a hard
 * cut: at 40 the sampler never considers fewer than forty candidates however
 * confident the model is. The recommended `presence_penalty` is 2.0, "to reduce
 * endless repetitions", and that control is not on the path
 * `ConversationConfig` exposes — so for a small model, and for a model whose own
 * card warns it is prone to looping, keeping the candidate set tight is the part
 * of the advice that is reachable. Exactly one of the two values is changed; a
 * tighter `topP` would compound the effect rather than correct it.
 *
 * `topP = 0.95` is inside the range the same card recommends (0.95–1.00).
 *
 * `temperature` is only the default the settings screen opens on; the user's
 * choice replaces it at load.
 *
 * What this is not: a claim to have tuned anything. It is one value brought in
 * line with the publisher's own recommendation for a model this app ships, with
 * the reasoning written down so the next session does not have to guess where 40
 * came from. Nothing here has been measured on a device, because there is no
 * device — see the note in `docs/ROADMAP.md` about real-device verification.
 */
data class GenerationConfig(
    val maxTokens: Int = 512,
    val temperature: Double = 0.8,
    val topK: Int = 20,
    val topP: Double = 0.95,
    /** Fixing the seed makes a reply reproducible, which is what a test needs. */
    val seed: Int = 0,
)

/**
 * A named choice about how the model picks its words.
 *
 * ## Why presets rather than the four numbers
 *
 * Exposing `topK`, `topP`, `temperature` and `seed` as separate controls was
 * rejected on this project's own reasoning: a small model plus a wall of knobs is
 * a user making it worse. That rejection was about the *controls*, and it was
 * taken — wrongly — to mean the values underneath needed no attention at all.
 * They did: `topK` was the runtime's default, argued for nowhere.
 *
 * A profile is the middle: the numbers stay a deliberate set rather than four
 * dials, and a user still gets to say "this model rambles" and do something about
 * it. The names describe what a person wants, not what a sampler does.
 *
 * ## What each one is for
 *
 * - [PRECISE] is for a model that is producing text nobody asked for — repeats,
 *   invented continuations, drifting off the question. It narrows the candidate
 *   set hard and drops the temperature, which is the combination that makes the
 *   most probable continuation win.
 * - [BALANCED] is the app's own default, and the values are the publisher's
 *   recommendation for the smallest models in this catalogue. Not a compromise
 *   between the other two: the starting point.
 * - [VARIED] is for creative writing. It widens the cut and raises the
 *   temperature, and it is the one that can make a small model incoherent — which
 *   is why it is the last option rather than the first.
 *
 * These are not tuned by measurement. Nothing in this project has been run on a
 * device, so the honest claim is that each is a defensible point in the space the
 * sampling literature describes, not that it was found to be best.
 */
enum class SamplingProfile {
    PRECISE,
    BALANCED,
    VARIED,
    ;

    /**
     * This profile applied to [base].
     *
     * Built from the app's default rather than stating every value again, so a
     * change to [GenerationConfig]'s defaults reaches all three. Only what a
     * profile is actually about is overridden.
     */
    fun appliedTo(base: GenerationConfig = GenerationConfig()): GenerationConfig = when (this) {
        // Narrower than the default, and colder. `BALANCED`'s topK is already the
        // publisher's figure for the smallest models here; this is what to reach
        // for when that is still too loose.
        PRECISE -> base.copy(topK = 10, topP = 0.80, temperature = 0.3)
        BALANCED -> base
        VARIED -> base.copy(topK = 50, topP = 0.98, temperature = 1.1)
    }
}

/** How a sampling choice is named on the card that offers it. */
@StringRes
fun SamplingProfile.labelRes(): Int = when (this) {
    SamplingProfile.PRECISE -> R.string.sampling_precise
    SamplingProfile.BALANCED -> R.string.sampling_balanced
    SamplingProfile.VARIED -> R.string.sampling_varied
}

/** What that choice is for, in the app's own words. */
@StringRes
fun SamplingProfile.hintRes(): Int = when (this) {
    SamplingProfile.PRECISE -> R.string.sampling_precise_hint
    SamplingProfile.BALANCED -> R.string.sampling_balanced_hint
    SamplingProfile.VARIED -> R.string.sampling_varied_hint
}

/** A model file already present on the device. */
data class LocalModel(
    val id: String,
    val displayName: String,
    val file: java.io.File,
)

/**
 * The seam between the application and whatever runs the model.
 *
 * Two implementations are anticipated: the LiteRT-LM one that exists today, and
 * a Qualcomm-NPU one later. Nothing outside this package may reference a library
 * type.
 *
 * The sentence that used to follow -- "so that swapping implementations is a change
 * to one file" -- was wrong twice over and is worth recording rather than deleting.
 * It was never true *inside* the package either: `LocalTools.kt` lives here and
 * imports `Tool` and `ToolSet` from the runtime, because "how does the runtime
 * discover a tool" has no runtime-neutral answer and cannot be routed through this
 * interface. So the count is two files plus a factory, and the goal is the smaller
 * one -- that nothing *outside* `inference/` has to change, which is a claim the
 * code does honour.
 */
interface LlmEngine {

    /** Current lifecycle state. Collect to drive the UI. */
    val state: StateFlow<EngineState>

    /**
     * Loads a model, replacing any model already loaded.
     *
     * Loading a second model while holding the first is the reliable way to
     * exhaust memory and get killed by the system, so this unloads first.
     *
     * @return the backend actually used, which may differ from [requested] when
     *   the implementation falls back (for example GPU requested on a device
     *   with no usable GPU delegate).
     */
    suspend fun load(
        model: LocalModel,
        requested: InferenceBackend = InferenceBackend.AUTO,
        /**
         * What the model is told it is, for the whole of this load.
         *
         * The runtime takes it when a conversation is created, and every
         * conversation this load makes is created with it, so changing it means
         * reloading. Blank means the implementation's own default.
         */
        systemPrompt: String = "",
        /**
         * How the model chooses its words, for the whole of this load.
         *
         * The whole configuration rather than just the temperature, because the
         * runtime takes all of it together when a conversation is created: there is
         * no per-call temperature and no per-call `topK`. Changing any of it means
         * loading again, which is what the settings screen says.
         *
         * Passed in rather than read from a constant inside the implementation. It
         * used to be a constant there, and the effect was that a caller had no way
         * to ask for anything else -- so "per-model sampling" was not something the
         * app had merely left unbuilt, it was something the seam made impossible.
         *
         * [GenerationConfig.maxTokens] is ignored here: reply length is a property
         * of a request, not of a load.
         */
        sampling: GenerationConfig = GenerationConfig(),
    ): Result<InferenceBackend>

    /** Releases the model and its native resources. Safe to call when idle. */
    suspend fun unload()

    /**
     * Discards the runtime's accumulated conversation state, keeping the model
     * loaded, and optionally seeds it with [history].
     *
     * The runtime carries one conversation per loaded model, and that object is
     * what holds the model's memory of what has been said. An application with
     * more than one conversation therefore has to reset it when the user
     * switches: without this, starting a new chat leaves the model still
     * answering from the previous one, and the transcript on screen stops
     * describing what the model was actually told.
     *
     * [history] is the other half of the same problem. Switching to a
     * conversation the model has never seen -- after a restart, or simply
     * opening an older chat -- leaves it with no memory of a transcript the
     * screen is showing. Seeding hands it what it missed, so the reply matches
     * the conversation in front of the user instead of a blank slate.
     *
     * @param history earlier turns, oldest first. Empty starts a fresh one.
     */
    suspend fun resetConversation(history: List<EngineTurn> = emptyList())

    /**
     * Generates a reply, streaming fragments as they are produced.
     *
     * The flow is cold: collection starts the generation and cancellation stops
     * it. It never throws — failures arrive as [GenerationEvent.Failed] so a
     * caller cannot accidentally leave the UI spinning.
     */
    fun generate(prompt: String, config: GenerationConfig = GenerationConfig()): Flow<GenerationEvent>
}
