package com.example.demoapp.ui.models

import com.example.demoapp.ui.UiMessageText

import androidx.compose.material3.Surface
import com.example.demoapp.model.ModelLanguage
import com.example.demoapp.inference.labelRes
import com.example.demoapp.ui.UiMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.demoapp.R
import com.example.demoapp.formatBytes
import com.example.demoapp.durationOf
import com.example.demoapp.describe
import com.example.demoapp.formatPercent
import com.example.demoapp.formatRate
import com.example.demoapp.inference.describe
import com.example.demoapp.inference.explain
import com.example.demoapp.inference.InferenceBackend
import com.example.demoapp.inference.SamplingProfile
import com.example.demoapp.inference.hintRes
import com.example.demoapp.model.ModelCatalogue
import com.example.demoapp.model.ModelDescriptor
import com.example.demoapp.model.ModelMemory
import com.example.demoapp.theme.DemoAppTheme

@Composable
fun ModelManagerScreen(
    viewModel: ModelManagerViewModel,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModelManagerContent(
        state = state,
        // The question every model is asked. Read here because the content is
        // stateless, and required there because an omitted prompt is a benchmark that
        // measures how fast the model produces nothing, with numbers that come back
        // looking like measurements. It was defaulted to the empty string, so the
        // omission compiled and the screen was the only thing that noticed.
        benchmarkPrompt = stringResource(R.string.models_benchmark_prompt),
        onDownload = viewModel::download,
        onCancelDownload = viewModel::cancelDownload,
        onDismissMessage = viewModel::dismissMessage,
        onDeleteRequested = viewModel::requestDelete,
        onVerify = viewModel::verifyModel,
        onBenchmark = viewModel::runBenchmark,
        onSamplingChosen = viewModel::chooseSamplingProfile,
        onDeleteConfirmed = viewModel::confirmDelete,
        onDeleteCancelled = viewModel::cancelDelete,
        onDismissDeleteError = viewModel::dismissDeleteError,
        onDismissVerification = viewModel::dismissVerification,
        onDismissBenchmark = viewModel::dismissBenchmark,
        onOpenAbout = onOpenAbout,
        onImportPicked = viewModel::prepareImport,
        onImportConfirmed = viewModel::confirmImport,
        onImportCancelled = viewModel::cancelImport,
        onDismissImportReport = viewModel::dismissImportReport,
        onDismissImportError = viewModel::dismissImportError,
        modifier = modifier,
    )
}

/**
 * Stateless so it can be previewed and exercised in every state without a
 * device or a running download.
 */
@Composable
internal fun ModelManagerContent(
    state: ModelManagerUiState,
    onDownload: (ModelDescriptor) -> Unit,
    onCancelDownload: () -> Unit,
    onDismissMessage: () -> Unit,
    onDeleteRequested: (ModelDescriptor) -> Unit,
    modifier: Modifier = Modifier,
    onVerify: (ModelDescriptor) -> Unit = {},
    onBenchmark: (ModelDescriptor, String) -> Unit = { _, _ -> },
    /** The sampling profile the user picked for a model. */
    onSamplingChosen: (String, SamplingProfile) -> Unit = { _, _ -> },
    /** The prompt every model is asked, so the numbers compare. */
    benchmarkPrompt: String,
    onDeleteConfirmed: () -> Unit,
    onDeleteCancelled: () -> Unit,
    onDismissDeleteError: () -> Unit,
    onDismissVerification: () -> Unit = {},
    onDismissBenchmark: () -> Unit = {},
    onOpenAbout: () -> Unit,
    onImportPicked: (String, String, Long) -> Unit,
    onImportConfirmed: (String, String, String) -> Unit,
    onImportCancelled: () -> Unit,
    onDismissImportReport: () -> Unit,
    onDismissImportError: () -> Unit,
) {
    Column(modifier) {
        StorageSummary(state)

        ImportSection(
            state = state,
            onPicked = onImportPicked,
            onConfirmed = onImportConfirmed,
            onCancelled = onImportCancelled,
            onDismissReport = onDismissImportReport,
            onDismissError = onDismissImportError,
        )

        // A delete that did not happen. Shown above the list rather than on the
        // row, because the row's state is the fact and the message is about the
        // attempt.
        state.deleteError?.let { error ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UiMessageText(
                    message = error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissDeleteError) { Text(stringResource(R.string.settings_dismiss)) }
            }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            items(state.rows, key = { it.descriptor.id }) { row ->
                ModelCard(
                    row = row,
                    // Judged here rather than stored on the row: the verdict is a
                    // function of the model's size and the device's memory, and
                    // computing it from those two means it cannot disagree with
                    // either of them.
                    //
                    // Null on a device that would not report its memory, which
                    // draws no line at all rather than a line claiming the model
                    // does not fit.
                    verifying = state.verifying,
                    benchmarking = state.benchmarking,
                    modelMemory = state.deviceRamBytes
                        .takeIf { it > 0L }
                        ?.let { ModelMemory.judge(row.descriptor.sizeBytes, it) },
                    // A second download would compete for the same connection
                    // and the same free space, so the other rows wait. Nothing is
                    // offered at all until the device has been read: a Download
                    // button for a model that is already present is discarded
                    // silently by the coordinator, which looks like the app
                    // ignoring the tap.
                    enabled = !state.scanning &&
                        !state.rows.any { it.status is ModelStatus.Downloading || it.status is ModelStatus.Verifying },
                    onDownload = { onDownload(row.descriptor) },
                    onCancelDownload = onCancelDownload,
                    onDismissMessage = onDismissMessage,
                    onDeleteRequested = { onDeleteRequested(row.descriptor) },
                    onVerify = { onVerify(row.descriptor) },
                    onBenchmark = { onBenchmark(row.descriptor, benchmarkPrompt) },
                    onSamplingChosen = { profile -> onSamplingChosen(row.descriptor.id, profile) },
                )
            }

            item {
                // Last, after the models: it is here for a user who is thinking
                // about what the app holds, which is usually after looking at
                // what it has taken.
                TextButton(
                    onClick = onOpenAbout,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) { Text(stringResource(R.string.models_about)) }
            }
        }
    }

    state.pendingDelete?.let { pending ->
        DeleteConfirmation(
            pending = pending,
            onConfirm = onDeleteConfirmed,
            onCancel = onDeleteCancelled,
        )
    }

    // What the check found.
    //
    // Without this the check runs, finishes, and reports nothing -- which is
    // indistinguishable from a check that never ran, and trains the user to stop
    // pressing the button.
    state.verification?.let { result ->
        AlertDialog(
            onDismissRequest = onDismissVerification,
            title = { Text(stringResource(R.string.models_verify)) },
            text = {
                Text(
                    when (result.outcome) {
                        VerificationOutcome.INTACT ->
                            stringResource(R.string.models_verify_intact, result.displayName)
                        VerificationOutcome.DAMAGED ->
                            stringResource(R.string.models_verify_damaged, result.displayName)
                        VerificationOutcome.MISSING ->
                            stringResource(R.string.models_verify_missing, result.displayName)
                        VerificationOutcome.FAILED ->
                            stringResource(R.string.models_verify_failed, result.displayName)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissVerification) {
                    Text(stringResource(R.string.models_verify_dismiss))
                }
            },
        )
    }

    // What the measurement found.
    //
    // Shown once and dismissed, like the check result. A number that stayed on
    // screen would be read as a property of the model rather than a reading of one
    // run on one device.
    state.benchmark?.let { measured ->
        val t = measured.timing
        AlertDialog(
            onDismissRequest = onDismissBenchmark,
            title = { Text(stringResource(R.string.models_benchmark)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.benchmark_model, measured.displayName),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    listOfNotNull(
                        t.timeToFirstTokenMillis?.let {
                            stringResource(
                                R.string.benchmark_first_token,
                                durationOf(it / 1000).describe().orEmpty(),
                            )
                        },
                        t.decodeTokensPerSecond?.let {
                            stringResource(R.string.benchmark_decode, "%.1f".format(it))
                        },
                        t.prefillTokensPerSecond?.let {
                            stringResource(R.string.benchmark_prefill, "%.1f".format(it))
                        },
                        t.loadMillis?.takeIf { it > 0 }?.let {
                            stringResource(
                                R.string.benchmark_load,
                                durationOf(it / 1000).describe().orEmpty(),
                            )
                        },
                    ).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.benchmark_same_prompt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissBenchmark) {
                    Text(stringResource(R.string.models_verify_dismiss))
                }
            },
        )
    }
}

@Composable
private fun StorageSummary(state: ModelManagerUiState) {
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.models_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        // Shown because a 3.6 GB download is not a decision anyone can make
        // without knowing whether it fits. The failure is better prevented here
        // than discovered after an hour of downloading.
        //
        // Not shown until the device has been read. Before that the figures are
        // zero placeholders, and "Free: 0 B" is a claim about the device that the
        // app has no grounds for.
        Text(
            if (state.scanning) {
                stringResource(R.string.models_checking)
            } else {
                stringResource(
                    R.string.models_storage,
                    formatBytes(state.usedBytes),
                    formatBytes(state.freeBytes),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
private fun SamplingPicker(
    chosen: SamplingProfile,
    /** Whether this model is loaded, in which case the choice waits for a reload. */
    live: Boolean,
    onChosen: (SamplingProfile) -> Unit,
) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            stringResource(R.string.sampling_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SamplingProfile.entries.forEach { profile ->
                val selected = profile == chosen
                // The label says what the profile is called and the hint says what
                // it is for, and the hint is only on the selected one: three
                // explanations at once is a paragraph where the user needed a choice.
                TextButton(onClick = { onChosen(profile) }) {
                    Text(
                        stringResource(profile.labelRes()),
                        // The chosen one is marked by weight as well as colour, so the
                        // answer does not depend on telling two container tints apart.
                        style = if (selected) {
                            MaterialTheme.typography.labelLarge
                        } else {
                            MaterialTheme.typography.labelMedium
                        },
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        Text(
            stringResource(chosen.hintRes()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The runtime takes sampling when it creates a conversation, so a model
        // that is already loaded keeps what it was loaded with. Saying so is the
        // difference between a setting and a setting that appears to do nothing.
        //
        // The same sentence the settings screen already uses for the same reason,
        // which is also why it is not a second string: two wordings for one rule is
        // two things to keep in step.
        if (live) {
            Text(
                stringResource(R.string.settings_prompt_needs_reload),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ModelCard(
    row: ModelRow,
    /** Null when the device would not say how much memory it has. */
    modelMemory: ModelMemory.Verdict?,
    /** The model currently being re-read, if any. */
    verifying: String?,
    /** The model currently being measured, if any. */
    benchmarking: String?,
    enabled: Boolean,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismissMessage: () -> Unit,
    onDeleteRequested: () -> Unit,
    onVerify: () -> Unit,
    onBenchmark: () -> Unit,
    onSamplingChosen: (SamplingProfile) -> Unit,
) {
    val model = row.descriptor

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(model.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.models_subtitle,
                    stringResource(model.publisherRes),
                    "${model.parametersBillions}",
                    formatBytes(model.sizeBytes),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            // What it can be talked to in, before the description. The first
            // build of this app put an English-only model at the top of this list
            // with nothing to say so, and a Chinese question produced confident
            // English about something else -- which reads as a broken app rather
            // than as the wrong model for the job.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                model.languages.forEach { language ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (language == ModelLanguage.CHINESE) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Text(
                            stringResource(language.labelRes()),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            Text(
                stringResource(model.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Required by docs/PRINCIPLES.md: the licence is shown to the user,
            // not only recorded in the repository.
            Text(
                stringResource(R.string.models_licence, model.license.spdxId),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // What it will cost to run, and whether this device can.
            //
            // The file size is already on the row and is the wrong number to
            // decide on: a model needs about half again its size in memory, so the
            // 2.0 GB model in this list wants 2.9 GB to run. A user who picks by
            // file size picks one that does not fit and finds out by watching the
            // app disappear.
            modelMemory?.let { verdict ->
                val required = ModelMemory.requireBytes(verdict)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.models_memory, formatBytes(required)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (verdict is ModelMemory.Verdict.TooBig) {
                    Text(
                        stringResource(
                            R.string.models_memory_too_big,
                            formatBytes(required),
                            formatBytes(verdict.budgetBytes),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            StatusAndActions(row.status, row, enabled, onDownload, onCancelDownload, onDismissMessage, onDeleteRequested)

            // What can be done with a model that is already here.
            //
            // Both of these act on the installed file, so neither is offered until
            // there is one. Both take seconds -- one reads the whole file, one runs
            // the model -- which is why they are buttons rather than something done
            // on every launch.
            if (row.status is ModelStatus.Ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Only one at a time. A check and a measurement at once would
                    // compete for the same file and the same memory, and both would
                    // report the cost of the competition.
                    val busy = verifying != null || benchmarking != null
                    TextButton(onClick = onVerify, enabled = !busy) {
                        Text(
                            stringResource(
                                if (verifying == model.id) R.string.models_verifying
                                else R.string.models_verify,
                            ),
                        )
                    }
                    TextButton(onClick = onBenchmark, enabled = !busy) {
                        Text(
                            stringResource(
                                if (benchmarking == model.id) R.string.models_benchmarking
                                else R.string.models_benchmark,
                            ),
                        )
                    }
                }
                SamplingPicker(
                    chosen = row.samplingProfile,
                    live = row.samplingIsLive,
                    onChosen = onSamplingChosen,
                )
                // Why the second button exists at all. Which model is faster is a
                // thing people guess at from parameter counts, and the guess is
                // often wrong on a phone.
                Text(
                    stringResource(R.string.models_benchmark_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusAndActions(
    status: ModelStatus,
    row: ModelRow,
    enabled: Boolean,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismissMessage: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    when (status) {
        ModelStatus.NotDownloaded -> Button(onClick = onDownload, enabled = enabled) {
            Text(stringResource(R.string.models_download))
        }

        is ModelStatus.Downloading -> Column {
            // The percentage is a real number here rather than an animation:
            // the downloader reports bytes written, so the bar reflects actual
            // progress instead of an estimate of elapsed time.
            LinearProgressIndicator(
                progress = { status.fraction },
                modifier = Modifier.fillMaxWidth().semanticsPercent(formatPercent(status.fraction)),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    append(formatPercent(status.fraction))
                    append("  ·  ")
                    append(formatBytes(status.bytesDone))
                    append(" ")
                    append(stringResource(R.string.models_of, formatBytes(status.bytesTotal)))
                    formatRate(status.bytesPerSecond).takeIf { it.isNotEmpty() }?.let {
                        append("  ·  ")
                        append(it)
                    }
                    durationOf(status.etaSeconds).describe()?.let {
                        append("  ·  ")
                        append(stringResource(R.string.models_left, it))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCancelDownload) { Text(stringResource(R.string.models_cancel)) }
        }

        is ModelStatus.Verifying -> Column {
            // Indeterminate on purpose: the remaining time depends on the file
            // size and the device's hash speed, and a fake percentage would be
            // worse than an honest "working".
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.models_verifying),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        is ModelStatus.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.models_installed) + " · " + formatBytes(status.bytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDeleteRequested) { Text(stringResource(R.string.models_delete)) }
        }

        is ModelStatus.Failed -> Column {
            Text(
                stringResource(status.message.textRes, *status.message.args.toTypedArray()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            // The exception's own text, kept apart from the sentence above. It is
            // not translated and is not meant to read as Chinese: it is what
            // someone needs in order to find out what actually happened.
            status.message.detail?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                // Offered only when retrying could plausibly succeed. A Retry
                // button for a full disk would fail identically and teach the
                // user that the button does nothing.
                if (status.retryable) {
                    Button(onClick = onDownload, enabled = enabled) { Text(stringResource(R.string.models_retry)) }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.settings_dismiss)) }
            }
        }

        is ModelStatus.Cancelled -> Column {
            Text(
                // Not "resumes from there": the downloader sends a Range request
                // and resumes only when the server honours it. A server that
                // ignores the header, or answers 416, makes it start again from
                // zero, and the user should not be promised otherwise.
                stringResource(R.string.models_cancelled, formatBytes(status.bytesDone)) +
                    "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = onDownload, enabled = enabled) { Text(stringResource(R.string.models_resume)) }
                Spacer(Modifier.width(8.dp))
                // Offered here as well as on a finished model. Without it the
                // bytes of a stopped download can only be freed by letting the
                // download finish first, which is the opposite of what someone
                // who stopped it wants.
                TextButton(onClick = onDeleteRequested) { Text(stringResource(R.string.models_delete)) }
            }
        }
    }
}

@Composable
private fun DeleteConfirmation(
    pending: PendingDelete,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.models_delete_title, pending.descriptor.displayName)) },
        text = {
            Text(
                if (pending.resumable) {
                    // A partial file. Saying "this frees X" would be a claim about
                    // space the user is about to spend again.
                    stringResource(
                        R.string.models_delete_body_in_progress,
                        formatBytes(pending.bytesFreed),
                    )
                } else {
                    stringResource(
                        R.string.models_delete_body,
                        formatBytes(pending.bytesFreed),
                    )
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.models_delete_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.models_delete_cancel)) }
        },
    )
}

/**
 * Adds a spoken description to a progress bar without deleting its value.
 *
 * `clearAndSetSemantics` would remove the node's own semantics and its
 * descendants', including the `progressBarRangeInfo` that the indicator sets --
 * so the bar would stop being a progress bar to a screen reader and become a
 * static string. Adding to the existing semantics keeps both: the assistive
 * layer can read the value, and the description gives it in the same words the
 * screen shows.
 */
private fun Modifier.semanticsPercent(percent: String): Modifier =
    semantics { contentDescription = percent }

// ------------------------------------------------------------- previews ---

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun PreviewMixedStates() {
    val models = ModelCatalogue.models
    DemoAppTheme {
        ModelManagerContent(
            state = ModelManagerUiState(
                rows = listOf(
                    ModelRow(models[0], ModelStatus.Ready(373_719_040L)),
                    ModelRow(
                        models[1],
                        ModelStatus.Downloading(
                            bytesDone = 214_000_000L,
                            bytesTotal = 614_230_168L,
                            bytesPerSecond = 2_400_000L,
                            etaSeconds = 167L,
                        ),
                    ),
                    ModelRow(models[2], ModelStatus.NotDownloaded),
                    ModelRow(models[3], ModelStatus.Verifying(2_588_147_712L)),
                    ModelRow(
                        models[4],
                        ModelStatus.Failed(
                            UiMessage(
                                R.string.err_download_no_space,
                                "3.6 GB",
                                "1.1 GB",
                            ),
                            retryable = false,
                        ),
                    ),
                ),
                freeBytes = 1_200_000_000L,
                usedBytes = 373_719_040L,
            ),
            benchmarkPrompt = stringResource(R.string.models_benchmark_prompt),
            onDownload = {},
            onCancelDownload = {},
            onDismissMessage = {},
            onDeleteRequested = {},
            onDeleteConfirmed = {},
            onDeleteCancelled = {},
            onDismissDeleteError = {},
            onOpenAbout = {},
            onImportPicked = { _, _, _ -> },
            onImportConfirmed = { _, _, _ -> },
            onImportCancelled = {},
            onDismissImportReport = {},
            onDismissImportError = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewNothingDownloaded() {
    DemoAppTheme {
        ModelManagerContent(
            state = ModelManagerUiState(
                rows = ModelCatalogue.models.take(2).map { ModelRow(it, ModelStatus.NotDownloaded) },
                freeBytes = 48_000_000_000L,
                usedBytes = 0L,
            ),
            benchmarkPrompt = stringResource(R.string.models_benchmark_prompt),
            onDownload = {},
            onCancelDownload = {},
            onDismissMessage = {},
            onDeleteRequested = {},
            onDeleteConfirmed = {},
            onDeleteCancelled = {},
            onDismissDeleteError = {},
            onOpenAbout = {},
            onImportPicked = { _, _, _ -> },
            onImportConfirmed = { _, _, _ -> },
            onImportCancelled = {},
            onDismissImportReport = {},
            onDismissImportError = {},
        )
    }
}
