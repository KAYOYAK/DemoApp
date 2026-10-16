package com.kayoyak.demoapp.ui.settings

import com.kayoyak.demoapp.R

import com.kayoyak.demoapp.ui.UiMessage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayoyak.demoapp.data.settings.AppTheme
import com.kayoyak.demoapp.data.settings.sanitisedFontScale
import com.kayoyak.demoapp.data.settings.MAX_SAVED_PROMPTS
import com.kayoyak.demoapp.data.settings.SavedPrompt
import com.kayoyak.demoapp.data.settings.Settings
import com.kayoyak.demoapp.data.settings.SettingsStore
import com.kayoyak.demoapp.inference.EngineState
import com.kayoyak.demoapp.inference.InferenceBackend
import com.kayoyak.demoapp.inference.LlmEngine
import com.kayoyak.demoapp.inference.inferenceBackendNamed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val backend: InferenceBackend = InferenceBackend.AUTO,
    /** What is actually running, which a device may have had to choose differently. */
    val backendInUse: InferenceBackend? = null,
    /** The prompt as the user wrote it, empty when they never have. */
    val systemPrompt: String = "",
    /** What is actually sent, after the empty-means-default rule. */
    val effectiveSystemPrompt: String = Settings.DEFAULT_SYSTEM_PROMPT,
    val maxReplyTokens: Int = 512,
    val temperatureTenths: Int = 8,
    /** Which appearance to use. */
    val theme: AppTheme = AppTheme.SYSTEM,
    /** Whether the app asks for the device lock before showing anything. */
    val lockEnabled: Boolean = false,
    /** Whether every message carries the time it was written. */
    val showTimestamps: Boolean = false,
    /**
     * Whether this device has anything to check.
     *
     * Carried so the switch can say it will not work here, rather than accepting
     * the tap and leaving the user locked out of their own notes.
     */
    val canAuthenticate: Boolean = false,
    /** Text size, as a percentage of the system's own. */
    val fontScalePercent: Int = 100,
    /**
     * True when a change cannot take effect until the model is loaded again.
     *
     * The runtime takes the system prompt when a conversation is created, so an
     * edit does not reach a model that is already running. Saying so is better
     * than applying it silently to the next message and leaving the user to work
     * out why nothing changed.
     */
    val needsReload: Boolean = false,
    /**
     * Instructions the user saved, most recently added last.
     *
     * Exposed as the state rather than read from the store in the screen, so the row
     * redraws when one is saved or deleted without the screen holding a second copy
     * of the truth.
     */
    val savedPrompts: List<SavedPrompt> = emptyList(),
    /** A change that could not be written. */
    val error: UiMessage? = null,
) {
    /**
     * The temperature as a fraction, for the custom-value line.
     *
     * `fontScale` used to sit beside this, as `fontScalePercent / 100f`. It was
     * removed rather than kept for symmetry: nothing read it, and the only place
     * that looked like a reader -- the sample line in `AppearanceSection` -- was
     * multiplying an already-scaled `MaterialTheme.typography` value by it, which
     * is what made that sample wrong. Leaving the property would have left the
     * next person with the same mistake one keystroke away.
     */
    val temperature: Double get() = temperatureTenths / 10.0
    val usingDefaultPrompt: Boolean get() = systemPrompt.isBlank()
}

/**
 * The choices that shape every reply.
 *
 * The system prompt is here rather than hidden because it is not cosmetic: it is
 * the difference between a model that answers and one that continues the text.
 * The shipped default was measured to matter, and a user who wants a different
 * manner -- more formal, answering in another language, refusing a topic -- has
 * nowhere else to say so.
 */
class SettingsViewModel(
    private val settingsStore: SettingsStore,
    /** Whether this device can check anything. Defaults to no for a caller with no device. */
    private val canAuthenticate: Boolean = false,
    /** The device's own word on whether the lock can be offered. */
    private val deviceAuth: Boolean = canAuthenticate,
    private val engine: LlmEngine? = null,
) : ViewModel() {

    private val error = MutableStateFlow<UiMessage?>(null)
    private val savedRevision = MutableStateFlow(0L)

    val state: StateFlow<SettingsUiState> =
        combine(
            settingsStore.settings,
            engine?.state ?: MutableStateFlow(EngineState.Idle),
            error,
            savedRevision,
        ) { settings, engineState, error, _ ->
            SettingsUiState(
                backend = inferenceBackendNamed(settings.backend),
                backendInUse = (engineState as? EngineState.Ready)?.backendInUse,
                systemPrompt = settings.systemPrompt,
                effectiveSystemPrompt = settings.effectiveSystemPrompt,
                maxReplyTokens = settings.maxReplyTokens,
        lockEnabled = settings.lockEnabled,
        showTimestamps = settings.showTimestamps,
        canAuthenticate = deviceAuth,
                temperatureTenths = settings.temperatureTenths,
                theme = settings.chosenTheme,
                fontScalePercent = settings.fontScalePercent,
                savedPrompts = settings.savedPrompts,
                needsReload = engineState is EngineState.Ready,
                error = error,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(
                backend = inferenceBackendNamed(settingsStore.settings.value.backend),
                systemPrompt = settingsStore.settings.value.systemPrompt,
                effectiveSystemPrompt = settingsStore.settings.value.effectiveSystemPrompt,
                maxReplyTokens = settingsStore.settings.value.maxReplyTokens,
                temperatureTenths = settingsStore.settings.value.temperatureTenths,
                theme = settingsStore.settings.value.chosenTheme,
                fontScalePercent = settingsStore.settings.value.fontScalePercent,
                backendInUse = (engine?.state?.value as? EngineState.Ready)?.backendInUse,
                needsReload = engine?.state?.value is EngineState.Ready,
            ),
        )

    /**
     * Writes a change.
     *
     * Failures are reported rather than thrown: the settings file is a file, the
     * write can fail, and a setting that silently did not save is one the user
     * will find reverted the next time they open the app.
     */
    private fun change(update: (Settings) -> Settings) {
        viewModelScope.launch {
            try {
                settingsStore.update(update)
                savedRevision.value += 1
            } catch (t: Throwable) {
                error.value = UiMessage(R.string.err_settings_save)
                    .withDetail(t.message ?: t::class.simpleName)
            }
        }
    }

    fun chooseBackend(backend: InferenceBackend) =
        change { it.copy(backend = backend.name) }

    fun setSystemPrompt(prompt: String) =
        change { it.copy(systemPrompt = prompt) }

    /** Puts the prompt back to the shipped wording, by forgetting the user's. */
    fun resetSystemPrompt() =
        change { it.copy(systemPrompt = "") }

    /**
     * Saves [body] under [name], replacing any entry with that name.
     *
     * Replacing rather than refusing a duplicate: the name is the user's handle for
     * the text, and someone who saves "翻译" twice means "this is what 翻译 means to
     * me now", not "keep both and let me guess which is which".
     */
    fun savePrompt(name: String, body: String) {
        val trimmedName = name.trim()
        val trimmedBody = body.trim()
        if (trimmedName.isEmpty() || trimmedBody.isEmpty()) return
        change { settings ->
            val without = settings.savedPrompts.filterNot { it.name == trimmedName }
            settings.copy(
                savedPrompts = (without + SavedPrompt(trimmedName, trimmedBody))
                    .takeLast(MAX_SAVED_PROMPTS),
            )
        }
    }

    fun deletePrompt(name: String) =
        change { settings ->
            settings.copy(savedPrompts = settings.savedPrompts.filterNot { it.name == name })
        }

    fun setMaxReplyTokens(tokens: Int) =
        change { it.copy(maxReplyTokens = tokens) }

    fun setTemperatureTenths(tenths: Int) =
        change { it.copy(temperatureTenths = tenths) }

    fun chooseTheme(theme: AppTheme) =
        change { it.copy(theme = theme.name) }

    fun setFontScalePercent(percent: Int) =
        change { it.copy(fontScalePercent = percent.sanitisedFontScale()) }

    /**
     * Turns the app lock on or off.
     *
     * Refused on a device with nothing to authenticate with. Turning it on there
     * would shut the user out of their own notes with no way back in -- the way back
     * in would be uninstalling the app, which loses the notes.
     */
    fun setLockEnabled(enabled: Boolean, available: Boolean) {
        if (enabled && !available) {
            error.value = UiMessage(R.string.settings_lock_unavailable)
            return
        }
        change { it.copy(lockEnabled = enabled) }
    }

    fun setShowTimestamps(show: Boolean) =
        change { it.copy(showTimestamps = show) }

    fun dismissError() {
        error.value = null
    }
}
