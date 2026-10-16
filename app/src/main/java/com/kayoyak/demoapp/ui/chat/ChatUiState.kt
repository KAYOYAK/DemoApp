package com.kayoyak.demoapp.ui.chat

import com.kayoyak.demoapp.ui.UiMessage

import com.kayoyak.demoapp.data.chat.ChatMessage
import com.kayoyak.demoapp.model.ModelDescriptor

/**
 * Everything the chat screen renders.
 *
 * The states are the ones the screen actually reaches: no model installed, a
 * model still loading, ready and empty, ready with history, generating, and
 * failed. See the project's UI/UX governance rules: cover the states the
 * operation has rather than only the happy path.
 */
data class ChatUiState(
    /** Persisted history, oldest first. */
    /**
     * Which conversation is open, so another screen can say so.
     *
     * Null until one is opened or the first message creates it.
     */
    val conversationId: String? = null,

    val messages: List<ChatMessage> = emptyList(),

    /**
     * Text of the reply currently being generated.
     *
     * Held separately from [messages] because it is not history yet: it has not
     * been written to disk and it changes on every token. Keeping it out of the
     * list means the list is only ever something that exists.
     */
    val streaming: String = "",

    val generating: Boolean = false,

    /** Non-null when the last generation failed. */
    val error: UiMessage? = null,

    /** The model loaded in the engine, if any. */
    val modelId: String? = null,
    val modelDisplayName: String? = null,

    /**
     * Models present on the device.
     *
     * Only installed ones: offering a choice that would trigger a download from
     * inside a chat screen hides a multi-gigabyte operation behind a dropdown.
     */
    val installedModels: List<ModelDescriptor> = emptyList(),

    /** True while a model file is being read into the engine. */
    val loadingModel: Boolean = false,

    /**
     * False until the installed-model scan has finished.
     *
     * Without it the initial state is indistinguishable from "nothing is
     * installed", so a cold start shows the screen whose whole job is sending the
     * user to the model list, and then flips. Reading the device is quick but not
     * instant.
     */
    val scanned: Boolean = false,
    /**
     * What the user is looking for inside this conversation, or empty.
     *
     * A filter rather than a highlight-and-jump. Highlighting is the better
     * experience inside one long reply, and it is the wrong first version: it needs
     * scroll-to-match, a match counter, and somewhere to put the "3 of 7" a user
     * needs when they reach the end and the search has not. A list showing only
     * what matched, with the count in the header, answers the actual question --
     * where did it say that -- and never leaves someone in a transcript with the
     * matches somewhere off screen.
     */
    val searchQuery: String = "",
    /**
     * A model the user picked that this device may not have room for.
     *
     * A question rather than a refusal. The figure is an estimate and the device's
     * free memory is not read at all, so someone who has just closed everything
     * else knows more about this moment than the app does. The platform's own
     * Android reference client asks here too.
     */
    val ramWarning: PendingRamWarning? = null,
    /**
     * Why the last load failed, when it was a load that failed.
     *
     * Apart from [error], which is a sentence: this carries the runtime's own
     * account as well, which is what makes a report actionable and what nobody
     * should have to read to understand the sentence.
     */
    val loadFailure: com.kayoyak.demoapp.inference.LoadFailure? = null,
    /**
     * The message currently being read aloud, or null.
     *
     * The id rather than a boolean: with a boolean, every reply on screen would
     * offer the same "stop", and the user would not know which one was talking.
     */
    val speakingId: String? = null,
    /**
     * How many earlier turns are no longer being sent to the model.
     *
     * On screen because the alternative is silent: the model gets worse, the user
     * concludes the model is bad, and nothing anywhere says the conversation
     * outgrew the window six replies ago.
     */
    val droppedTurns: Int = 0,
    /** Whether every message carries the time it was written. */
    val showTimestamps: Boolean = false,
    /**
     * How many tokens the conversation occupies, and how many it may.
     *
     * Shown because the alternative is what every local chat app does: the model
     * quietly stops reading the beginning of a long conversation and the user
     * concludes it got worse. A number is the difference between "this model is
     * bad" and "this conversation is full".
     *
     * Clamped so the meter can never read past its own limit. A gauge showing
     * more than the maximum is a bug reported against real tools, and it happens
     * when the count is read after the runtime has already shifted the window.
     */
    val contextTokens: Int? = null,
    val contextWindow: Int? = null,
) {
    /**
     * The used and total to show, or null when the runtime did not report both.
     *
     * The used value is clamped to the total: the count is read after a turn, and
     * a runtime that has already discarded the oldest messages can report a number
     * from before it did.
     */
    val contextUsage: Pair<Int, Int>?
        get() {
            val total = contextWindow?.takeIf { it > 0 } ?: return null
            val used = contextTokens?.takeIf { it >= 0 } ?: return null
            return minOf(used, total) to total
        }
    /** The device has been checked and there is nothing to chat with. */
    val needsAModel: Boolean get() = scanned && installedModels.isEmpty() && !loadingModel

    /**
     * The messages that match [searchQuery], or all of them.
     *
     * Computed rather than stored so it cannot go stale against the transcript, and
     * skipped entirely when nothing is being searched for, which is the common case.
     */
    val visibleMessages: List<ChatMessage>
        get() {
            val needle = searchQuery.trim()
            if (needle.isEmpty()) return messages
            return messages.filter { it.text.contains(needle, ignoreCase = true) }
        }

    /** True while a search is narrowing the transcript. */
    val searching: Boolean get() = searchQuery.isNotBlank()

    /** A search that matched nothing, which is not the same as nothing to search. */
    val searchFoundNothing: Boolean get() = searching && visibleMessages.isEmpty()

    /** Still finding out what is installed. */
    val scanning: Boolean get() = !scanned

    /** A model is installed and loaded, so a message can be sent. */
    val canSend: Boolean get() = modelId != null && !generating && !loadingModel

    /** Nothing has been said yet and nothing is being said. */
    val isEmpty: Boolean get() = messages.isEmpty() && streaming.isEmpty() && !generating
}

/** A load the user is being asked about, with the numbers to decide with. */
data class PendingRamWarning(
    val descriptor: com.kayoyak.demoapp.model.ModelDescriptor,
    val requiredBytes: Long,
    val budgetBytes: Long,
)