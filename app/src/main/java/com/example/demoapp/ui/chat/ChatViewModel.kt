package com.example.demoapp.ui.chat

import com.example.demoapp.R
import com.example.demoapp.ui.UiMessage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.demoapp.data.chat.ChatMessage
import com.example.demoapp.data.chat.withKeeping
import com.example.demoapp.data.chat.withVersion
import com.example.demoapp.data.chat.ChatStore
import com.example.demoapp.data.chat.Conversation
import com.example.demoapp.data.chat.MessageRole
import com.example.demoapp.inference.EngineLoadFailure
import com.example.demoapp.inference.EngineState
import com.example.demoapp.inference.GenerationTiming
import com.example.demoapp.inference.EngineTurn
import com.example.demoapp.inference.GenerationConfig
import com.example.demoapp.inference.GenerationEvent
import com.example.demoapp.inference.InferenceBackend
import com.example.demoapp.inference.ContextBudget
import com.example.demoapp.inference.LoadFailure
import com.example.demoapp.inference.LlmEngine
import com.example.demoapp.inference.messageRes
import com.example.demoapp.inference.LocalModel
import com.example.demoapp.inference.inferenceBackendNamed
import com.example.demoapp.data.settings.SettingsStore
import com.example.demoapp.data.settings.Settings
import com.example.demoapp.data.settings.InMemorySettingsStore
import com.example.demoapp.model.DeviceMemory
import com.example.demoapp.model.ModelDescriptor
import com.example.demoapp.model.ModelMemory
import com.example.demoapp.model.ModelStore
import com.example.demoapp.model.InMemoryModelRegistry
import com.example.demoapp.model.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Drives the chat screen.
 *
 * Owns three things: which conversation is open, which model is loaded, and the
 * generation currently running. Everything else is derived.
 *
 * The behaviour lives in the files beside this one, as extensions on this class:
 * ChatConversation.kt (the open conversation and its message list),
 * ChatGeneration.kt (asking, streaming, stopping, asking again), ChatSaving.kt
 * (what reaches disk and which title wins), ChatModels.kt (the catalogue and
 * loading a model), ChatSpeech.kt (reading a reply aloud). They are extensions
 * rather than members so that each file can be read on its own; what they share
 * is the state declared here, which is why these fields are `internal` rather
 * than private.
 */
class ChatViewModel(
    internal val chatStore: ChatStore,
    internal val engine: LlmEngine,
    internal val modelStore: ModelStore,
    /** The user's choices; the backend is read from here when a model is loaded. */
    internal val settingsStore: SettingsStore = InMemorySettingsStore(),
    /** Which models exist, catalogue plus whatever the user added. */
    internal val registry: ModelRegistry = InMemoryModelRegistry(),
    /**
     * How much memory the device has, for asking before a load that may not fit.
     *
     * Defaults to zero, which means "not known" and asks nothing. A caller with no
     * device -- a test -- gets that rather than a dialog on every load.
     */
    internal val deviceMemory: DeviceMemory = DeviceMemory(0L),
    /**
     * Where the current time comes from.
     *
     * A parameter because [Conversation.stamp] exists to survive a device clock
     * that moves backwards, and a test cannot move the system clock. Without this
     * the property is unfalsifiable: a version that used the plain clock would
     * pass every test while losing replies on a device whose time jumped.
     */
    internal val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    internal val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    internal var conversation: Conversation? = null
    internal var generationJob: Job? = null

    /**
     * The reply the running generation is replacing, carried so the replacement
     * can offer it as an earlier version.
     *
     * Held here rather than in the conversation because it is not part of the
     * transcript while the new answer is being written: the screen shows a
     * generating reply where the old one was, and putting both on screen at once
     * would be showing an answer to a question the model is still working on.
     */
    internal var pendingPreviousReply: ChatMessage? = null

    /** Serialises model loading; see [loadModel]. */
    internal val loadLock = Mutex()

    /**
     * Serialises opening a conversation; see [openConversation].
     */
    internal val openLock = Mutex()

    /** The conversation the user last asked for, so a slow load cannot win a race. */
    @Volatile
    internal var wantedConversationId: String? = null
    internal var inFlightLoad: String? = null

    /**
     * Serialises writes to conversation files.
     *
     * Each write carries the conversation it belongs to; the lock only orders
     * them. An earlier version read the open conversation inside the lock, which
     * meant a queued write could be redirected to whatever conversation the user
     * switched to in the meantime -- saving an empty new chat and losing the
     * message that scheduled the write.
     */
    internal val saveLock = Mutex()

    /**
     * How far a write got, for reading when one does not arrive.
     *
     * `internal` rather than on the class's public surface: this is a diagnosis, not
     * state the UI decides anything with. Both counters only ever increase, so they
     * are safe to read from a test thread without a lock -- see [saveNow] for what
     * the pair distinguishes.
     */
    internal var saveNowCalls: Int = 0

    internal var saveNowThroughLock: Int = 0

    init {
        viewModelScope.launch {
            openMostRecentOrStartNew()
            refreshModels()
        }
    }

    // ------------------------------------ what the other files in this package use ---

    override fun onCleared() {
        super.onCleared()
        onScreenClosed()
    }

    /**
     * The runtime's view of a transcript.
     *
     * On the message list rather than the conversation, because the one caller
     * that needs the distinction has already removed a question from the
     * transcript and has to seed everything except it.
     */
    /**
     * The turns to hand the runtime, cut to the model's window.
     *
     * The reserve is the system prompt and the question about to be asked, neither of
     * which is a message yet. Half the window is a blunt figure, and a deliberate one:
     * the estimate this uses is within a factor of two, and a reserve that varied with
     * the prompt's length would make the trim point move for reasons the user cannot
     * see.
     */
    internal fun List<ChatMessage>.historyForRuntime(): List<EngineTurn> {
        val window = _state.value.contextWindow
        val budget = if (window != null && window > 0) window / 2 else 0
        val result = ContextBudget.window(
            messages = map { EngineTurn(fromUser = it.role == MessageRole.USER, text = it.text) },
            budgetTokens = budget,
        )
        droppedTurns = result.dropped
        return result.turns
    }

    /**
     * How many turns the last assembly left out.
     *
     * Held rather than put in the state directly, because this runs inside a
     * generation's setup and the state is written as a whole by the caller.
     */
    internal var droppedTurns: Int = 0

    init {
        // Follows the setting rather than reading it once. Turning the timestamps
        // on and seeing nothing change until the app is reopened reads as the
        // switch being broken.
        viewModelScope.launch {
            settingsStore.settings.collect { applyDisplaySettings(it) }
        }
    }

    /**
     * The timestamp to stamp a change to this conversation with.
     *
     * Never earlier than the timestamp it replaces. The store keeps the newer of
     * two versions and compares `updatedAt`, so with a plain clock reading a
     * device whose time moves backwards -- an automatic sync after drift, or the
     * user changing the date -- makes a later change look older than the state it
     * replaces, and the store silently keeps the older one. The reply is on
     * screen and not on disk, and reopening the chat shows it gone.
     *
     * `updatedAt` is therefore a per-conversation sequence, not the wall clock.
     * It still reads as a time when the clock behaves, which is what the history
     * screen wants to display.
     */
    internal fun Conversation.stamp(now: Long = clock()): Long =
        maxOf(now, updatedAt + 1)

    /**
     * [conversation] with whatever title is on disk, where that is the newer one.
     *
     * This screen holds the copy it read when it opened the conversation, and a
     * save carries the whole thing -- so writing that copy back silently undid a
     * rename made on the history screen while this one was still open. The user
     * made that change and watched it happen; losing it to a background save is
     * the kind of thing that reads as the app forgetting.
     *
     * The screen decides a title exactly once, when the first message names a
     * conversation that has none. A conversation on disk with no messages is one
     * whose name has not been decided yet, so that save keeps its own title.
     * Afterwards the stored title is the newer statement about it.
     *
     * The deferral lives here rather than in [com.example.demoapp.data.chat.ChatStore]
     * because only the caller knows it is holding a copy that may have gone stale.
     * The store cannot tell a rename from a chat save that happens to carry an old
     * title, and a rule it applied to both would refuse the rename itself.
     */
    /**
     * The title this screen is working from, for telling its own edits apart from
     * everyone else's.
     *
     * A save writes the whole conversation, so writing back a copy read earlier
     * undid a rename made on the history screen while this one was open. Deferring
     * to the disk unconditionally fixes that and breaks the other direction: an
     * edit to the first question changes the title, and the disk's older one was
     * written back over it.
     *
     * The two are told apart by asking whether this screen's title has moved away
     * from the one it was working from. If it has, this screen changed it and its
     * version is the newer. If it has not, the change came from elsewhere and the
     * disk's version wins.
     */
    internal var baselineTitle: String? = null
}
