package com.kayoyak.demoapp.ui.history

import com.kayoyak.demoapp.ui.UiMessage

import com.kayoyak.demoapp.data.chat.Conversation

/** One conversation as the history list shows it. */
data class HistoryRow(
    val conversation: Conversation,
    /** True while the user is being asked to confirm deleting this one. */
    val confirmingDelete: Boolean = false,
    /**
     * How much room this conversation takes on disk, or null when not known yet.
     *
     * Shown so "which one is using the space" has an answer before deleting one.
     * Null rather than zero: an unknown size and an empty conversation are
     * different, and a row claiming "0 B" invites deleting the wrong thing.
     */
    val bytes: Long? = null,
)

data class HistoryUiState(
    val rows: List<HistoryRow> = emptyList(),
    /**
     * False until the first read of the device has completed.
     *
     * Without it the initial state is indistinguishable from "there are no
     * conversations", and the screen would tell a user with a year of history
     * that they have none.
     */
    val scanned: Boolean = false,
    /** A delete that did not happen, or a conversation that could not be read. */
    val error: UiMessage? = null,
    /**
     * What the last restore did, when it did anything.
     *
     * Separate from [error] because a restore that added nothing is not a failure:
     * "all of them are already here" is a success, and reporting it as an error
     * would tell a user their backup did not work when it did.
     */
    val restored: UiMessage? = null,
    /**
     * How many stored conversations the last scan could not read.
     *
     * Not an error, and not the user's fault: the keystore entry is gone, so the
     * ciphertext will never decrypt again. Counted rather than swallowed because
     * the alternative is a conversation missing from a list with nothing said,
     * which reads as one that was deleted.
     */
    val unreadable: Int = 0,
    /**
     * True once the reader has dismissed the notice about [unreadable].
     *
     * Held separately from the count so a later scan that finds a *different*
     * number can speak again: the reader dismissed "three could not be read", not
     * "never tell me about this again".
     */
    val unreadableNoticeDismissed: Boolean = false,
    /** Which conversation the chat screen currently has open. */
    val openConversationId: String? = null,
    /** The conversation whose title is being edited, if any. */
    val renaming: Renaming? = null,
    /** What the user typed into the search box. */
    val searchQuery: String = "",
) {
    val scanning: Boolean get() = !scanned
    val isEmpty: Boolean get() = scanned && rows.isEmpty()

    /**
     * The rows to show, after the search.
     *
     * Matched against the title and every message, because a conversation's title
     * is one sentence from its beginning and what someone remembers is usually
     * something said later.
     */
    val visibleRows: List<HistoryRow>
        get() {
            val needle = searchQuery.trim()
            if (needle.isEmpty()) return rows
            return rows.filter { row ->
                row.conversation.title.contains(needle, ignoreCase = true) ||
                    row.conversation.messages.any { it.text.contains(needle, ignoreCase = true) }
            }
        }

    /** True when a search is hiding everything rather than there being nothing. */
    val searchFoundNothing: Boolean
        get() = searchQuery.isNotBlank() && visibleRows.isEmpty() && rows.isNotEmpty()

    /** Whether to show the notice that some conversations could not be read. */
    val showsUnreadable: Boolean
        get() = unreadable > 0 && !unreadableNoticeDismissed
}

/** A title being edited, and what it was before. */
data class Renaming(val id: String, val currentTitle: String)

/** The id carried between the history screen and the chat screen. */
object HistoryKeys {
    /** Set by the history screen when the user picks a conversation. */
    const val CHOSEN_ID = "chosenConversationId"
}
