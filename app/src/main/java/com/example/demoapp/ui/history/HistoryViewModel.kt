package com.example.demoapp.ui.history



import com.example.demoapp.ui.UiMessage

import com.example.demoapp.R

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.demoapp.data.chat.ChatStore
import com.example.demoapp.data.chat.Conversation
import com.example.demoapp.data.chat.ConversationBackupCodec
import com.example.demoapp.data.chat.ConversationExport
import com.example.demoapp.data.chat.MarkdownLabels
import com.example.demoapp.data.chat.toMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lists what is on the device and lets the user open or delete it.
 *
 * Reads the store directly rather than through the chat screen's ViewModel. The
 * two are separate screens with separate lifetimes, and the chat ViewModel holds
 * the open conversation in memory -- asking it what is on disk would answer from
 * that state, not from the files.
 */
class HistoryViewModel(
    private val chatStore: ChatStore,
    /**
     * Which conversation the chat screen has open, or a flow that never emits.
     *
     * Observed rather than asked for: the chat screen is a different destination
     * with its own lifetime and no reference to this one, so the only thing they
     * can share is a value someone else owns.
     */
    openConversation: StateFlow<String?> = MutableStateFlow(null),
    /**
     * The current time, injected.
     *
     * A parameter so a test can pin it. The pin order is a real behaviour — pinned
     * conversations are shown oldest pin first — and it is not observable if every
     * pin in a test carries the same millisecond.
     */
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            openConversation.collect { id ->
                _state.value = _state.value.copy(openConversationId = id)
            }
        }
    }

    /** Serialises reads and the assignment that publishes them; see [reload]. */
    private val reloadLock = Mutex()

    init {
        reload()
    }

    /**
     * Re-reads the list off the main thread.
     *
     * Reading and publishing under the same lock, because two overlapping reads
     * can otherwise finish in the wrong order and leave the older list on screen.
     */
    fun reload() {
        viewModelScope.launch {
            reloadLock.withLock {
                // The sizes come back with the list, in one IO block, because the row
                // shows them and `ChatStore.sizes()` was written for that and had no
                // caller: the field stayed at its `null` default on every row, so the
                // screen never showed how much space a conversation takes, and the
                // sentence in `HistoryUiState` about answering "which one is using
                // the space" described something the app did not do.
                val (conversations, sizes) = withContext(Dispatchers.IO) {
                    chatStore.list() to chatStore.sizes()
                }
                // Read after list(), which is what sets it. A conversation whose key
                // is gone cannot be listed, and without this the only thing the user
                // would know is that it is not there.
                val unreadable = chatStore.unreadable
                val changed = unreadable != _state.value.unreadable
                _state.value = _state.value.copy(
                    rows = conversations.map { HistoryRow(it, bytes = sizes[it.id]) },
                    scanned = true,                    unreadable = unreadable,
                    // A new number is new news. The reader dismissed "three could not
                    // be read", not the subject.
                    unreadableNoticeDismissed =
                        if (changed) false else _state.value.unreadableNoticeDismissed,
                )
            }
        }
    }

    /**
     * Marks the conversation the chat screen has open, so the list shows it.
     *
     * The observer above is what calls this in the app. It stays public because
     * it is the seam the state is set through, and a test that wants to see the
     * badge render should not have to stand up a chat screen to do it.
     */
    fun setOpenConversation(id: String?) {
        _state.value = _state.value.copy(openConversationId = id)
    }

    fun requestDelete(id: String) {
        _state.value = _state.value.copy(
            rows = _state.value.rows.map { it.copy(confirmingDelete = it.conversation.id == id) },
        )
    }

    fun cancelDelete() {
        _state.value = _state.value.copy(rows = _state.value.rows.map { it.copy(confirmingDelete = false) })
    }

    /**
     * Deletes the conversation the user confirmed.
     *
     * Reported when it fails, for the same reason deleting a model is: a
     * deliberate destructive action that silently does nothing is
     * indistinguishable from the app ignoring the tap.
     */
    fun confirmDelete() {
        val target = _state.value.rows.firstOrNull { it.confirmingDelete } ?: return
        cancelDelete()

        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { chatStore.delete(target.conversation.id) }.exceptionOrNull()
            }
            if (failure != null) {
                _state.value = _state.value.copy(
                    error = UiMessage(R.string.err_history_delete, target.conversation.title)
                        .withDetail(failure.message ?: failure::class.simpleName),
                )
            }
            reload()
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    // --------------------------------------------------------------- search ---

    /**
     * Narrows the list to what matches [query].
     *
     * The filter itself lives on the state, so it is applied without touching the
     * store: searching is a view of the list, not a second query against it.
     */
    fun setSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    // --------------------------------------------------------------- export ---

    /**
     * Prepares a conversation for sharing.
     *
     * The text is produced here and handed to the caller, which owns the Android
     * parts. Nothing is written to storage: the app's promise is that it keeps
     * nothing it was not asked to keep, and a file left behind by a cancelled
     * share would be exactly that.
     */
    fun prepareExport(
        id: String,
        labels: ConversationExport.ExportLabels,
        onReady: (title: String, fileName: String, text: String) -> Unit,
    ) {
        viewModelScope.launch {
            val conversation = withContext(Dispatchers.IO) { chatStore.load(id) }
            if (conversation == null) {
                _state.value = _state.value.copy(error = UiMessage(R.string.err_history_open))
                return@launch
            }
            onReady(
                conversation.title,
                ConversationExport.fileNameFor(conversation),
                ConversationExport.asText(conversation, labels),
            )
        }
    }


    /**
     * The stored conversations as one Markdown document, or null when there are
     * none.
     *
     * Read from disk rather than from the list on screen, so an export is not
     * affected by what happens to be filtered or scrolled into view.
     */
    suspend fun buildMarkdown(labels: MarkdownLabels): String? = withContext(Dispatchers.IO) {
        val stored = chatStore.list().mapNotNull { chatStore.load(it.id) }
        if (stored.isEmpty()) {
            null
        } else {
            // A rule between conversations, so pasting two of them into one file
            // does not run them together into something that reads as one.
            stored.joinToString("\n\n---\n\n") { it.toMarkdown(labels) }
        }
    }

    // ------------------------------------------------------------ archiving ---

    /**
     * Puts a conversation away, or brings it back.
     *
     * Loaded from disk before flipping rather than edited from the row on screen.
     * The row is a snapshot, and a conversation that changed in between -- a reply
     * that arrived from the chat screen -- would be written back without it.
     */
    fun toggleArchive(id: String) {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { chatStore.load(id) } ?: return@launch
            val updated = stored.copy(archivedAt = if (stored.archivedAt == null) clock() else null)
            val outcome = withContext(Dispatchers.IO) { runCatching { chatStore.save(updated) } }
            if (outcome.isFailure) {
                _state.value = _state.value.copy(error = UiMessage(R.string.err_history_archive))
            }
            reload()
        }
    }

    // --------------------------------------------------------------- backup ---

    /** The export had nothing to say, because there is nothing stored. */
    fun reportNothingToExport() {
        _state.value = _state.value.copy(error = UiMessage(R.string.backup_nothing_to_export))
    }

    /** The picked file could not be read at all. */
    fun reportUnreadableBackup() {
        _state.value = _state.value.copy(error = UiMessage(R.string.backup_import_unreadable))
    }

    /** The last restore report has been read. */
    fun dismissRestored() {
        _state.value = _state.value.copy(restored = null)
    }

    /**
     * Hides the unreadable-conversations notice for this sitting.
     *
     * Only the notice, not the count: the conversations are still unreadable, and
     * the next scan is what decides whether to say so again. Clearing the count
     * instead would make the next reload announce it a second time, which reads as
     * more conversations having been lost.
     */
    fun dismissUnreadable() {
        _state.value = _state.value.copy(unreadableNoticeDismissed = true)
    }

    /**
     * Every conversation, as a file the user can keep.
     *
     * Read at the moment of export rather than from the rows on screen, so nothing
     * is left out because a search was active or the list had not finished loading.
     * Nothing is written to storage: the text goes into a share the user started,
     * which is the only way an app that keeps everything private can hand anything
     * over at all.
     */
    suspend fun buildBackup(): String? {
        val all = withContext(Dispatchers.IO) { chatStore.list() }
        if (all.isEmpty()) return null
        return ConversationBackupCodec.encode(all, now = clock())
    }

    /**
     * Adds conversations from a file the user picked.
     *
     * Every outcome is reported. "Nothing was added because they are all already
     * here" and "nothing was added because this is not a backup" look identical on
     * screen otherwise, and they mean opposite things to someone who just restored.
     */
    fun restore(text: String) {
        viewModelScope.launch {
            when (val result = ConversationBackupCodec.decode(text)) {
                is ConversationBackupCodec.ReadResult.Unreadable -> {
                    _state.value = _state.value.copy(
                        error = UiMessage(R.string.backup_import_unreadable)
                            .withDetail(result.detail),
                    )
                }

                is ConversationBackupCodec.ReadResult.TooNew -> {
                    _state.value = _state.value.copy(
                        error = UiMessage(R.string.backup_import_too_new, result.version),
                    )
                }

                is ConversationBackupCodec.ReadResult.Ok -> {
                    if (result.conversations.isEmpty()) {
                        // "No usable conversations" and "this backup holds nothing"
                        // are different answers, and the second one is not a fault:
                        // a file of empty conversations is reported as exactly that
                        // rather than as a file with nothing in it.
                        _state.value =
                            _state.value.copy(
                                error = UiMessage(
                                    if (result.emptyDropped > 0) R.string.backup_import_only_empty
                                    else R.string.backup_import_none,
                                ),
                            )
                        return@launch
                    }

                    val failure =
                        withContext(Dispatchers.IO) {
                            runCatching { chatStore.restore(result.conversations) }.getOrNull()
                        }

                    if (failure == null) {
                        _state.value = _state.value.copy(error = UiMessage(R.string.err_backup_import))
                    } else {
                        _state.value =
                            _state.value.copy(
                                restored = UiMessage(
                                    R.string.backup_import_result,
                                    failure.added,
                                    failure.skipped,
                                ),
                                error =
                                    when {
                                        failure.failed.isNotEmpty() ->
                                            UiMessage(
                                                R.string.backup_import_partial,
                                                failure.failed.size,
                                            )

                                        // Named rather than silent. `ChatStore.restore`
                                        // never sees these -- an empty conversation is
                                        // dropped while the file is being read -- so
                                        // without this the count on screen is simply
                                        // lower than the file, with nothing to explain
                                        // it.
                                        result.emptyDropped > 0 ->
                                            UiMessage(
                                                R.string.backup_import_empty_dropped,
                                                result.emptyDropped,
                                            )

                                        else -> null
                                    },
                            )
                    }
                    reload()
                }
            }
        }
    }

    // ---------------------------------------------------------------- pinning ---

    /**
     * Pins a conversation, or unpins it.
     *
     * Read from disk first rather than from the row the user tapped. The row is a
     * snapshot taken when the list was built, and a reply written since then would
     * be thrown away by saving it back — the same way a stale in-memory copy loses
     * a rename. Only the new pin value is changed; everything else is whatever is
     * actually stored.
     */
    fun togglePin(id: String) {
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    val conversation = chatStore.load(id) ?: return@runCatching
                    chatStore.save(
                        conversation.copy(
                            pinnedAt = if (conversation.pinnedAt == null) clock() else null,
                        ),
                    )
                }.exceptionOrNull()
            }
            if (failure != null) {
                _state.value = _state.value.copy(
                    error = UiMessage(R.string.err_history_pin)
                        .withDetail(failure.message ?: failure::class.simpleName),
                )
            }
            reload()
        }
    }

    // -------------------------------------------------------------- renaming ---

    /** Opens the rename dialog for one conversation. */
    fun requestRename(id: String) {
        val row = _state.value.rows.firstOrNull { it.conversation.id == id } ?: return
        _state.value = _state.value.copy(renaming = Renaming(id, row.conversation.title))
    }

    fun cancelRename() {
        _state.value = _state.value.copy(renaming = null)
    }

    /**
     * Saves a new title.
     *
     * Whitespace-only and unchanged titles are treated as "leave it alone" rather
     * than written: a conversation whose title is a run of spaces is one no list
     * can show, and a title the user did not actually change is not worth a write.
     */
    fun confirmRename(title: String) {
        val target = _state.value.renaming ?: return
        _state.value = _state.value.copy(renaming = null)

        val cleaned = title.trim().take(MAX_TITLE_LENGTH)
        if (cleaned.isEmpty()) return

        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    val conversation = chatStore.load(target.id) ?: return@runCatching
                    if (conversation.title == cleaned) return@runCatching
                    chatStore.save(
                        conversation.copy(title = cleaned, updatedAt = conversation.updatedAt + 1),
                    )
                }.exceptionOrNull()
            }
            if (failure != null) {
                _state.value = _state.value.copy(
                    error = UiMessage(R.string.err_history_rename)
                    .withDetail(failure.message ?: failure::class.simpleName),
                )
            }
            reload()
        }
    }

    private companion object {
        /** Long enough to be a sentence, short enough to fit one line of a list. */
        const val MAX_TITLE_LENGTH = 60
    }
}

/**
 * How a message time is written in an export.
 *
 * Public because the Markdown export is handed this rather than resolving copy in
 * its own layer: `HistoryScreen` builds the labels during composition, where a
 * language change is observable, and this is the one label that is a computation
 * rather than a lookup.
 */
fun clockText(epochMillis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMillis))
