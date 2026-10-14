package com.example.demoapp.ui.models

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.demoapp.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.demoapp.formatBytes
import com.example.demoapp.model.ImportProgress
import com.example.demoapp.model.ModelCatalogue

/**
 * Adding a model the user already has.
 *
 * The picker is opened for every MIME type rather than a specific one:
 * `.litertlm` has no registered type, and a filter that matches nothing would
 * make the file unselectable.
 */
@Composable
internal fun ImportSection(
    state: ModelManagerUiState,
    onPicked: (uri: String, fileName: String, sizeBytes: Long) -> Unit,
    onConfirmed: (displayName: String, licenseId: String, licenseSourceUrl: String) -> Unit,
    onCancelled: () -> Unit,
    onDismissReport: () -> Unit,
    onDismissError: () -> Unit,
) {
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // The size and name come from the document provider, which is the only
        // thing that knows them before the file is opened. A provider that will
        // not answer gives -1, which the import treats as "unknown" rather than
        // as an empty file.
        var name = "imported-model.litertlm"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        onPicked(uri.toString(), name, size)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        TextButton(
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = state.importStage == null,
        ) { Text(stringResource(R.string.import_add)) }

        Text(
            stringResource(R.string.import_add_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    state.importStage?.let { ImportProgressDialog(it, onCancelled) }

    state.importDraft?.let { draft ->
        LicenceForm(
            draft = draft,
            onConfirm = onConfirmed,
            onCancel = onCancelled,
        )
    }

    state.importReport?.let { report ->
        NoticeDialog(
            title = stringResource(R.string.import_done_title),
            body = stringResource(report.textRes, *report.args.toTypedArray()),
            detail = report.detail,
            onDismiss = onDismissReport,
        )
    }
    state.importError?.let { error ->
        NoticeDialog(
            title = stringResource(R.string.import_failed_title),
            body = stringResource(error.textRes, *error.args.toTypedArray()),
            detail = error.detail,
            onDismiss = onDismissError,
        )
    }
}

/**
 * The licence question, which is the part that makes an import acceptable.
 *
 * Every model in the catalogue is there because its licence was checked. A file
 * the user supplies has no such record, so the import cannot be silent about it:
 * the licence must be named, and where it was checked must be said. That is an
 * attestation rather than a verification, which is exactly what the catalogue's
 * own entries are too -- and both are recorded so that anyone can check them.
 */
@Composable
private fun LicenceForm(
    draft: ImportDraft,
    onConfirm: (String, String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(draft.uri) { mutableStateOf(draft.displayName) }
    var licence by remember(draft.uri) { mutableStateOf("") }
    var source by remember(draft.uri) { mutableStateOf("") }
    var licenceOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.import_form_title)) },
        text = {
            Column {
                Text(
                    draft.fileName + if (draft.sizeBytes > 0) " · ${formatBytes(draft.sizeBytes)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.import_form_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth()) {
                    Text(
                        if (licence.isBlank()) stringResource(R.string.import_form_licence) else licence,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { licenceOpen = true }) {
                        Text(stringResource(R.string.import_form_licence_button))
                    }
                    if (licenceOpen) {
                        androidx.compose.material3.DropdownMenu(
                            expanded = true,
                            onDismissRequest = { licenceOpen = false },
                        ) {
                            ModelCatalogue.ACCEPTED_LICENSES.sorted().forEach { option ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        licence = option
                                        licenceOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.import_form_licence_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.import_form_source)) },
                    placeholder = { Text(stringResource(R.string.import_form_source_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.import_form_source_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, licence, source) },
                enabled = name.isNotBlank() && licence.isNotBlank() && source.isNotBlank(),
) { Text(stringResource(R.string.import_form_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.import_form_cancel)) }
        },
    )
}

@Composable
private fun ImportProgressDialog(stage: ImportProgress, onCancel: () -> Unit) {
    val (label, done, total) = when (stage) {
        is ImportProgress.Copying ->
            Triple(stringResource(R.string.import_copying), stage.bytesDone, stage.bytesTotal)

        is ImportProgress.Hashing ->
            Triple(stringResource(R.string.import_verifying), stage.bytesTotal, stage.bytesTotal)
    }

    AlertDialog(
        // Not dismissible by tapping outside: the copy is mid-write, and a
        // dismissed dialog would leave the user with no way to see that it
        // finished.
        onDismissRequest = {},
        title = { Text("$label…") },
        text = {
            Column {
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (done.toDouble() / total).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.import_progress,
                            formatBytes(done),
                            formatBytes(total),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    // The provider would not say how large the file is, so there
                    // is no fraction to show. A bar that moves without meaning
                    // anything would be worse than none.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(formatBytes(done), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.import_stop)) }
        },
    )
}

@Composable
private fun NoticeDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    detail: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(body)
                // The exception's own text, kept out of the sentence. A dialog
                // that says only "could not add that file" leaves nothing to
                // report and nothing to search for.
                detail?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_ok)) }
        },
    )
}
