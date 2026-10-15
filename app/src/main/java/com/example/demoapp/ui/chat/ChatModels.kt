package com.example.demoapp.ui.chat

// The catalogue, what is installed, loading a model, and the RAM warning.
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

/** Loads [descriptor] into the engine, replacing whatever was loaded. */
fun ChatViewModel.selectModel(descriptor: ModelDescriptor) {
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
fun ChatViewModel.confirmRamWarning() {
    val warning = _state.value.ramWarning ?: return
    _state.value = _state.value.copy(ramWarning = null)
    viewModelScope.launch { loadModel(warning.descriptor) }
}

/** The user decided against it. */
fun ChatViewModel.dismissRamWarning() {
    _state.value = _state.value.copy(ramWarning = null)
}

internal suspend fun ChatViewModel.refreshModels() {
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


internal suspend fun ChatViewModel.loadModel(descriptor: ModelDescriptor) {
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
