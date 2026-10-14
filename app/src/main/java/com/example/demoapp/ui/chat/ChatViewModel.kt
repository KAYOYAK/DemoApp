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
 */
class ChatViewModel(
    private val chatStore: ChatStore,
    private val engine: LlmEngine,
    private val modelStore: ModelStore,
    /** The user's choices; the backend is read from here when a model is loaded. */
    private val settingsStore: SettingsStore = InMemorySettingsStore(),
    /** Which models exist, catalogue plus whatever the user added. */
    private val registry: ModelRegistry = InMemoryModelRegistry(),
    /**
     * How much memory the device has, for asking before a load that may not fit.
     *
     * Defaults to zero, which means "not known" and asks nothing. A caller with no
     * device -- a test -- gets that rather than a dialog on every load.
     */
    private val deviceMemory: DeviceMemory = DeviceMemory(0L),
    /**
     * Where the current time comes from.
     *
     * A parameter because [Conversation.stamp] exists to survive a device clock
     * that moves backwards, and a test cannot move the system clock. Without this
     * the property is unfalsifiable: a version that used the plain clock would
     * pass every test while losing replies on a device whose time jumped.
     */
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var conversation: Conversation? = null
    private var generationJob: Job? = null

    /**
     * The reply the running generation is replacing, carried so the replacement
     * can offer it as an earlier version.
     *
     * Held here rather than in the conversation because it is not part of the
     * transcript while the new answer is being written: the screen shows a
     * generating reply where the old one was, and putting both on screen at once
     * would be showing an answer to a question the model is still working on.
     */
    private var pendingPreviousReply: ChatMessage? = null

    /** Serialises model loading; see [loadModel]. */
    private val loadLock = Mutex()

    /**
     * Serialises opening a conversation; see [openConversation].
     */
    private val openLock = Mutex()

    /** The conversation the user last asked for, so a slow load cannot win a race. */
    @Volatile
    private var wantedConversationId: String? = null
    private var inFlightLoad: String? = null

    /**
     * Serialises writes to conversation files.
     *
     * Each write carries the conversation it belongs to; the lock only orders
     * them. An earlier version read the open conversation inside the lock, which
     * meant a queued write could be redirected to whatever conversation the user
     * switched to in the meantime -- saving an empty new chat and losing the
     * message that scheduled the write.
     */
    private val saveLock = Mutex()

    /**
     * How far a write got, for reading when one does not arrive.
     *
     * `internal` rather than on the class's public surface: this is a diagnosis, not
     * state the UI decides anything with. Both counters only ever increase, so they
     * are safe to read from a test thread without a lock -- see [saveNow] for what
     * the pair distinguishes.
     */
    internal var saveNowCalls: Int = 0
        private set

    internal var saveNowThroughLock: Int = 0
        private set

    init {
        viewModelScope.launch {
            openMostRecentOrStartNew()
            refreshModels()
        }
    }

    // ------------------------------------------------------------ messages ---

    /**
     * Sends [text] and streams the reply.
     *
     * The user's message is committed to disk before the model is asked, and
     * that is awaited rather than fired off. If the process dies while the model
     * is thinking, the question is still there when the app is reopened; losing
     * the answer is recoverable, losing what the user typed is not.
     */
    fun send(text: String) {
        val current = conversation ?: return
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.generating || !canAskTheModel()) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            text = prompt,
            createdAt = clock(),
        )

        // The first thing the user says names the conversation, so a later
        // history screen is readable without opening anything.
        val updated = current.copy(
            title = if (current.messages.isEmpty()) Conversation.titleFrom(prompt) else current.title,
            messages = current.messages + userMessage,
            updatedAt = current.stamp(userMessage.createdAt),
        )
        conversation = updated
        publish(updated)

        _state.value = _state.value.copy(generating = true, streaming = "", error = null)

        // The question's write lives in its own coroutine, not inside the
        // generation job. stop() and newChat() cancel that job, and a write
        // queued inside it would be cancelled with it -- losing what the user
        // typed, which is the one thing this must not do.
        val saveJob = viewModelScope.launch { saveNow(updated) }

        generationJob = viewModelScope.launch {
            // Awaited, so the promise above is true by the time the model is
            // asked anything.
            saveJob.join()
            runGeneration(updated, prompt)
        }
    }

    /**
     * Streams a reply into [into] and commits it.
     *
     * Shared by asking and asking again: the two differ in what precedes the
     * request, not in what happens once it is made.
     */
    private suspend fun runGeneration(into: Conversation, prompt: String) {
        // Re-cut the history if this conversation has outgrown the window since it was
        // seeded.
        //
        // The runtime holds the conversation natively and each turn continues it, so
        // the trim normally happens once. A chat that grows past the window while it is
        // open would otherwise keep growing: the meter would climb past its own
        // maximum and the model would start losing the earliest turns by itself, with
        // nothing on screen saying so.
        //
        // Re-seeding is the expensive path -- it discards the KV cache and the next
        // reply is slower -- and it is taken only when the alternative is a conversation
        // the runtime cannot hold.
        val window = _state.value.contextWindow
        if (window != null && window > 0) {
            val estimated = into.messages.sumOf { ContextBudget.estimateTokens(it.text) }
            if (estimated > window / 2) {
                reseedRuntimeWith(into)
            }
        }
        val assembler = StringBuilder()
        var settled = false

        try {
            engine.generate(prompt, generationConfig()).collect { event ->
                when (event) {
                    is GenerationEvent.Token -> {
                        assembler.append(event.text)
                        _state.value = _state.value.copy(streaming = assembler.toString())
                    }

                    is GenerationEvent.Done -> {
                        settled = true
                        commitReply(
                            into, event.fullText, event.elapsedMillis,
                            tokens = event.tokenCount, reasoning = event.reasoning,
                            timing = event.timing,
                            contextTokens = event.contextTokens,
                            interrupted = false,
                        )
                    }

                    is GenerationEvent.Failed -> {
                        settled = true
                        // Anything already streamed is kept. The user watched it
                        // arrive; discarding it loses work that cannot be
                        // recovered without asking again, which is the same
                        // reason stop() keeps it.
                        commitReply(
                            into,
                            assembler.toString(),
                            elapsedMillis = null,
                            tokens = null,
                            reasoning = null,
                            timing = null,
                            contextTokens = null,
                            interrupted = true,
                        )
                        _state.value = _state.value.copy(
                            generating = false,
                            streaming = "",
                            error = UiMessage(R.string.err_generation_failed)
                    .withDetail(event.error.message ?: event.error::class.simpleName),
                        )
                    }
                }
            }
        } catch (t: CancellationException) {
            // Left to propagate. What was produced is committed by stop(), or by
            // onScreenClosed() when the screen is being torn down without one;
            // cancelling here would lose it either way.
            throw t
        } finally {
            if (!settled) _state.value = _state.value.copy(generating = false, streaming = "")
        }
    }

    /**
     * Stops the reply in progress.
     *
     * What was produced so far is kept and marked as interrupted. Discarding it
     * would lose work the user watched happen and cannot get back without asking
     * again.
     */
    fun stop() {
        val partial = _state.value.streaming
        val target = conversation
        generationJob?.cancel()
        generationJob = null

        if (partial.isNotBlank() && target != null) {
            commitReply(target, partial, elapsedMillis = null, tokens = null, reasoning = null, timing = null, contextTokens = null, interrupted = true)
            // The runtime was cancelled mid-turn and what it now holds is its own
            // business -- it may have the partial assistant turn, or not, or be in
            // a state neither the app nor its API describes. The transcript has
            // just gained a message the runtime was never told about, so the two
            // are rebuilt into agreement rather than left to diverge.
            viewModelScope.launch { reseedRuntimeWith(conversation) }
        } else {
            _state.value = _state.value.copy(generating = false, streaming = "")
        }
    }

    /** Clears the error banner. */
    // -------------------------------------------------------------- searching ---

    /**
     * Narrows the transcript to what matches.
     *
     * Deliberately not a change to the conversation: nothing is written, nothing is
     * sent to the model, and clearing the field puts the whole transcript back. A
     * search that touched the runtime would make finding a word in a conversation
     * cost the same as asking a question, which is not what the user asked for.
     */
    fun setSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Whether the model can be asked anything right now.
     *
     * Two questions, and both are needed. The state says which model this screen
     * loaded; the engine says whether one is still loaded. They can disagree --
     * erasing everything from the About screen unloads the engine directly, and a
     * load that failed leaves this screen believing in a model it does not have.
     * Checking only the state let "ask again" delete the answer it was meant to
     * replace and then fail to produce another, which is the worst possible
     * outcome of a guard that is supposed to prevent damage.
     */
    private fun canAskTheModel(): Boolean =
        _state.value.modelId != null && engine.state.value is EngineState.Ready

    /**
     * Asks the model again for the last reply.
     *
     * The reply being replaced is removed from the app's transcript AND from the
     * runtime's own conversation. Removing it from only one of the two was the
     * first version of this, and it did nothing useful: the runtime accumulates
     * every turn it is given, so the model would have answered with its own
     * previous answer in front of it -- reproducing a reply rather than replacing
     * one.
     */
    fun regenerate() {
        val current = conversation ?: return
        if (_state.value.generating) return
        // The same guard send() has. Without it, asking again while no model is
        // loaded -- which is a state the user can be in, because a failed load
        // leaves the transcript on screen with an error banner -- trimmed the
        // reply out of the transcript and off disk, then failed to produce a
        // replacement. The question was left unanswered and the old answer was
        // gone, with nothing to undo it.
        if (!canAskTheModel()) return

        val lastQuestion = current.messages.lastOrNull { it.role == MessageRole.USER } ?: return
        // The reply about to be replaced, kept so the replacement can offer it as an
        // earlier version. Removed from the transcript and from the runtime -- the
        // model must not answer with its own previous answer in front of it -- but
        // not from the conversation's history.
        val replaced = current.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        // Everything up to and including the question being asked again.
        val upto = current.messages.takeWhile { it.id != lastQuestion.id } + lastQuestion
        val trimmed = current.copy(messages = upto, updatedAt = current.stamp())

        conversation = trimmed
        pendingPreviousReply = replaced
        publish(trimmed)
        persist(trimmed)

        _state.value = _state.value.copy(generating = true, streaming = "", error = null)

        generationJob = viewModelScope.launch {
            // Seeded with everything BEFORE the question, because the question is
            // about to be sent as the prompt. Seeding with it included put it in
            // the runtime twice -- resetConversation places it in the history and
            // generate() sends it again -- so every "ask again" answered from a
            // context holding the same question twice, which is not a context the
            // app shows, stores, or can produce any other way.
            reseedRuntimeWith(trimmed, messages = upto.dropLast(1))
            runGeneration(trimmed, lastQuestion.text)
        }
    }

    /**
     * Shows an earlier version of a reply.
     *
     * The runtime is rebuilt from what is now on screen. Without that the model
     * keeps answering from whichever version it was last told about, and the user
     * reads one answer while the model remembers another -- which shows up as the
     * next reply contradicting the one above it, with nothing on screen explaining
     * why.
     *
     * Nothing is generated here. Looking at an earlier answer is not asking a
     * question, and a version switch that cost a turn would make browsing them
     * expensive enough not to bother.
     */
    /**
     * Marks an answer as worth keeping, or stops.
     *
     * Written straight through rather than held in the view model and saved later.
     * A bookmark is a small, deliberate act with a visible result, and one that
     * disappeared on a restart would be worse than not having the feature.
     */
    /**
     * The screen has started or stopped reading [messageId].
     *
     * Told rather than driven: the speech engine needs a Context and a service
     * connection, and this class has neither. What it owns is the one piece of state
     * the screen cannot hold on its own -- which reply is talking -- because that has
     * to survive a recomposition and a scroll.
     */
    /** No Chinese voice is installed, so reading aloud cannot work here. */
    fun reportSpeechUnavailable() {
        _state.value = _state.value.copy(error = UiMessage(R.string.chat_speak_unavailable))
    }

    fun setSpeaking(messageId: String?) {
        if (_state.value.speakingId == messageId) return
        _state.value = _state.value.copy(speakingId = messageId)
    }

    fun toggleKeeping(messageId: String) {
        val current = conversation ?: return
        val target = current.messages.firstOrNull { it.id == messageId } ?: return
        val updated = current.copy(
            messages = current.messages.map {
                if (it.id == messageId) it.withKeeping(it.keptAt == null, clock()) else it
            },
            updatedAt = current.stamp(),
        )
        conversation = updated
        publish(updated)
        persist(updated)
    }

    fun selectVersion(messageId: String, index: Int) {
        val current = conversation ?: return
        // Refused mid-reply for the same reason a save from here is: the generation
        // is reading the runtime this would rebuild.
        if (_state.value.generating) return

        val target = current.messages.firstOrNull { it.id == messageId } ?: return
        val switched = target.withVersion(index)
        if (switched === target) return

        val updated = current.copy(
            messages = current.messages.map { if (it.id == messageId) switched else it },
            updatedAt = current.stamp(),
        )
        conversation = updated
        publish(updated)
        persist(updated)
        viewModelScope.launch { reseedRuntimeWith(updated) }
    }

    /**
     * Replaces a question and asks it again.
     *
     * Only the conversation's last question can be edited, and that is a
     * deliberate limit rather than an unfinished feature. Changing an earlier
     * question invalidates every answer after it -- the model was told something
     * the user has since decided was wrong -- and discarding those replies is a
     * bigger thing to do silently than this menu item can honestly offer. The
     * screen offers the action only for the last question.
     */
    fun editAndResend(messageId: String, newText: String) {
        val current = conversation ?: return
        if (_state.value.generating) return
        if (!canAskTheModel()) return

        val prompt = newText.trim()
        if (prompt.isEmpty()) return

        val index = current.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        if (current.messages[index].role != MessageRole.USER) return
        if (index != current.messages.indexOfLast { it.role == MessageRole.USER }) return

        // Everything before the question, then the edited one, then the question
        // is sent. The same shape as asking again, with a different question.
        val before = current.messages.take(index)
        val kept = current.messages[index].copy(text = prompt)
        val trimmed = current.copy(
            messages = before + kept,
            // The title comes from the first question, so an edit to that one has
            // to follow -- otherwise the history list keeps naming a conversation
            // after something the user changed their mind about.
            title = if (index == 0) Conversation.titleFrom(prompt) else current.title,
            updatedAt = current.stamp(),
        )

        conversation = trimmed
        // Deliberately not carrying the replaced reply here, unlike regenerate().
        // The question itself changed, so the old answer answers a different
        // question -- offering it as a version of this one would put two unrelated
        // answers side by side and call them alternatives.
        publish(trimmed)
        persist(trimmed)

        _state.value = _state.value.copy(generating = true, streaming = "", error = null)

        generationJob = viewModelScope.launch {
            // Seeded with everything before the question, which is about to be
            // sent as the prompt. Seeding with it included would put it in the
            // runtime twice.
            reseedRuntimeWith(trimmed, messages = before)
            runGeneration(trimmed, prompt)
        }
    }

    /**
     * Removes one message.
     *
     * A reply whose question is gone is a reply to nothing, so removing a question
     * removes the answer that followed it. Removing an answer leaves the question,
     * which is the state asking again starts from.
     *
     * The runtime's conversation is rebuilt from what is left, for the same reason
     * asking again rebuilds it: a message the user deleted must not still be part
     * of what the model has been told, or it will refer to something that is no
     * longer on screen.
     */
    fun deleteMessage(messageId: String) {
        val current = conversation ?: return
        if (_state.value.generating) return

        val index = current.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return

        val doomed = if (current.messages[index].role == MessageRole.USER) {
            // The question and the answer that belongs to it.
            setOf(current.messages[index].id) +
                current.messages.drop(index + 1)
                    .takeWhile { it.role != MessageRole.USER }
                    .map { it.id }
        } else {
            setOf(current.messages[index].id)
        }

        val remaining = current.messages.filterNot { it.id in doomed }
        val updated = current.copy(messages = remaining, updatedAt = current.stamp())

        conversation = updated
        publish(updated)
        persist(updated)

        // The runtime keeps every turn it was given, so what was deleted has to
        // be taken away from it too. Otherwise the model answers later questions
        // using something that is no longer on screen, and the user sees it quote
        // a message they removed.
        viewModelScope.launch { reseedRuntimeWith(updated) }
    }

    /**
     * Rebuilds the runtime's conversation from [conversation].
     *
     * The runtime's memory is its own object and accumulates whatever it is sent;
     * it does not read the app's transcript. Anything that changes the transcript
     * underneath it -- loading a model, asking again, deleting a message, stopping
     * mid-reply, opening a different conversation -- has to say so, or the model
     * keeps answering a conversation the user can no longer see.
     *
     * [messages] defaults to the conversation's own, and is overridden by the one
     * caller that has already removed a question from the transcript and is about
     * to send that question as the prompt.
     *
     * A null [conversation] seeds nothing, which is the correct state after a
     * model load with no conversation open.
     *
     * A failure is reported rather than thrown: the transcript on screen is still
     * correct, and the person reading it can decide whether to carry on.
     */
    private suspend fun reseedRuntimeWith(
        conversation: Conversation?,
        messages: List<ChatMessage> = conversation?.messages ?: emptyList(),
    ) {
        val windowed = messages.historyForRuntime()
        _state.value = _state.value.copy(droppedTurns = droppedTurns)
        runCatching { engine.resetConversation(windowed) }
            .onFailure {
                _state.value = _state.value.copy(
                    error = UiMessage(R.string.err_model_mismatch)
                        .withDetail(it.message ?: it::class.simpleName),
                )
            }
    }

    /**
     * Keeps a half-finished reply when the screen goes away.
     *
     * Leaving mid-reply used to discard it: the scope is cancelled, the stream
     * stops, and the text the user watched arrive was never written. `stop()`
     * already treats that text as worth keeping, and pressing Back instead of
     * Stop is not a statement that it is not.
     *
     * The framework closes a ViewModel's closeables -- where `viewModelScope`
     * lives -- before calling `onCleared()`, so this cannot use that scope and
     * makes its own. The partial is read here rather than in the write, because
     * the state is gone by the time the write runs.
     */
    fun onScreenClosed() {
        val target = conversation
        val partial = _state.value.streaming
        if (target == null || partial.isBlank() || !_state.value.generating) return

        val interrupted = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.ASSISTANT,
            text = partial,
            createdAt = clock(),
            modelId = _state.value.modelId,
            elapsedMillis = null,
            interrupted = true,
        )
        val updated = target.copy(
            messages = target.messages + interrupted,
            updatedAt = target.stamp(interrupted.createdAt),
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            // A failure here has nobody left to tell; refusing to write is the
            // only other option and loses the reply.
            runCatching { chatStore.save(updated) }
        }.invokeOnCompletion { scope.cancel() }
    }

    override fun onCleared() {
        super.onCleared()
        onScreenClosed()
    }

    // -------------------------------------------------------- conversations ---

    /**
     * Starts an empty conversation. The previous one stays on disk.
     *
     * The runtime's own conversation is reset as well. Keeping it would leave the
     * model still answering from the previous chat while the screen shows an
     * empty one.
     */
    fun newChat() {
        stop()
        viewModelScope.launch {
            runCatching { engine.resetConversation() }
                .onFailure {
                _state.value = _state.value.copy(
                    error = UiMessage(R.string.err_new_chat).withDetail(it.message),
                )
            }
        }

        val now = clock()
        val fresh = Conversation(
            id = UUID.randomUUID().toString(),
            title = Conversation.UNNAMED,
            modelId = _state.value.modelId.orEmpty(),
            createdAt = now,
            updatedAt = now,
            messages = emptyList(),
        )
        conversation = fresh
        publish(fresh)
        _state.value = _state.value.copy(streaming = "", error = null)
    }

    /**
     * Opens a conversation from history.
     *
     * The runtime's conversation is rebuilt with the transcript seeded into it.
     * Resetting without seeding would leave the model answering a conversation it
     * has never seen: the screen shows twenty messages and the model has none, so
     * the same question gets a different answer before and after a restart.
     */
    /**
     * Opens a conversation and rebuilds the runtime to match it.
     *
     * Serialised, and guarded by the last id the user asked for. Two selections in
     * quick succession each suspend twice -- the file read, then a reseed that can
     * block for up to ten seconds behind a running generation -- and their
     * completions can interleave in any order. Without this the screen could end
     * up showing one conversation while the model had been told another, and the
     * user could not tell.
     */
    fun openConversation(id: String) {
        if (conversation?.id == id) return
        wantedConversationId = id
        if (generationJob?.isActive == true) stop()

        viewModelScope.launch {
            openLock.withLock {
                val loaded = chatStore.load(id)
                if (loaded == null) {
                    // Deleted, or the file is unreadable. The screen keeps showing
                    // what it has rather than blanking on a missing file.
                    if (wantedConversationId == id) {
                        _state.value = _state.value.copy(error = UiMessage(R.string.err_conversation_open))
                    }
                    return@withLock
                }

                // What a save of this copy may not overwrite. See [withCurrentTitle].
                baselineTitle = loaded.title

                // A later choice arrived while this one was being read. Showing it
                // now would put one conversation on screen with another in the
                // runtime; the later call is already queued behind this lock.
                if (wantedConversationId != id) return@withLock

                // A reply started while this was loading. Replacing the transcript
                // now would take the question it is answering off the screen, and
                // the reply would then be written into a conversation the user is
                // not looking at.
                if (_state.value.generating) return@withLock

                conversation = loaded
                publish(loaded)
                _state.value = _state.value.copy(streaming = "", error = null)

                reseedRuntimeWith(loaded)
            }
        }
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
    private fun List<ChatMessage>.historyForRuntime(): List<EngineTurn> {
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
    private var droppedTurns: Int = 0

    /**
     * Re-reads what is on disk. Called after returning from the model list.
     *
     * Ignored while a reply is streaming: unloading or reloading the model
     * closes the native conversation the running generation is reading from.
     */
    init {
        // Follows the setting rather than reading it once. Turning the timestamps
        // on and seeing nothing change until the app is reopened reads as the
        // switch being broken.
        viewModelScope.launch {
            settingsStore.settings.collect { applyDisplaySettings(it) }
        }
    }

    fun refresh() {
        if (_state.value.generating) return
        viewModelScope.launch {
            refreshModels()

            val current = conversation ?: return@launch
            val loaded = chatStore.load(current.id) ?: return@launch

            // Only accept a newer version. A save can be in flight while this
            // read happens, and replacing in-memory state with the older copy
            // would make a completed reply disappear -- and then be written back
            // over the good version.
            if (loaded.updatedAt > current.updatedAt) {
                conversation = loaded
                baselineTitle = loaded.title
                publish(loaded)
            }
        }
    }

    // --------------------------------------------------------------- model ---

    /** Loads [descriptor] into the engine, replacing whatever was loaded. */
    fun selectModel(descriptor: ModelDescriptor) {
        if (_state.value.generating || _state.value.loadingModel) return

        // Asked before the load rather than detected during it. A load that runs
        // out of memory does not always fail politely -- the process can be ended
        // outright, with nothing on screen to say why -- and that cannot be caught
        // from here. An estimate and a question can.
        val verdict = deviceMemory.judge(descriptor.sizeBytes)
        if (verdict is ModelMemory.Verdict.TooBig) {
            _state.value = _state.value.copy(
                ramWarning = PendingRamWarning(descriptor, verdict.requiredBytes, verdict.budgetBytes),
            )
            return
        }

        viewModelScope.launch { loadModel(descriptor) }
    }

    /** The user decided to try anyway. */
    fun confirmRamWarning() {
        val warning = _state.value.ramWarning ?: return
        _state.value = _state.value.copy(ramWarning = null)
        viewModelScope.launch { loadModel(warning.descriptor) }
    }

    /** The user decided against it. */
    fun dismissRamWarning() {
        _state.value = _state.value.copy(ramWarning = null)
    }

    // ------------------------------------------------------------ internals ---

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
    private fun Conversation.stamp(now: Long = clock()): Long =
        maxOf(now, updatedAt + 1)

    private fun applyDisplaySettings(settings: Settings) {
        _state.value = _state.value.copy(showTimestamps = settings.showTimestamps)
    }

    private fun publish(conversation: Conversation) {
        _state.value = _state.value.copy(
            messages = conversation.messages,
            // Published with the messages because they are the same fact seen two
            // ways: which conversation is open, and what is in it. The history
            // screen needs the first to mark the one the user is in, and it has no
            // other way to find out.
            conversationId = conversation.id,
        )
    }

    private suspend fun openMostRecentOrStartNew() {
        val existing = chatStore.list().firstOrNull()
        if (existing != null) {
            conversation = existing
            publish(existing)
        } else {
            val now = clock()
            val fresh = Conversation(
                id = UUID.randomUUID().toString(),
                title = Conversation.UNNAMED,
                modelId = "",
                createdAt = now,
                updatedAt = now,
            )
            conversation = fresh
            publish(fresh)
        }
    }

    private suspend fun refreshModels() {
        // Off the main thread: this stats every model file on the device, and it
        // runs from the ViewModel's start-up and again on every return to the
        // screen.
        val installed = withContext(Dispatchers.IO) { modelStore.installedModels(registry.models.value) }
        _state.value = _state.value.copy(installedModels = installed, scanned = true)

        if (installed.isEmpty()) {
            // Nothing to chat with. Leaving an engine loaded from a model that
            // has since been deleted would be worse than idle -- and leaving the
            // id set would keep the composer enabled with nothing behind it.
            if (engine.state.value !is EngineState.Idle) engine.unload()
            _state.value = _state.value.copy(modelId = null, modelDisplayName = null)
            return
        }

        // A load already in progress decides which model wins. Checking the
        // engine's state instead would read null while it is Loading and start a
        // second, competing load of a different model -- reading gigabytes twice
        // and letting the earlier choice be reverted by the later one.
        if (_state.value.loadingModel) return

        val ready = engine.state.value as? EngineState.Ready
        val readyDescriptor = ready?.let { state -> installed.firstOrNull { it.id == state.modelId } }

        if (readyDescriptor != null) {
            // The engine outlives this ViewModel -- it is application-scoped so a
            // model stays loaded while the user visits the model list. A ViewModel
            // recreated while it is still Ready therefore has to ADOPT it. Not
            // publishing the id here left modelId null for the rest of the
            // session: the composer stayed disabled, no model was offered, and
            // with one model installed the picker is disabled too, so the app had
            // no way back to working order.
            _state.value = _state.value.copy(
                modelId = readyDescriptor.id,
                modelDisplayName = readyDescriptor.displayName,
                contextWindow = ready.contextWindow,
            )
            // Adopted, so the runtime holds whatever the previous ViewModel left
            // in it -- which may be a conversation this one has never seen, or the
            // emptied one a model load left behind. It is rebuilt from what is on
            // screen, for the same reason a load is.
            viewModelScope.launch { reseedRuntimeWith(conversation) }
            return
        }

        // Prefer whatever the open conversation used, so reopening a chat does
        // not silently switch models.
        val preferred = conversation?.modelId?.let { id -> installed.firstOrNull { it.id == id } }
        loadModel(preferred ?: installed.first())
    }

    private suspend fun loadModel(descriptor: ModelDescriptor) {
        // Serialised, and deduplicated per descriptor. Two loads of the same
        // model would each read gigabytes and race to install the result.
        val proceed = loadLock.withLock {
            val alreadyReady = (engine.state.value as? EngineState.Ready)?.modelId == descriptor.id
            if (alreadyReady || inFlightLoad == descriptor.id) {
                false
            } else {
                inFlightLoad = descriptor.id
                true
            }
        }
        if (!proceed) return

        try {
            _state.value = _state.value.copy(loadingModel = true, error = null)

            val local = LocalModel(
                id = descriptor.id,
                displayName = descriptor.displayName,
                file = modelStore.modelFile(descriptor),
            )

            // The whole sampling configuration, not just the temperature.
            //
            // The runtime takes all four values together when a conversation is
            // created and offers nothing per call, so a value that is not passed
            // here cannot be applied at all -- which is what used to be the case
            // for topK, topP and seed. The settings screen still owns temperature
            // and reply length; the rest come from the app's own defaults unless
            // this model has its own, which is what makes a per-model choice mean
            // anything.
            val settings = settingsStore.settings.value
            val result = engine.load(
                local,
                chosenBackend(),
                settings.effectiveSystemPrompt,
                settings.samplingFor(descriptor.id),
            )
            _state.value = _state.value.copy(loadingModel = false)

            // The result carries the backend that was actually chosen, and nothing
            // here needs it: the state already names the model, and the processor
            // reaches the screen through the engine's own state. There used to be a
            // println here that logged it, which was the only println in the
            // project -- a debug line left in the path that runs every time a model
            // loads, and the one place a user's screen could be filled with output
            // nobody asked for.
            result.onSuccess {
                // The runtime's conversation belonged to the model that was just
                // replaced, so it is rebuilt from the transcript rather than
                // merely emptied. Emptying it was the first version of this, and
                // it left the app's worst state: on every cold start the screen
                // showed a long conversation and the model had been told nothing,
                // so the next question was answered as if the chat had never
                // happened -- and no action of the user's could repair it.
                reseedRuntimeWith(conversation)

                conversation?.let {
                    val updated = it.copy(modelId = descriptor.id)
                    conversation = updated
                    persist(updated)
                }
                _state.value = _state.value.copy(
                    modelId = descriptor.id,
                    modelDisplayName = descriptor.displayName,
                    error = null,
                )
            }.onFailure { error ->
                // What went wrong, said in terms someone can act on, with the
                // runtime's own account kept for the report.
                //
                // The runtime's text was the whole message before this, and it is
                // a stack of library names and a number: readable to whoever wrote
                // the library and to nobody else.
                //
                // The engine's own sentence wins when it has one. It is the only
                // layer that saw that every backend was rejected, so it is the only
                // one that can say which kind -- a GPU the device does not have, an
                // NPU that was asked for and is absent. Classifying the raw text
                // here instead would file those under a general "it did not load",
                // which is true and useless.
                val engineMessage = (error as? EngineLoadFailure)?.sentence
                val failure = LoadFailure.of(error)
                _state.value = _state.value.copy(
                    modelId = null,
                    modelDisplayName = null,
                    loadFailure = failure,
                    error = engineMessage
                        ?: UiMessage(failure.kind.messageRes(), descriptor.displayName)
                            .withDetail(failure.detail.orEmpty()),
                )
            }
        } finally {
            loadLock.withLock { inFlightLoad = null }
        }
    }

    /** Writes a finished reply into the conversation and to disk. */
    private fun commitReply(
        into: Conversation,
        text: String,
        elapsedMillis: Long?,
        /** How many tokens the runtime produced, when it said so. */
        tokens: Int?,
        /** The model's reasoning trace, when it produced one. */
        reasoning: String?,
        /** How the runtime spent the time, when it said. */
        timing: GenerationTiming?,
        /** How full the conversation was after this turn. */
        contextTokens: Int?,
        interrupted: Boolean,
    ) {
        if (text.isBlank()) {
            // Nothing is recorded, because there is no answer to record. What the
            // screen says afterwards depends on whether anything arrived at all:
            //
            //   - A reasoning model that spent its whole budget thinking and never
            //     reached an answer gets told so. Before this it got a stopped
            //     spinner and an unchanged transcript, indistinguishable from the
            //     app having ignored the question.
            //   - A model that produced nothing at all -- no text, no trace -- stays
            //     quiet, because saying it "thought" would be a fabrication. That
            //     is reachable in practice: the full-precision catalogue entry
            //     loaded, reported CPU, and ended cleanly with no text at all.
            //
            // Both branches are pinned by tests in ChatViewModelTest, which is what
            // makes this a decision rather than an oversight.
            _state.value =
                _state.value.copy(
                    generating = false,
                    streaming = "",
                    error =
                        if (reasoning.isNullOrBlank()) null
                        else UiMessage(R.string.chat_reasoned_only),
                )
            return
        }

        _state.value = _state.value.copy(contextTokens = contextTokens)
        // Carried from the reply this replaced, then cleared. A regenerate is the
        // only thing that sets it, and it applies to exactly one generation.
        val previous = pendingPreviousReply
        pendingPreviousReply = null
        val reply = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.ASSISTANT,
            text = text,
            createdAt = clock(),
            modelId = _state.value.modelId,
            elapsedMillis = elapsedMillis,
            tokenCount = tokens,
            reasoning = reasoning,
            timeToFirstTokenMillis = timing?.timeToFirstTokenMillis,
            decodeTokensPerSecond = timing?.decodeTokensPerSecond,
            interrupted = interrupted,
            versions = previous?.let { earlier ->
                // The earlier text and the new text, oldest first, with the new
                // one selected -- the same result withNewVersion would give, built
                // here because this reply is being constructed, not updated.
                val all = (if (earlier.versions.isEmpty()) listOf(earlier.text) else earlier.versions) + text
                all
            } ?: emptyList(),
        ).let { fresh ->
            if (fresh.versions.isEmpty()) fresh else fresh.copy(versionIndex = fresh.versions.lastIndex)
        }

        // Applied to the conversation the generation started in, not to whatever
        // is open now: a reply must never land in a chat the user switched to.
        val updated = into.copy(
            messages = into.messages + reply,
            updatedAt = into.stamp(reply.createdAt),
        )

        // The user can reach this only if the conversation is still open, because
        // switching calls stop() and a cancelled generation emits nothing. Kept
        // because it is the correct thing to do regardless of what the runtime
        // guarantees, and because a future engine that finishes a cancelled
        // request would otherwise write a reply into the wrong chat.
        //
        // The message and the end of generation are published together. Written
        // separately, there is a moment where the reply is on screen and the
        // screen still believes it is generating -- which shows as a spinner next
        // to the finished answer, and makes "wait until the reply is there, then
        // ask again" a race that fails about half the time.
        val landed = updated.takeIf { conversation?.id == into.id }
        if (landed != null) conversation = landed
        _state.value = _state.value.copy(
            messages = landed?.messages ?: _state.value.messages,
            generating = false,
            streaming = "",
        )

        persist(updated)
    }

    /** Fire-and-forget write, with failures surfaced instead of crashing. */
    private fun persist(conversation: Conversation) {
        viewModelScope.launch { saveNow(conversation) }
    }

    /**
     * The backend the user asked for.
     *
     * Read at load time rather than held, so changing the setting takes effect on
     * the next load without the chat screen having to watch a settings file it
     * otherwise has no interest in.
     */
    private fun chosenBackend(): InferenceBackend =
        inferenceBackendNamed(settingsStore.settings.value.backend)

    /**
     * How this reply should be generated.
     *
     * Read at send time rather than held, so a change on the settings screen
     * applies to the next message without the chat screen watching a file it has
     * no other interest in.
     */
    private fun generationConfig(): GenerationConfig {
        val settings = settingsStore.settings.value
        return GenerationConfig(
            maxTokens = settings.maxReplyTokens,
            temperature = settings.temperature,
        )
    }

    /**
     * Writes [conversation] to disk and waits for it.
     *
     * Failures are reported rather than thrown. Saving is asynchronous and its
     * coroutine has no exception handler, so an unhandled write error -- a full
     * disk is the obvious one -- would take the process down and lose the
     * conversation with it.
     *
     * Counted on both sides of the lock, which is the only reason there are two
     * counters for one line of work. A test waits for a conversation to reach disk;
     * when that wait times out, `ChatStore.saveAudit` reports that `save` was never
     * called, which says the loss is upstream of the store -- but not whether this
     * function ran and blocked on `saveLock`, or was never reached at all. Those are
     * different bugs in different places, and the counters are what tells them
     * apart: `entered > 0 && stored == 0` means the lock, `entered == 0` means the
     * caller never got here.
     */
    private suspend fun saveNow(conversation: Conversation) {
        saveNowCalls++
        try {
            saveLock.withLock {
                saveNowThroughLock++
                chatStore.save(withCurrentTitle(conversation))
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            _state.value = _state.value.copy(
                error = UiMessage(R.string.err_conversation_save)
                    .withDetail(t.message ?: t::class.simpleName),
            )
        }
    }

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
    private var baselineTitle: String? = null

    /**
     * [conversation] with the fields this screen does not own taken from disk.
     *
     * A save writes the whole conversation, so any field this screen never set is
     * written back at whatever value it had when the conversation was opened. Two
     * fields have that problem and they need opposite treatment:
     *
     *  - **The title** is decided in two places, so one has to defer. This screen
     *    wins when it has changed the title since it was last in step, and the disk
     *    wins otherwise — see [baselineTitle].
     *  - **The pin** is decided in exactly one place, the history screen. Nothing
     *    here ever changes it, so the disk always wins. Without this, pinning a
     *    conversation and then sending a message in it would silently unpin it, and
     *    the user would watch the change they just made come undone.
     *
     * The deferral lives here rather than in [com.example.demoapp.data.chat.ChatStore]
     * because only the caller knows it is holding a copy that may have gone stale.
     * The store cannot tell a rename from a chat save that happens to carry an old
     * title, and a rule it applied to both would refuse the rename itself.
     */
    private suspend fun withCurrentTitle(conversation: Conversation): Conversation {
        // Read the previous baseline, then move it forward. The order is the whole
        // mechanism: assigning first makes `conversation.title != baseline` false
        // every time, so this screen's own edit is never recognised and the disk's
        // older title is written back over it. That was the bug, and it is invisible
        // in a test that only renames from elsewhere.
        val baseline = baselineTitle
        baselineTitle = conversation.title

        val stored = runCatching { chatStore.load(conversation.id) }.getOrNull() ?: return conversation
        val withPin = conversation.copy(pinnedAt = stored.pinnedAt)

        // This screen changed the title since it was last in step, so its version is
        // the newer one and must survive the save.
        if (baseline != null && conversation.title != baseline) return withPin
        // Nothing to keep: a conversation on disk with no messages has not named
        // itself yet, and the first message is about to.
        if (stored.messages.isEmpty()) return withPin
        if (stored.title == conversation.title) return withPin
        return withPin.copy(title = stored.title)
    }
}
