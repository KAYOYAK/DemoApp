package com.example.demoapp.ui.chat

// Reading a reply aloud, as far as the view model is concerned: it holds which
// reply is talking and whether a voice exists at all. The speaking itself needs a
// Context and a service connection, which this class has neither of.
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
 * The screen has started or stopped reading [messageId].
 *
 * Told rather than driven: the speech engine needs a Context and a service
 * connection, and this class has neither. What it owns is the one piece of state
 * the screen cannot hold on its own -- which reply is talking -- because that has
 * to survive a recomposition and a scroll.
 */
/** No Chinese voice is installed, so reading aloud cannot work here. */
fun ChatViewModel.reportSpeechUnavailable() {
    _state.value = _state.value.copy(error = UiMessage(R.string.chat_speak_unavailable))
}

fun ChatViewModel.setSpeaking(messageId: String?) {
    if (_state.value.speakingId == messageId) return
    _state.value = _state.value.copy(speakingId = messageId)
}
