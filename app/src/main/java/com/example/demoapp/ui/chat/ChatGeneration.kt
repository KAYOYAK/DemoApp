package com.example.demoapp.ui.chat

// Asking the model, streaming the reply, stopping it, and asking again.
//
// Extensions on [ChatViewModel]: the state they share lives in the class.

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
 * Sends [text] and streams the reply.
 *
 * The user's message is committed to disk before the model is asked, and
 * that is awaited rather than fired off. If the process dies while the model
 * is thinking, the question is still there when the app is reopened; losing
 * the answer is recoverable, losing what the user typed is not.
 */
fun ChatViewModel.send(text: String) {
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
internal suspend fun ChatViewModel.runGeneration(into: Conversation, prompt: String) {
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
fun ChatViewModel.stop() {
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
internal fun ChatViewModel.canAskTheModel(): Boolean =
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
fun ChatViewModel.regenerate() {
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
internal suspend fun ChatViewModel.reseedRuntimeWith(
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

/** Writes a finished reply into the conversation and to disk. */
internal fun ChatViewModel.commitReply(
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

/**
 * The backend the user asked for.
 *
 * Read at load time rather than held, so changing the setting takes effect on
 * the next load without the chat screen having to watch a settings file it
 * otherwise has no interest in.
 */
internal fun ChatViewModel.chosenBackend(): InferenceBackend =
    inferenceBackendNamed(settingsStore.settings.value.backend)

/**
 * How this reply should be generated.
 *
 * Read at send time rather than held, so a change on the settings screen
 * applies to the next message without the chat screen watching a file it has
 * no other interest in.
 */
internal fun ChatViewModel.generationConfig(): GenerationConfig {
    val settings = settingsStore.settings.value
    return GenerationConfig(
        maxTokens = settings.maxReplyTokens,
        temperature = settings.temperature,
    )
}
