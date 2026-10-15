package com.example.demoapp.ui.chat

// Which conversation is open and what its message list holds: opening, renaming
// through a message, deleting, keeping, the version on screen, refresh.
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

/** Clears the error banner. */

/**
 * Narrows the transcript to what matches.
 *
 * Deliberately not a change to the conversation: nothing is written, nothing is
 * sent to the model, and clearing the field puts the whole transcript back. A
 * search that touched the runtime would make finding a word in a conversation
 * cost the same as asking a question, which is not what the user asked for.
 */
fun ChatViewModel.setSearchQuery(query: String) {
    _state.value = _state.value.copy(searchQuery = query)
}

fun ChatViewModel.dismissError() {
    _state.value = _state.value.copy(error = null)
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

fun ChatViewModel.toggleKeeping(messageId: String) {
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

fun ChatViewModel.selectVersion(messageId: String, index: Int) {
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
fun ChatViewModel.editAndResend(messageId: String, newText: String) {
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
fun ChatViewModel.deleteMessage(messageId: String) {
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
 * Keeps a half-finished reply when the screen goes away.
 *
 * Leaving mid-reply used to discard it: the scope is cancelled, the stream
 * stops, and the text the user watched arrive was never written. `stop()`
 * already treats that text as worth keeping, and pressing Back instead of
 * Stop is not a statement that it is not.
 *
 * The framework closes a ViewModel's closeables -- where `viewModelScope`
 * lives -- before calling `onCleared()`, so this cannot use that scope: it
 * writes on [ChatViewModel.closingWrites] instead. The partial is read here
 * rather than in the write, because the state is gone by the time the write
 * runs.
 */
fun ChatViewModel.onScreenClosed() {
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

    closingWrites.launch {
        // A failure here has nobody left to tell; refusing to write is the
        // only other option and loses the reply.
        runCatching { chatStore.save(updated) }
    }
}

/**
 * Starts an empty conversation. The previous one stays on disk.
 *
 * The runtime's own conversation is reset as well. Keeping it would leave the
 * model still answering from the previous chat while the screen shows an
 * empty one.
 */
fun ChatViewModel.newChat() {
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
fun ChatViewModel.openConversation(id: String) {
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
 * Re-reads what is on disk. Called after returning from the model list.
 *
 * Ignored while a reply is streaming: unloading or reloading the model
 * closes the native conversation the running generation is reading from.
 */


fun ChatViewModel.refresh() {
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

internal fun ChatViewModel.applyDisplaySettings(settings: Settings) {
    _state.value = _state.value.copy(showTimestamps = settings.showTimestamps)
}


internal fun ChatViewModel.publish(conversation: Conversation) {
    _state.value = _state.value.copy(
        messages = conversation.messages,
        // Published with the messages because they are the same fact seen two
        // ways: which conversation is open, and what is in it. The history
        // screen needs the first to mark the one the user is in, and it has no
        // other way to find out.
        conversationId = conversation.id,
    )
}


internal suspend fun ChatViewModel.openMostRecentOrStartNew() {
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
