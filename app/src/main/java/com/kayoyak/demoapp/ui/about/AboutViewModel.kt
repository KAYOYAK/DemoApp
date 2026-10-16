package com.kayoyak.demoapp.ui.about

import com.kayoyak.demoapp.R

import com.kayoyak.demoapp.ui.UiMessage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayoyak.demoapp.data.chat.ChatStore
import com.kayoyak.demoapp.data.chat.SecureDelete
import com.kayoyak.demoapp.data.settings.Settings
import com.kayoyak.demoapp.data.settings.SettingsStore
import com.kayoyak.demoapp.model.ModelDownloadCoordinator
import com.kayoyak.demoapp.model.ModelCatalogue
import com.kayoyak.demoapp.model.ModelStore
import com.kayoyak.demoapp.inference.EngineState
import com.kayoyak.demoapp.inference.LlmEngine
import com.kayoyak.demoapp.model.InMemoryModelRegistry
import com.kayoyak.demoapp.model.ModelRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AboutUiState(
    /** Bytes held by chat history, so a user can see what is being kept. */
    val chatBytes: Long = 0L,
    /** Bytes held by model files. */
    val modelBytes: Long = 0L,
    val conversationCount: Int = 0,
    val scanning: Boolean = true,
    /** Non-null while the confirmation for erasing everything is on screen. */
    val confirmingErase: Boolean = false,
    val eraseReport: UiMessage? = null,
    /** Whether a free-space pass is running, and what the last one wrote. */
    val wiping: Boolean = false,
    val wipedBytes: Long? = null,
    val error: UiMessage? = null,
    /**
     * What the engine is running, and what is installed.
     *
     * Here because the exported diagnostics report asks for them and nothing was
     * supplying them: `DiagnosticsReport.Facts` declares `modelId`, `backend`,
     * `contextTokens`, `contextWindow` and `installedModelCount`, and every one of
     * them has a default -- so the single place that builds a `Facts` omitted four
     * of the five without a word from the compiler, and the report a user sends
     * after "it got slow" said `model: none`, `backend: none`, `installed models:
     * 0` on every device, including one with a model resident and more on disk. The
     * four lines of label text those fields exist to print had no other use.
     *
     * `contextTokens` is not here: it is the current conversation's occupancy, this
     * screen holds no conversation, and a number invented for a diagnostic report
     * is worse than a gap.
     */
    val runningModelId: String? = null,
    val runningBackend: String? = null,
    val contextWindow: Int? = null,
    val installedModelCount: Int = 0,
) {
    val totalBytes: Long get() = chatBytes + modelBytes
    val hasAnything: Boolean get() = chatBytes > 0L || modelBytes > 0L || conversationCount > 0
}

/**
 * What the app is, and a way to prove it kept nothing.
 *
 * The privacy promise is that what the user types stays on the device. A promise
 * a user cannot check is only a claim, so the screen shows what is actually held
 * and offers to remove all of it.
 */
class AboutViewModel(
    private val chatStore: ChatStore,
    private val modelStore: ModelStore,
    private val coordinator: ModelDownloadCoordinator,
    private val settingsStore: SettingsStore,
    /** Which models exist. Erasing has to include the ones the user imported. */
    private val registry: ModelRegistry = InMemoryModelRegistry(),
    /**
     * The engine, if one has been built.
     *
     * Held so erasing can release it: it keeps the model's file open and several
     * gigabytes resident, and leaving it running a model that was just deleted is
     * a state the user did not ask for and cannot see.
     */
    private val engine: LlmEngine? = null,
    /**
     * The directory the app owns, for the parts no store owns.
     *
     * The stores delete what they were given: conversations, models, settings, the
     * imported-model registry. Two things were not given to any of them -- the crash
     * log under `diagnostics/` and the runtime's scratch directory -- and erasing
     * left both behind while the screen said everything had gone. A file an app
     * wrote and did not delete is the app's data whatever it contains.
     */
    private val appFilesDir: java.io.File? = null,
) : ViewModel() {

    // There were two private StateFlows here -- `wiping` and `wiped` -- holding the
    // progress and result of a free-space pass, while `AboutUiState` carried fields
    // of the same names. Nothing observed the private pair and nothing wrote the
    // state fields, so the screen read a value that was always its own default:
    // the button never greyed out, never became "writing", and never reported what
    // it wrote. On a pass the app's own copy says may take a long time and wears
    // the flash, the user could not tell a running wipe from a tap that did
    // nothing -- and the guard below made a second tap silent. They write the state
    // now, which is what the screen was always reading.

    private val _state = MutableStateFlow(AboutUiState())
    val state: StateFlow<AboutUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val disk = withContext(Dispatchers.IO) {
                DiskRead(
                    chats = chatStore.bytesUsed(),
                    models = modelStore.totalBytesUsed(),
                    conversations = chatStore.list().size,
                    installed = modelStore.installedModels(registry.models.value).size,
                )
            }
            _state.value =
                _state.value.copy(
                    chatBytes = disk.chats,
                    modelBytes = disk.models,
                    conversationCount = disk.conversations,
                    installedModelCount = disk.installed,
                    scanning = false,
                    // Read at the same moment as the disk figures rather than held
                    // from a previous refresh: the report is a snapshot, and half of
                    // it describing an earlier moment is worse than a stale one.
                    runningModelId = ready()?.modelId,
                    runningBackend = ready()?.backendInUse?.name,
                    contextWindow = ready()?.contextWindow,
                )
        }
    }

    /** What the engine holds right now, or null when nothing is loaded. */
    private fun ready(): EngineState.Ready? = engine?.state?.value as? EngineState.Ready

    /** One IO block's worth of disk figures. */
    private data class DiskRead(
        val chats: Long,
        val models: Long,
        val conversations: Int,
        val installed: Int,
    )

    fun requestErase() {
        _state.value = _state.value.copy(confirmingErase = true)
    }

    fun cancelErase() {
        _state.value = _state.value.copy(confirmingErase = false)
    }

    /**
     * Removes every conversation and every downloaded model.
     *
     * Models are included because they are the largest thing the app holds and a
     * user freeing space should not have to visit two screens. The catalogue is
     * not a file, so nothing about which models exist is lost.
     *
     * Downloads are stopped first: deleting a file a transfer still holds open
     * leaves the transfer running into an inode nothing can reach, which is the
     * same reason the model screen stops one before deleting it.
     */
    fun confirmErase() {
        _state.value = _state.value.copy(confirmingErase = false)
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    chatStore.list().forEach { chatStore.delete(it.id) }

                    // The engine first. It holds the model's file open and keeps
                    // several gigabytes resident; deleting the file underneath it
                    // leaves the app running a model that no longer exists, which
                    // looks fine until something asks it to generate.
                    engine?.unload()

                    // Everything the registry knows about, not just the catalogue: a
                    // promise to remove everything that left an imported file behind
                    // is the most visibly false version of it.
                    registry.models.value.forEach { model ->
                        coordinator.delete(model)
                        if (model.url == null) registry.forgetImported(model.id)
                    }
                    // Back to the shipped defaults, so nothing about how the app
                    // was used survives either.
                    settingsStore.update { Settings() }

                    // What no store owns. `diagnostics/` holds the last crash — a
                    // stack trace with this device's paths and package name in it,
                    // which is exactly the sort of thing a person wiping their data
                    // expects to be gone. `inference-cache/` is the runtime's own
                    // scratch space, and the runtime is unloaded above, so nothing
                    // is using it now.
                    appFilesDir?.let { root ->
                        listOf("diagnostics", "inference-cache").forEach { name ->
                            File(root, name).deleteRecursively()
                        }
                    }
                }.exceptionOrNull()
            }

            _state.value = _state.value.copy(
                error =
                    failure?.let {
                        UiMessage(R.string.err_erase_failed)
                            .withDetail(it.message ?: it::class.simpleName)
                    },
                eraseReport = if (failure == null) UiMessage(R.string.erase_done) else null,
            )
            refresh()
        }
    }

    /**
     * Writes over the app's free space, once.
     *
     * The one action here that takes real time and has a real cost, which is why
     * it is a button with the cost written above it rather than something done
     * after a delete. Two gigabytes: enough to be a real pass on a phone, bounded
     * so the button finishes.
     */
    fun wipeFreeSpace(directory: java.io.File, budgetBytes: Long) {
        if (_state.value.wiping) return
        _state.value = _state.value.copy(wiping = true, wipedBytes = null)
        viewModelScope.launch {
            val written = try {
                SecureDelete.wipeFreeSpace(directory, budgetBytes)
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(wiping = false)
                throw e
            } catch (t: Throwable) {
                // Null, not zero: a pass that never ran must not report a figure.
                null
            }
            _state.value =
                _state.value.copy(
                    wiping = false,
                    // Zero is also reported as nothing.
                    //
                    // `SecureDelete.wipeFreeSpace` returns `0L` for every way a pass
                    // can write nothing -- a path that cannot take a write, a
                    // directory that refused the first chunk -- and it does that
                    // without throwing, so from here it is indistinguishable from "the
                    // pass ran and wrote zero bytes". The screen would print "wrote 0
                    // B" for all of them, and zero is a claim about the flash: a
                    // reader would reasonably conclude the pass ran and did nothing,
                    // when the truth may be that it never started.
                    //
                    // A genuine zero is not worth the ambiguity. The budget the screen
                    // passes is two gigabytes, so a pass that writes nothing has
                    // something wrong with its arguments, and "nothing to report" is
                    // the honest reading of that.
                    wipedBytes = written?.takeIf { it > 0L },
                )
        }
    }

    /**
     * The figure has been read.
     *
     * This had no caller for as long as the value had no screen: the note here used
     * to say that clearing something nobody can see is not a state transition, and
     * that it should come back with a caller when there was one. There is one now --
     * the wipe card shows the figure and offers to dismiss it -- so the clearing is
     * a transition the person on the other side can see.
     */
    fun dismissWipe() {
        _state.value = _state.value.copy(wipedBytes = null)
    }

    fun dismissReport() {
        _state.value = _state.value.copy(eraseReport = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }
}
