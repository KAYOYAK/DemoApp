package com.example.demoapp.ui.chat

// What reaches disk, and which title wins when two screens hold a copy.
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

/** Fire-and-forget write, with failures surfaced instead of crashing. */
internal fun ChatViewModel.persist(conversation: Conversation) {
    viewModelScope.launch { saveNow(conversation) }
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
internal suspend fun ChatViewModel.saveNow(conversation: Conversation) {
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
private suspend fun ChatViewModel.withCurrentTitle(conversation: Conversation): Conversation {
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
