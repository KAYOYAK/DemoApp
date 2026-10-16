package com.kayoyak.demoapp.ui.settings

import com.kayoyak.demoapp.ui.UiMessageText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.kayoyak.demoapp.data.settings.AppTheme
import com.kayoyak.demoapp.inference.labelRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.kayoyak.demoapp.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kayoyak.demoapp.data.settings.Settings
import com.kayoyak.demoapp.inference.InferenceBackend
import com.kayoyak.demoapp.inference.describe
import com.kayoyak.demoapp.inference.explain

/**
 * How the model runs and what it is told.
 *
 * One screen rather than two, because these are the same kind of choice: a user
 * whose replies are the wrong length, the wrong manner, or too slow is looking
 * for exactly this.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onChooseBackend = viewModel::chooseBackend,
        onSetLock = viewModel::setLockEnabled,
        onSetTimestamps = viewModel::setShowTimestamps,
        onSystemPromptChanged = viewModel::setSystemPrompt,
        onResetSystemPrompt = viewModel::resetSystemPrompt,
        onSavePrompt = viewModel::savePrompt,
        onDeletePrompt = viewModel::deletePrompt,
        onMaxReplyTokens = viewModel::setMaxReplyTokens,
        onTemperature = viewModel::setTemperatureTenths,
        onChooseTheme = viewModel::chooseTheme,
        onChooseFontScale = viewModel::setFontScalePercent,
        onDismissError = viewModel::dismissError,
        modifier = modifier,
    )
}

/** Stateless, so every state can be previewed without a device. */
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    onChooseBackend: (InferenceBackend) -> Unit,
    onSetLock: (Boolean, Boolean) -> Unit,
    onSetTimestamps: (Boolean) -> Unit,
    onSystemPromptChanged: (String) -> Unit,
    onResetSystemPrompt: () -> Unit,
    onSavePrompt: (String, String) -> Unit,
    onDeletePrompt: (String) -> Unit,
    onMaxReplyTokens: (Int) -> Unit,
    onTemperature: (Int) -> Unit,
    onChooseTheme: (AppTheme) -> Unit,
    onChooseFontScale: (Int) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.error?.let { error ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UiMessageText(
                    message = error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissError) { Text(stringResource(R.string.settings_dismiss)) }
            }
        }

        HorizontalDivider()

        AppearanceSection(state, onChooseTheme, onChooseFontScale)

        HorizontalDivider()

        // The lock, before the performance sections.
        //
        // Placed with the other privacy-shaped settings rather than at the bottom:
        // what the app does with the user's words is the thing they came to this
        // screen to decide, and a switch buried under four performance options is
        // one they never find.
        // A display preference, next to the other appearance settings.
        SwitchRow(
            title = R.string.settings_timestamps_title,
            explanation = R.string.settings_timestamps_explainer,
            checked = state.showTimestamps,
            onCheckedChange = onSetTimestamps,
            onLabel = R.string.settings_timestamps_on,
            offLabel = R.string.settings_timestamps_off,
        )

        LockSection(
            enabled = state.lockEnabled,
            available = state.canAuthenticate,
            onSet = onSetLock,
        )

        BackendSection(state, onChooseBackend)

        HorizontalDivider()

        PromptSection(state, onSystemPromptChanged, onResetSystemPrompt, onSavePrompt, onDeletePrompt)

        HorizontalDivider()

        ReplySection(state, onMaxReplyTokens, onTemperature)
    }
}

/**
 * The instruction presets offered beside the editor.
 *
 * Resource ids rather than strings, so a preset's text goes through the same single
 * home every other word in this app does. A literal here would be copy that ships
 * untranslated and is only noticed by someone who is already confused.
 */
private data class PromptPreset(val name: Int, val body: Int)

private val PROMPT_PRESETS = listOf(
    PromptPreset(R.string.preset_general, R.string.preset_general_body),
    PromptPreset(R.string.preset_translate, R.string.preset_translate_body),
    PromptPreset(R.string.preset_code, R.string.preset_code_body),
    PromptPreset(R.string.preset_explain, R.string.preset_explain_body),
    PromptPreset(R.string.preset_short, R.string.preset_short_body),
)

@Composable
private fun BackendSection(
    state: SettingsUiState,
    onChooseBackend: (InferenceBackend) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    CardSection(
        title = R.string.settings_backend_title,
        explanation = R.string.settings_backend_explainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { open = true }) { Text(stringResource(state.backend.describe())) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    InferenceBackend.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(stringResource(option.describe()))
                                    Text(
                                        stringResource(option.explain()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                open = false
                                onChooseBackend(option)
                            },
                        )
                    }
                }
            }
        }

        val inUse = state.backendInUse
        val wantedSpecific = state.backend != InferenceBackend.AUTO
        val fellBack = inUse != null && inUse != state.backend
        Text(
            when {
                inUse == null -> stringResource(R.string.settings_backend_none)

                !fellBack -> stringResource(
                    R.string.settings_backend_running,
                    stringResource(inUse.describe()),
                )

                wantedSpecific -> stringResource(
                    R.string.settings_backend_fell_back,
                    stringResource(state.backend.describe()),
                    stringResource(inUse.describe()),
                )

                else -> stringResource(
                    R.string.settings_backend_running,
                    stringResource(inUse.describe()),
                ) + " " + stringResource(R.string.settings_backend_auto)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (fellBack && wantedSpecific) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * The system prompt.
 *
 * Editable rather than fixed. The shipped wording was measured to turn the same
 * model from one that continues the text into one that answers the question, so
 * it is doing real work -- and a user who wants a different manner has nowhere
 * else to ask for it.
 */
@Composable
private fun PromptSection(
    state: SettingsUiState,
    onChanged: (String) -> Unit,
    onReset: () -> Unit,
    onSavePrompt: (String, String) -> Unit,
    onDeletePrompt: (String) -> Unit,
) {
    // Local while typing, so every keystroke is not a disk write. The value is
    // committed when the field loses focus.
    var draft by remember(state.systemPrompt) { mutableStateOf(state.systemPrompt) }
    var editing by remember { mutableStateOf(false) }
    // The name being asked for, or null. Held here because it is about one dialog
    // being on screen, not about what the app knows.
    var naming by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    // The saved prompt whose delete is being confirmed, or null.
    var deleting by remember { mutableStateOf<String?>(null) }

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.settings_prompt_save_as_title)) },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_prompt_save_as_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = nameDraft.isNotBlank(),
                    onClick = {
                        // Saved and committed together. Storing the instruction
                        // without making it the active one would leave the user
                        // looking at a chip they just made and still typing the text
                        // it already holds.
                        onSavePrompt(nameDraft, draft)
                        onChanged(draft)
                        naming = false
                    },
                ) { Text(stringResource(R.string.settings_prompt_save_as_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) {
                    Text(stringResource(R.string.settings_prompt_save_as_cancel))
                }
            },
        )
    }

    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.settings_prompt_delete, name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePrompt(name)
                    deleting = null
                }) { Text(stringResource(R.string.settings_prompt_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.settings_prompt_save_as_cancel))
                }
            },
        )
    }

    CardSection(
        title = R.string.settings_prompt_title,
        explanation = R.string.settings_prompt_explainer,
    ) {
        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.settings_prompt_label)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            // A starting point, not a setting of its own.
            //
            // An empty instruction box is the hardest thing on this screen to fill
            // in: "tell the model what it is" assumes the user already knows what
            // that sentence is supposed to look like. Picking one drops it into the
            // field to be edited, and what ends up saved is the user's text either
            // way -- the box stays theirs to change.
            Text(
                stringResource(R.string.settings_prompt_presets_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PROMPT_PRESETS.forEach { preset ->
                    // Read here, not inside onClick: stringResource is @Composable
                    // and the click handler is not.
                    val body = stringResource(preset.body)
                    AssistChip(
                        onClick = { draft = body },
                        label = { Text(stringResource(preset.name)) },
                    )
                }
            }

            // The user's own, above the shipped ones in importance even though they
            // sit below: these are the ones someone actually reached for, and the
            // reason the feature exists is that reproducing good wording from memory
            // is the thing nobody does.
            if (state.savedPrompts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_prompt_mine),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.savedPrompts.forEach { saved ->
                        InputChip(
                            selected = false,
                            onClick = { draft = saved.body },
                            label = { Text(saved.name) },
                            trailingIcon = {
                                // A visible remove rather than a long press. A long
                                // press on a chip is a gesture nothing else on this
                                // screen uses, so it would be discoverable only by
                                // accident -- and deleting text the user typed is
                                // not something to learn by accident.
                                IconButton(onClick = { deleting = saved.name }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(
                                            R.string.settings_prompt_delete,
                                            saved.name,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    editing = false
                    if (draft != state.systemPrompt) onChanged(draft)
                }) { Text(stringResource(R.string.settings_prompt_save)) }
                if (draft.isNotBlank()) {
                    TextButton(onClick = {
                        nameDraft = ""
                        naming = true
                    }) { Text(stringResource(R.string.settings_prompt_save_as)) }
                }
                TextButton(onClick = {
                    editing = false
                    draft = state.systemPrompt
                }) { Text(stringResource(R.string.settings_prompt_cancel)) }
            }
        } else {
            Text(
                state.effectiveSystemPrompt,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { editing = true }) { Text(stringResource(R.string.settings_prompt_edit)) }
                if (!state.usingDefaultPrompt) {
                    TextButton(onClick = onReset) { Text(stringResource(R.string.settings_prompt_reset)) }
                }
            }
        }

        if (state.needsReload) {
            Text(
                stringResource(R.string.settings_prompt_needs_reload),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }    }
}

@Composable
private fun ReplySection(
    state: SettingsUiState,
    onMaxReplyTokens: (Int) -> Unit,
    onTemperature: (Int) -> Unit,
) {
    CardSection(
        title = R.string.settings_replies_title,
        explanation = R.string.settings_replies_explainer,
    ) {
        Text(stringResource(R.string.settings_length_title), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Settings.REPLY_LENGTHS.forEach { option ->
                FilterChip(
                    selected = state.maxReplyTokens == option,
                    onClick = { onMaxReplyTokens(option) },
                    label = { Text("$option") },
                )
            }
        }
        // A stored value no chip offers -- written by another build, or edited by
        // hand -- leaves every chip unselected, so the screen appears to have no
        // setting at all. Saying the number is the least it can do.
        if (state.maxReplyTokens !in Settings.REPLY_LENGTHS) {
            Text(
                stringResource(R.string.settings_length_custom, state.maxReplyTokens),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            stringResource(R.string.settings_length_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.settings_temperature_title), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Settings.TEMPERATURES.forEach { option ->
                FilterChip(
                    selected = state.temperatureTenths == option,
                    onClick = { onTemperature(option) },
                    label = { Text("${option / 10.0}") },
                )
            }
        }
        if (state.temperatureTenths !in Settings.TEMPERATURES) {
            Text(
                stringResource(R.string.settings_temperature_custom, "${state.temperature}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            when (state.temperatureTenths) {
                0 -> stringResource(R.string.settings_temperature_flat)
                in 1..5 -> stringResource(R.string.settings_temperature_close)
                in 6..10 -> stringResource(R.string.settings_temperature_balanced)
                else -> stringResource(R.string.settings_temperature_wild)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_replies_when),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.needsReload) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun CardSection(
    @StringRes title: Int,
    @StringRes explanation: Int,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(
    state: SettingsUiState,
    onChooseTheme: (AppTheme) -> Unit,
    onChooseFontScale: (Int) -> Unit,
) {
    CardSection(
        title = R.string.settings_appearance_title,
        explanation = R.string.settings_appearance_explainer,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTheme.entries.forEach { option ->
                FilterChip(
                    selected = state.theme == option,
                    onClick = { onChooseTheme(option) },
                    label = { Text(stringResource(option.labelRes())) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.settings_font_title),
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Settings.FONT_SCALES.forEach { option ->
                FilterChip(
                    selected = state.fontScalePercent == option,
                    onClick = { onChooseFontScale(option) },
                    label = { Text("$option%") },
                )
            }
        }
        if (state.fontScalePercent !in Settings.FONT_SCALES) {
            Text(
                stringResource(R.string.settings_font_custom, state.fontScalePercent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            stringResource(R.string.settings_font_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // Scaled with the real setting, so the choice is visible before it is
        // made rather than after. A chip labelled "130%" tells a person nothing
        // about whether their own text will still fit on a line.
        //
        // No multiplier here, and that is the fix rather than an omission. The
        // theme above this screen already applies the chosen scale to every style
        // in `Typography` -- `DemoAppTheme(fontScale = current.fontScale)` is what
        // the scale *does* -- so `MaterialTheme.typography.bodyMedium.fontSize` is
        // already the scaled size. Multiplying by `state.fontScale` a second time
        // made the sample 1.69x at the 130% setting: the one piece of text whose
        // whole purpose is to show the truth about the setting was the one piece
        // that lied about it, and it lied by exactly the amount being chosen.
        Text(
            stringResource(R.string.settings_font_sample),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * The app lock.
 *
 * Says what it does and, more importantly, what it does not do. A lock presented
 * as protection for a set of private notes, when the notes are readable by anyone
 * with the device backed up, is a reassurance the user did not earn.
 */
@Composable
private fun LockSection(
    enabled: Boolean,
    available: Boolean,
    onSet: (Boolean, Boolean) -> Unit,
) {
    CardSection(
        title = R.string.settings_lock_title,
        explanation = R.string.settings_lock_explainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = enabled,
                onCheckedChange = { onSet(it, available) },
                enabled = available || enabled,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(
                    when {
                        enabled -> R.string.settings_lock_on
                        !available -> R.string.settings_lock_unavailable
                        else -> R.string.settings_lock_off
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (!available && !enabled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** A labelled switch in its own card. */
@Composable
private fun SwitchRow(
    @androidx.annotation.StringRes title: Int,
    @androidx.annotation.StringRes explanation: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    @androidx.annotation.StringRes onLabel: Int,
    @androidx.annotation.StringRes offLabel: Int,
) {
    CardSection(title = title, explanation = explanation) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(if (checked) onLabel else offLabel),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}