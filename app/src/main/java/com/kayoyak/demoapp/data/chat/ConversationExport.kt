package com.kayoyak.demoapp.data.chat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns a conversation into text someone can keep.
 *
 * The app's promise is that nothing leaves the device on its own. This is the
 * user asking for a copy, which is a different act: the two are only in tension
 * if sharing were automatic, and the button that calls this says where the text
 * is going.
 *
 * Deliberately plain text with no markup. What a recipient can read is a message,
 * an email, or a note -- and a format that renders only in one app is one that
 * arrives as noise everywhere else.
 */
object ConversationExport {

    /**
     * The conversation as plain text.
     *
     * The wording is passed in rather than written here. This is a data-layer
     * function with no Context and no business holding copy, and the labels it
     * needs -- who spoke, what a stopped reply means, what the footer says -- are
     * the app's language, which lives in the string resources. A literal here is
     * text that ships in the wrong language and is never noticed, because it only
     * appears after someone has exported something.
     */
    fun asText(
        conversation: Conversation,
        labels: ExportLabels,
        now: Long = System.currentTimeMillis(),
    ): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val builder = StringBuilder()

        builder.appendLine(conversation.title)
        builder.appendLine("=".repeat(minOf(conversation.title.length.coerceAtLeast(1), 60)))
        builder.appendLine()

        conversation.messages.forEach { message ->
            val who = if (message.role == MessageRole.USER) labels.you else labels.model
            builder.appendLine("$who · ${stamp.format(Date(message.createdAt))}")
            builder.appendLine(message.text)
            if (message.interrupted) {
                // Worth keeping: a reply that stops mid-sentence looks like a
                // mistake in the export otherwise.
                builder.appendLine(labels.stoppedHere)
            }
            builder.appendLine()
        }

        builder.appendLine("---")
        builder.appendLine(labels.footer)
        builder.appendLine(labels.exportedAt(stamp.format(Date(now))))

        return builder.toString()
    }

    /** The words an export needs, so this layer never holds copy of its own. */
    data class ExportLabels(
        val you: String,
        val model: String,
        val stoppedHere: String,
        val footer: String,
        val exportedAt: (String) -> String,
    )

    /**
     * The name the shared file should have.
     *
     * Built from the title rather than from the id, because it is the name a
     * recipient sees. Everything that is not safe in a file name is replaced
     * rather than removed, so two titles cannot collapse into the same name.
     */
    fun fileNameFor(conversation: Conversation): String {
        val base = conversation.title
            .map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            // The filler is trimmed as well as the whitespace. A title of nothing
            // but punctuation becomes a run of underscores, which is a legal file
            // name and a useless one.
            .trim(' ', '_', '-')
            .replace(Regex("\\s+"), "-")
            .take(40)
            .trim(' ', '_', '-')
            .ifBlank { "conversation" }
        return "$base.txt"
    }
}
