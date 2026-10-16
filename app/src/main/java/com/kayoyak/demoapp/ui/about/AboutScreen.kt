package com.kayoyak.demoapp.ui.about

import com.kayoyak.demoapp.ui.UiMessage
import com.kayoyak.demoapp.ui.UiMessageText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalContext
import com.kayoyak.demoapp.diagnostics.CrashLog
import com.kayoyak.demoapp.diagnostics.DiagnosticsReport
import com.kayoyak.demoapp.diagnostics.AppSignature
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kayoyak.demoapp.R
import com.kayoyak.demoapp.formatBytes

/**
 * What this is, what it promises, and how to check.
 *
 * The three principles are the project's stated constraints, and they are shown
 * to the user rather than kept in a document on the internet: an app that claims
 * your words never leave the device should say so where you can read it.
 *
 * Every word is a string resource. This screen is mostly prose, and prose written
 * as concatenated Kotlin literals cannot be proof-read -- a sentence is split
 * across lines, the halves drift apart in review, and a translation would have to
 * find all of them.
 */
@Composable
fun AboutScreen(
    viewModel: AboutViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val subject = stringResource(R.string.about_diagnostics_subject, stringResource(R.string.app_name))
    val labels = diagnosticsLabels()
    // Read before the handler, not inside it: gathering these reads string
    // resources, and the click handler is not a composable.
    val facts = diagnosticsFacts(context, state)
    // Read here for the same reason: the content is a pure function of what it
    // is handed, and a platform call inside it would not be.
    val signatureVerdict = remember { AppSignature.verdict(context) }
    val signatures = remember { AppSignature.fingerprints(context) }

    AboutContent(
        state = state,
        onEraseRequested = viewModel::requestErase,
        signatures = signatures,
        signatureVerdict = signatureVerdict,
        onEraseConfirmed = viewModel::confirmErase,
        onEraseCancelled = viewModel::cancelErase,
        onDismissReport = viewModel::dismissReport,
        onDismissError = viewModel::dismissError,
        onWipeFreeSpace = {
            viewModel.wipeFreeSpace(context.filesDir, 2L * 1024 * 1024 * 1024)
        },
        wiping = state.wiping,
        onDismissWipe = viewModel::dismissWipe,
        onShareDiagnostics = {
            // Assembled here because this is the layer with the Context. Nothing is
            // written to storage and nothing is sent: the text goes into a share the
            // user started.
            val report = DiagnosticsReport.build(
                facts = facts,
                labels = labels,
                crash = CrashLog.read(java.io.File(context.filesDir, "diagnostics")),
            )
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                putExtra(android.content.Intent.EXTRA_TEXT, report)
            }
            context.startActivity(
                android.content.Intent.createChooser(share, subject)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        modifier = modifier,
    )
}

@Composable
internal fun AboutContent(
    state: AboutUiState,
    onEraseRequested: () -> Unit,
    /** The fingerprints this copy is actually signed with. */
    signatures: Set<String>,
    /**
     * What this copy's signature says, or [AppSignature.Verdict.UNKNOWN].
     *
     * The verdict rather than a flag, because three of the four answers are not
     * "wrong" and each has its own sentence: a matching build, a debug build that was
     * never signed with the release key, and a device that would not report.
     */
    signatureVerdict: AppSignature.Verdict,
    onEraseConfirmed: () -> Unit,
    onShareDiagnostics: () -> Unit,
    /** Writes over the app's free space. Slow, and the cost is on the screen. */
    onWipeFreeSpace: () -> Unit,
    wiping: Boolean,
    /** The figure the wipe reported has been read. */
    onDismissWipe: () -> Unit,
    onEraseCancelled: () -> Unit,
    onDismissReport: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.about_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        Principle(R.string.about_wont_do_title, R.string.about_wont_do_body)
        Principle(R.string.about_keeps_title, R.string.about_keeps_body)
        Principle(R.string.about_built_from_title, R.string.about_built_from_body)

        HorizontalDivider()

        // What this copy is signed with, and whether it is the project's copy at all.
        //
        // Here rather than behind a menu: the one person who needs this is someone
        // who has downloaded the app from somewhere other than the project, and a
        // fact they have to go looking for is one they will not find.
        //
        // The fingerprints are selectable and in the same monospace the code blocks
        // use. The app compares them itself; the text stays selectable because the
        // comparison that matters is one a person can also check against a published
        // fingerprint, without trusting this screen's verdict.
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.about_signature_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.about_signature_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))

            if (signatures.isEmpty()) {
                Text(
                    stringResource(R.string.about_signature_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SelectionContainer {
                    Column {
                        signatures.forEach { fingerprint ->
                            Text(
                                fingerprint,
                                style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            // Four states rather than a warning and a fallback, because three of them
            // are not "wrong". A matching build has earned the sentence; a debug
            // build was never signed with the release key and saying so is the whole
            // point of separating it; a device that reports no signature is not a
            // finding.
            Text(
                stringResource(
                    when (signatureVerdict) {
                        is AppSignature.Verdict.OTHER -> R.string.about_signature_other
                        AppSignature.Verdict.DEVELOPMENT -> R.string.about_signature_development
                        AppSignature.Verdict.PUBLISHED -> R.string.about_signature_published
                        AppSignature.Verdict.UNKNOWN -> R.string.about_signature_unknown
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        // The disclaimer is here, in the app, and not only in the repository. The
        // app runs with no network: a document on a website is not reachable from
        // the device it is about, and the person who needs to read it is the one
        // holding the phone.
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.about_disclaimer_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.about_disclaimer_warranty),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.about_disclaimer_authorship),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.about_disclaimer_trademark),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.about_disclaimer_more),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.about_storage_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            if (state.scanning) {
                Text(
                    stringResource(R.string.about_reading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Held(
                    stringResource(R.string.about_storage_conversations),
                    stringResource(
                        R.string.about_storage_value,
                        state.conversationCount,
                        formatBytes(state.chatBytes),
                    ),
                )
                Held(
                    stringResource(R.string.about_storage_models),
                    formatBytes(state.modelBytes),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.about_storage_total, formatBytes(state.totalBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.eraseReport?.let { report ->
            MessageBanner(report, onDismiss = onDismissReport)
        }
        state.error?.let { error ->
            MessageBanner(error, onDismiss = onDismissError)
        }

        HorizontalDivider()

        // The one action here that takes real time and has a real cost.
        //
        // Not offered after a delete, and not offered by default. What it defends
        // against is someone taking a physical image of the device, and whether
        // that is in the user's threat model is something only they know. The cost
        // is written above the button rather than discovered by pressing it.
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.about_wipe_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.about_wipe_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.about_wipe_cost),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onWipeFreeSpace,
                enabled = !wiping,
            ) {
                Text(
                    stringResource(
                        if (wiping) R.string.about_wipe_running else R.string.about_wipe_action,
                    ),
                )
            }
            state.wipedBytes?.let { written ->
                // Dismissible, because the figure is the answer to the button above
                // it and it should stay only as long as the person who pressed it
                // wants it there. It used to stay until the next wipe or a restart:
                // the value could not be cleared at all, and `dismissWipe` had no
                // caller anywhere in the app.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.about_wipe_done, formatBytes(written)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissWipe) {
                        Text(stringResource(R.string.settings_dismiss))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.about_wipe_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        Column(Modifier.padding(16.dp)) {
            // Diagnostics, above the destructive action.
            //
            // The app has no telemetry, so this is the only way a real failure ever
            // reaches anyone who can fix it. It sits here because someone who has
            // just hit a bug goes looking at the screen that says what the app does.
            TextButton(onClick = onShareDiagnostics) {
                Text(stringResource(R.string.about_diagnostics))
            }
            Text(
                stringResource(R.string.about_diagnostics_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            TextButton(
                onClick = onEraseRequested,
                enabled = !state.scanning && state.hasAnything,
            ) { Text(stringResource(R.string.about_erase)) }

            Text(
                stringResource(
                    if (state.hasAnything) R.string.about_erase_hint else R.string.about_erase_nothing,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (state.confirmingErase) {
        AlertDialog(
            onDismissRequest = onEraseCancelled,
            title = { Text(stringResource(R.string.about_erase_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.about_erase_body,
                        state.conversationCount,
                        formatBytes(state.modelBytes),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = onEraseConfirmed) {
                    Text(stringResource(R.string.about_erase_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onEraseCancelled) {
                    Text(stringResource(R.string.about_erase_cancel))
                }
            },
        )
    }
}

@Composable
private fun MessageBanner(message: UiMessage, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The shared renderer rather than a local arrangement, so a message
            // looks the same wherever it is shown and the two parts -- sentence and
            // technical detail -- are always kept apart the same way.
            UiMessageText(message = message, modifier = Modifier.weight(1f).padding(12.dp))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dismiss)) }
        }
    }
}

@Composable
private fun Principle(title: Int, body: Int) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Held(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Banner(message: String, isError: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dismiss)) }
    }
}

/**
 * The report's labels, read here because this is the composable that has them.
 *
 * Passed to [DiagnosticsReport.build] rather than written there: the report is a
 * data-layer object and the app's language lives in the string resources.
 */
@Composable
fun diagnosticsLabels(): DiagnosticsReport.Labels = DiagnosticsReport.Labels(
    heading = stringResource(R.string.about_diagnostics_heading),
    app = stringResource(R.string.about_diagnostics_app),
    android = stringResource(R.string.about_diagnostics_android),
    device = stringResource(R.string.about_diagnostics_device),
    soc = stringResource(R.string.about_diagnostics_soc),
    memory = stringResource(R.string.about_diagnostics_memory),
    heap = stringResource(R.string.about_diagnostics_heap),
    model = stringResource(R.string.about_diagnostics_model),
    backend = stringResource(R.string.about_diagnostics_backend),
    context = stringResource(R.string.about_diagnostics_context),
    conversations = stringResource(R.string.about_diagnostics_conversations),
    models = stringResource(R.string.about_diagnostics_models),
    lastError = stringResource(R.string.about_diagnostics_last_error),
    lastCrash = stringResource(R.string.about_diagnostics_last_crash),
    noError = stringResource(R.string.about_diagnostics_no_error),
    none = stringResource(R.string.about_diagnostics_none),
    footer = stringResource(R.string.about_diagnostics_footer),
)

/**
 * What the device can say about itself, gathered at the moment of the report.
 *
 * Read here rather than from live objects inside the report: a snapshot describes
 * one moment, and reading the engine while building it would describe a different
 * one from the failure.
 */
@Composable
fun diagnosticsFacts(
    context: android.content.Context,
    state: AboutUiState,
): DiagnosticsReport.Facts {
    val runtime = Runtime.getRuntime()
    val memory = android.app.ActivityManager.MemoryInfo()
    (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
        ?.getMemoryInfo(memory)

    return DiagnosticsReport.Facts(
        appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty(),
        buildType = if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            stringResource(R.string.about_diagnostics_build_debug)
        } else {
            stringResource(R.string.about_diagnostics_build_release)
        },
        androidRelease = android.os.Build.VERSION.RELEASE.orEmpty(),
        sdkInt = android.os.Build.VERSION.SDK_INT,
        manufacturer = android.os.Build.MANUFACTURER.orEmpty(),
        model = android.os.Build.MODEL.orEmpty(),
        abis = android.os.Build.SUPPORTED_ABIS.joinToString(","),
        // The platform's own sentinel when it has nothing suitable to say, so it is
        // passed through and filtered by the report rather than judged here.
        //
        // The guard is still required: the field arrived in API 31, and minSdk here
        // is 26, so on older releases it is absent rather than empty.
        socModel = if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.os.Build.SOC_MODEL
        } else {
            null
        },
        socManufacturer = if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.os.Build.SOC_MANUFACTURER
        } else {
            null
        },
        availableMemoryMb = memory.availMem / (1024 * 1024),
        totalMemoryMb = memory.totalMem / (1024 * 1024),
        usedHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
        maxHeapMb = runtime.maxMemory() / (1024 * 1024),
        conversationCount = state.conversationCount,
        // These six were omitted, and nothing said so: every one had a default in
        // `Facts`, so the report printed "none" for the model and the backend and
        // zero for the installed count on every device, including one with a model
        // resident and three on disk. The report is the only channel this app has
        // (it has no telemetry by design), and the questions it exists to answer
        // are the ones these fields address. None of them has a default now, which
        // is why `contextTokens` had to be written down here as well -- see below.
        modelId = state.runningModelId,
        backend = state.runningBackend,
        // Null, and named, rather than left out: this screen holds no conversation,
        // so it has no occupancy to report, and `Facts` no longer defaults it away.
        // `AboutUiState` records the same decision from the other side.
        contextTokens = null,
        contextWindow = state.contextWindow,
        installedModelCount = state.installedModelCount,
        lastError = state.error?.let { message ->
            stringResource(message.textRes, *message.args.toTypedArray())
        },
    )
}