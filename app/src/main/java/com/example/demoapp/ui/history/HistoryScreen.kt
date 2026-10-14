package com.example.demoapp.ui.history

import kotlinx.coroutines.launch

import androidx.compose.runtime.rememberCoroutineScope

import androidx.activity.result.contract.ActivityResultContracts

import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.compose.foundation.layout.ExperimentalLayoutApi

import androidx.compose.foundation.layout.FlowRow

import com.example.demoapp.formatBytes
import com.example.demoapp.data.chat.ConversationExport
import com.example.demoapp.data.chat.MarkdownLabels

import com.example.demoapp.ui.UiMessage
import com.example.demoapp.ui.UiMessageText

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import com.example.demoapp.R
import com.example.demoapp.WhenAgo
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.demoapp.data.chat.Conversation
import com.example.demoapp.describe
import com.example.demoapp.whenAgo

/**
 * The conversations on this device.
 *
 * History is written to disk as the user chats, so leaving it reachable only by
 * continuing the conversation in front of them hides work that is already saved.
 */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Dismisses the notice that some stored conversations could not be read.
     *
     * Passed rather than done here so a Preview can render the screen without a
     * view model, and so the gate that checks screen wiring can see it.
     */
    onDismissRestored: () -> Unit = {},
    onDismissUnreadable: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val chooserTitle = stringResource(R.string.history_share_title)
    val markdownSubject = stringResource(R.string.history_export_markdown_subject)
    val backupSubject = stringResource(R.string.backup_export_subject)
    val nothingToExport = stringResource(R.string.backup_nothing_to_export)
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The words one conversation's Markdown export is written with.
    //
    // Resolved during composition rather than inside the export callback with
    // `context.getString`. `stringResource` records a read of the configuration, so
    // a language change recomposes this and the lambda closes over the new wording;
    // a `getString` call inside a callback records nothing, and an export started
    // after a language change would be written in the old one.
    //
    // `exportedAt` takes the stamp as an argument, so what is resolved here is the
    // pattern and the substitution happens in the lambda. A parameterised
    // `stringResource` call cannot go there: the lambda is built while this
    // composable runs, but it is not itself a composable context, and the compiler
    // refuses the call outright.
    val exportedAtPattern = stringResource(R.string.export_exported_at)
    val exportLabels = ConversationExport.ExportLabels(
        you = stringResource(R.string.export_you),
        model = stringResource(R.string.export_model),
        stoppedHere = stringResource(R.string.export_stopped),
        footer = stringResource(R.string.export_footer),
        exportedAt = { stamp -> String.format(exportedAtPattern, stamp) },
    )

    // The second export's words. The Markdown export labels the blockquote under
    // each reply -- the model, the token count, the rate -- and those were English
    // literals inside `toMarkdown` until the copy gate learned to look inside a
    // format string.
    val ratePattern = stringResource(R.string.export_rate)
    val markdownLabels = MarkdownLabels(
        model = stringResource(R.string.export_model_note),
        tokens = stringResource(R.string.export_tokens_note),
        rate = { perSecond -> String.format(ratePattern, perSecond) },
        clock = ::clockText,
    )

    // The system's own file picker, so the user chooses the file and the app is
    // never handed a path it did not ask about. There is no permission involved:
    // what the picker returns is a single document the user pointed at.
    val pickBackup =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val text =
                runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().decodeToString()
                        }
                    }
                    .getOrNull()
            if (text == null) {
                viewModel.reportUnreadableBackup()
            } else {
                viewModel.restore(text)
            }
        }

    HistoryContent(
        state = state,
        onOpen = onOpen,
        onDeleteRequested = viewModel::requestDelete,
        onDeleteConfirmed = viewModel::confirmDelete,
        onDeleteCancelled = viewModel::cancelDelete,
        onDismissError = viewModel::dismissError,
        onRenameRequested = viewModel::requestRename,
        onRenameConfirmed = viewModel::confirmRename,
        onRenameCancelled = viewModel::cancelRename,
        onTogglePin = viewModel::togglePin,
        onToggleArchive = viewModel::toggleArchive,
        onExportMarkdown = {
            scope.launch {
                val text = viewModel.buildMarkdown(markdownLabels)
                if (text == null) {
                    viewModel.reportNothingToExport()
                } else {
                    shareText(context, markdownSubject, text)
                }
            }
        },
        onDismissRestored = onDismissRestored,
        onDismissUnreadable = onDismissUnreadable,
        onSearchQueryChanged = viewModel::setSearchQuery,
        onExportAll = {
            scope.launch {
                val text = viewModel.buildBackup()
                if (text == null) {
                    viewModel.reportNothingToExport()
                } else {
                    shareText(context, backupSubject, text)
                }
            }
        },
        onImportRequested = { pickBackup.launch(arrayOf("application/json", "text/plain", "*/*")) },
        onExport = { id ->
            viewModel.prepareExport(id, exportLabels) { title, fileName, text ->
                shareConversation(context, title, fileName, chooserTitle, text)
            }
        },
        modifier = modifier,
    )
}

/** Stateless so every state can be previewed without a device. */
@Composable
internal fun HistoryContent(
    state: HistoryUiState,
    onOpen: (String) -> Unit,
    onDeleteRequested: (String) -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteCancelled: () -> Unit,
    onDismissError: () -> Unit,
    onRenameRequested: (String) -> Unit,
    onRenameConfirmed: (String) -> Unit,
    onRenameCancelled: () -> Unit,
    onTogglePin: (String) -> Unit,
    onToggleArchive: (String) -> Unit,
    onExportAll: () -> Unit,
    modifier: Modifier = Modifier,
    /** Writes the conversations out as Markdown rather than as a backup file. */
    onExportMarkdown: () -> Unit,
    onDismissRestored: () -> Unit,
    /** Hides the notice that some stored conversations could not be read. */
    onDismissUnreadable: () -> Unit = {},
    onImportRequested: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onExport: (String) -> Unit,
) {
    Column(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    state.scanning -> stringResource(R.string.history_reading)
                    state.rows.isEmpty() -> stringResource(R.string.history_empty_body)
                    else -> stringResource(R.string.history_subtitle, state.rows.size)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Backup sits here rather than behind a menu, and says what it is for.
        //
        // The app promises not to sync anything, and "not synced" reads as "not
        // movable" unless the screen offers a way out. A user who has not thought
        // about it will not go looking for a backup feature, and the moment they
        // need it is the moment the old phone is already gone.
        if (!state.scanning) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.backup_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow {
                    TextButton(onClick = onExportMarkdown) {
                Text(
                    stringResource(R.string.history_export_markdown),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            TextButton(onClick = onExportAll) {
                        Text(stringResource(R.string.backup_export_all))
                    }
                    TextButton(onClick = onImportRequested) {
                        Text(stringResource(R.string.backup_import))
                    }
                }
            }
        }

        state.restored?.let { report ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UiMessageText(
                    message = report,
                    modifier = Modifier.weight(1f),
                    errorColor = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = onDismissRestored) { Text(stringResource(R.string.settings_dismiss)) }
            }
        }

        // Above the error line, because it is not one: nothing failed and nothing
        // can be actioned. It is here because the alternative -- a conversation
        // simply absent from the list -- reads as one that was deleted, and the
        // reader did not delete it.
        if (state.showsUnreadable) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UiMessageText(
                    message = UiMessage(R.string.history_unreadable, state.unreadable),
                    modifier = Modifier.weight(1f),
                    errorColor = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = onDismissUnreadable) {
                    Text(stringResource(R.string.settings_dismiss))
                }
            }
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

        // Search sits above the list rather than behind an icon: a history of any
        // size is one where finding a conversation is the common act, and a
        // control you have to discover first is one most people never use.
        if (!state.scanning && state.rows.size > 1) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChanged,
                singleLine = true,
                label = { Text(stringResource(R.string.history_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Nothing to offer until the device has been read: an empty list here is
        // not "no conversations", it is "not known yet".
        if (state.scanning) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.history_reading), style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        // Computed once per change rather than on every read. `visibleRows` scans
        // the title and every message of every conversation, and the screen reads
        // it more than once -- once for "did the search find anything" and once
        // for the list -- so leaving it as a computed property re-ran the whole
        // scan twice per keystroke, over exactly the set the screen exists to
        // search.
        val visibleRows = remember(state.rows, state.searchQuery) { state.visibleRows }
        val foundNothing = remember(state.rows, state.searchQuery, visibleRows) {
            state.searchQuery.isNotBlank() && visibleRows.isEmpty() && state.rows.isNotEmpty()
        }

        if (foundNothing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.history_nothing_found), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { onSearchQueryChanged("") }) {
                        Text(stringResource(R.string.history_clear_search))
                    }
                }
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // Grouped by when they were last touched, but only when the list is the
            // list. While a search is running the results are what matched, in
            // relevance order, and filing them under "Yesterday" and "Past 7 days"
            // is filing an answer by a question the user did not ask.
            if (state.searchQuery.isBlank()) {
                ConversationDates.group(visibleRows, { it.conversation.updatedAt })
                    .forEach { (bucket, rows) ->
                        item(key = "heading-${bucket.name}") {
                            Text(
                                stringResource(bucket.label()),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 16.dp,
                                    bottom = 4.dp,
                                ),
                            )
                        }
                        items(rows, key = { it.conversation.id }) { row ->
                            HistoryCard(
                                row = row,
                                isOpen = row.conversation.id == state.openConversationId,
                                onOpen = { onOpen(row.conversation.id) },
                                onDeleteRequested = { onDeleteRequested(row.conversation.id) },
                                onRenameRequested = { onRenameRequested(row.conversation.id) },
                                onTogglePin = { onTogglePin(row.conversation.id) },
                                onToggleArchive = { onToggleArchive(row.conversation.id) },
                                onExport = { onExport(row.conversation.id) },
                            )
                        }
                    }
            } else {
                items(visibleRows, key = { it.conversation.id }) { row ->
                    HistoryCard(
                        row = row,
                        isOpen = row.conversation.id == state.openConversationId,
                        onOpen = { onOpen(row.conversation.id) },
                        onDeleteRequested = { onDeleteRequested(row.conversation.id) },
                        onRenameRequested = { onRenameRequested(row.conversation.id) },
                        onTogglePin = { onTogglePin(row.conversation.id) },
                        onToggleArchive = { onToggleArchive(row.conversation.id) },
                        onExport = { onExport(row.conversation.id) },
                    )
                }
            }
        }
    }

    val confirming = state.rows.firstOrNull { it.confirmingDelete }?.conversation
    confirming?.let {
        AlertDialog(
            onDismissRequest = onDeleteCancelled,
            title = { Text(stringResource(R.string.history_delete_title, it.title)) },
            text = {
                Text(
                    stringResource(R.string.history_delete_body)
                )
            },
            confirmButton = {
                    TextButton(onClick = onDeleteConfirmed) {
                        Text(stringResource(R.string.history_delete))
                    }
                },
            dismissButton = {
                    TextButton(onClick = onDeleteCancelled) {
                        Text(stringResource(R.string.about_erase_cancel))
                    }
                },
        )
    }

    state.renaming?.let { renaming ->
        RenameDialog(
            renaming = renaming,
            onConfirm = onRenameConfirmed,
            onCancel = onRenameCancelled,
        )
    }
}

/**
 * Editing a conversation's title.
 *
 * The title is derived from the first thing the user said, which is a reasonable
 * guess and a poor permanent name: "hi" is not what anyone will look for in a
 * list a month later.
 */
@Composable
private fun RenameDialog(
    renaming: Renaming,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // Seeded from the current title, and re-seeded if a different conversation is
    // opened for renaming while this is on screen.
    var draft by remember(renaming.id) { mutableStateOf(renaming.currentTitle) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.history_rename_title)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                label = { Text(stringResource(R.string.history_rename_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draft) },
                enabled = draft.isNotBlank(),
            ) { Text(stringResource(R.string.history_rename_save)) }
        },
        dismissButton = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.history_rename_cancel)) }
                },
    )
}

@Composable
private fun HistoryCard(
    row: HistoryRow,
    isOpen: Boolean,
    onOpen: () -> Unit,
    onDeleteRequested: () -> Unit,
    onRenameRequested: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
    onExport: () -> Unit,
) {
    val conversation = row.conversation

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp).clickable(onClick = onOpen)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.pinnedAt != null) {
                    // The row is already first in the list, so this is not what
                    // makes the pin work -- it is what makes it legible. Without
                    // it, someone looking at a row cannot tell a pinned
                    // conversation from one that merely happens to be recent.
                    Text(
                        stringResource(R.string.history_pinned_marker),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (isOpen) {
                    Text(
                        stringResource(R.string.history_in_use),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (conversation.archivedAt != null) {
                    // The row is already last in the list, so this is not what
                    // makes the archive work -- it is what makes it legible, for
                    // the same reason the pin marker exists. An archived
                    // conversation sits among old ones and otherwise looks like
                    // one that simply stopped being used.
                    Text(
                        stringResource(R.string.history_archived_marker),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.history_message_count, conversation.messages.size) +
                    " · " + whenAgo(conversation.updatedAt).describe() +
                    // Only when known. A row reading "0 B" is a claim, and one
                    // that invites deleting the wrong conversation.
                    (row.bytes?.let { " · " + formatBytes(it) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The last thing said, so two conversations with similar titles are
            // still tellable apart without opening both.
            conversation.messages.lastOrNull()?.let { last ->
                Spacer(Modifier.height(4.dp))
                Text(
                    last.text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            // Six actions no longer fit one line on a phone, and at the largest
            // text size they stop fitting well before that. FlowRow wraps them
            // rather than pushing the last one off the edge -- and the last one is
            // Delete, which is the one a person goes looking for on purpose.
            //
            // The count was five for as long as the archive button was missing:
            // the comment described the row it was written against, and the row
            // never gained the action the rest of the chain was already wired for.
            FlowRow {
                TextButton(onClick = onOpen) { Text(stringResource(R.string.history_open)) }
                TextButton(onClick = onExport) { Text(stringResource(R.string.history_share)) }
                TextButton(onClick = onRenameRequested) { Text(stringResource(R.string.history_rename)) }
                // The label says what the tap will do, not what the row currently
                // is. A button reading 「置顶」 on an already-pinned conversation
                // looks like it has been done, so nobody presses it.
                TextButton(onClick = onTogglePin) {
                    Text(
                        stringResource(
                            if (conversation.pinnedAt == null) R.string.history_pin
                            else R.string.history_unpin,
                        ),
                    )
                }
                // The same rule as the pin above: the label says what the tap will
                // do, and the marker beside the title says what the row currently
                // is. Archived rows sort last rather than disappearing, so both
                // halves are needed -- without the button there is no way to
                // archive, and without the marker no way to tell it happened.
                TextButton(onClick = onToggleArchive) {
                    Text(
                        stringResource(
                            if (conversation.archivedAt == null) R.string.history_archive
                            else R.string.history_unarchive,
                        ),
                    )
                }
                TextButton(onClick = onDeleteRequested) { Text(stringResource(R.string.history_delete)) }
            }
        }
    }
}

// ------------------------------------------------------------- previews ---

private fun sample(id: String, title: String, messages: Int, minutesAgo: Long): Conversation {
    val now = 1_760_000_000_000L - minutesAgo * 60_000L
    return Conversation(
        id = id,
        title = title,
        modelId = "smollm2-360m",
        createdAt = now,
        updatedAt = now,
        messages = (0 until messages).map {
            com.example.demoapp.data.chat.ChatMessage(
                id = "$id-$it",
                role = if (it % 2 == 0) com.example.demoapp.data.chat.MessageRole.USER
                else com.example.demoapp.data.chat.MessageRole.ASSISTANT,
                text = if (it % 2 == 0) "关于那件事的一个问题" else "关于那件事的一个回答",
                createdAt = now + it * 1000L,
            )
        },
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PreviewHistory() {
    com.example.demoapp.theme.DemoAppTheme {
        HistoryContent(
            state = HistoryUiState(
                rows = listOf(
                    HistoryRow(sample("a", "三原色", 4, 3)),
                    HistoryRow(sample("b", "用 Kotlin 给列表排序", 12, 90)),
                    HistoryRow(sample("c", "新对话", 0, 600)),
                ),
                scanned = true,
                openConversationId = "b",
            ),
            onOpen = {},
            onDeleteRequested = {},
            onDeleteConfirmed = {},
            onDeleteCancelled = {},
            onDismissError = {},
            onRenameRequested = {},
            onRenameConfirmed = {},
            onRenameCancelled = {},
            onTogglePin = {},
            onToggleArchive = {},
            onExportAll = {},
            onExportMarkdown = {},
            onDismissRestored = {},
            onImportRequested = {},
            onSearchQueryChanged = {},
            onExport = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 400)
@Composable
private fun PreviewHistoryScanning() {
    com.example.demoapp.theme.DemoAppTheme {
        HistoryContent(
            state = HistoryUiState(),
            onOpen = {},
            onDeleteRequested = {},
            onDeleteConfirmed = {},
            onDeleteCancelled = {},
            onDismissError = {},
            onRenameRequested = {},
            onRenameConfirmed = {},
            onRenameCancelled = {},
            onTogglePin = {},
            onToggleArchive = {},
            onExportAll = {},
            onExportMarkdown = {},
            onDismissRestored = {},
            onImportRequested = {},
            onSearchQueryChanged = {},
            onExport = {},
        )
    }
}

/**
 * Hands a conversation to whatever the user picks.
 *
 * The text goes in the intent itself rather than in a file: the app has no
 * business leaving a copy of a private conversation in a shared directory, and a
 * temporary file that outlives a cancelled share is exactly the kind of thing the
 * privacy promise is about. It is short enough that the intent limit is not close.
 */
private fun shareConversation(
    context: android.content.Context,
    title: String,
    fileName: String,
    shareTitle: String,
    text: String,
) {
    val intent =
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, title)
            putExtra(android.content.Intent.EXTRA_TITLE, fileName)
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
    context.startActivity(
        android.content.Intent.createChooser(intent, shareTitle).addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
        ),
    )
}

/**
 * Hands text to whatever the user picks.
 *
 * The same shape as [shareConversation] without a file name, and text rather than a
 * file for the same reason: an app that promises not to write user data outside its
 * own storage has exactly one way to hand something over, and this is it.
 */
private fun shareText(context: android.content.Context, subject: String, text: String) {
    val intent =
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
    context.startActivity(
        android.content.Intent.createChooser(intent, subject).addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
        ),
    )
}

/** The heading for a bucket, as a resource. */
private fun DateBucket.label(): Int = when (this) {
    DateBucket.TODAY -> R.string.date_today
    DateBucket.YESTERDAY -> R.string.date_yesterday
    DateBucket.LAST_SEVEN_DAYS -> R.string.date_last_seven
    DateBucket.LAST_THIRTY_DAYS -> R.string.date_last_thirty
    DateBucket.EARLIER -> R.string.date_earlier
}