package com.example.demoapp.data.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every conversation, in a form that can be written back.
 *
 * The plain-text export is for a person to read; this is for the app to read. They
 * are different jobs and one format cannot do both: text loses which model said
 * what, when, and whether a reply was interrupted, and those are the things a
 * restored conversation is judged by.
 *
 * ## Why this exists at all
 *
 * The app keeps everything in private storage and declares no backup, on purpose:
 * a chat log is as personal as data gets, and syncing it anywhere would break the
 * promise the About screen makes. But "not synced" must not mean "not movable", or
 * privacy becomes a cage -- a new phone would start empty, and the only way to keep
 * the old conversations would be to break the promise.
 *
 * So the user carries them. Nothing here writes to storage, uploads anything, or
 * runs on its own: the bytes go into a share the user initiated, and come back from
 * a file the user picked.
 */
@Serializable
data class ConversationBackup(
    /**
     * The format's own version.
     *
     * Read before anything else. A backup from a newer build is refused by name
     * rather than half-parsed, because the failure mode of guessing is a restored
     * conversation that is subtly missing messages -- and the user would have no
     * way to tell that from having lost them.
     */
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long,
    val conversations: List<Conversation>,
) {
    companion object {
        /**
         * Bump when the meaning of a stored field changes.
         *
         * Adding an optional field does not need a bump: an old file still parses,
         * and a new field is simply absent. A bump means the old file would be read
         * *wrongly*, which is the only case worth refusing.
         */
        const val CURRENT_VERSION = 1
    }
}

/**
 * Reads and writes [ConversationBackup].
 *
 * Failing is a value rather than an exception: a file the user picked is external
 * input, and "that file is not a backup from this app" is an answer, not a crash.
 */
object ConversationBackupCodec {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // Unknown keys are tolerated so a backup from a build that added a field
        // still restores what this one understands, rather than refusing whole.
        ignoreUnknownKeys = true
    }

    fun encode(conversations: List<Conversation>, now: Long = System.currentTimeMillis()): String =
        json.encodeToString(ConversationBackup(exportedAt = now, conversations = conversations))

    /** The result of reading a file the user chose. */
    sealed interface ReadResult {
        /**
         * Conversations that were read out of the file but had nothing in them.
         *
         * Counted so the screen can say so. `ChatStore.restore` reports the two
         * reasons it refuses a conversation — an id it cannot store safely, and one
         * already on the device — but an empty one never reaches it: it is dropped
         * here, and without this the only visible outcome of a restore is that fewer
         * conversations came back than the file appears to hold.
         *
         * Only the empty case. An unusable id is also caught by `restore`, which
         * names it, and counting it in both places would report one loss twice.
         */
        data class Ok(
            val conversations: List<Conversation>,
            val emptyDropped: Int = 0,
        ) : ReadResult

        /** Not this app's backup, or damaged. [detail] is for diagnosis, not display. */
        data class Unreadable(val detail: String) : ReadResult

        /** Written by a newer build, whose format this one would read wrongly. */
        data class TooNew(val version: Int) : ReadResult
    }

    fun decode(text: String): ReadResult {
        val parsed =
            try {
                json.decodeFromString<ConversationBackup>(text)
            } catch (t: Throwable) {
                return ReadResult.Unreadable(t.message ?: t::class.simpleName ?: "unreadable")
            }

        if (parsed.version > ConversationBackup.CURRENT_VERSION) {
            return ReadResult.TooNew(parsed.version)
        }

        // A conversation with no id cannot be stored, and one with no messages is
        // a row that does nothing. Dropped rather than refused: half a backup is
        // worth more than none. The empty ones are counted, because nothing else
        // would ever mention them; an unusable id is left for `restore` to name,
        // which is the layer that has to refuse it anyway.
        val usable = parsed.conversations.filter { it.id.isNotBlank() && it.messages.isNotEmpty() }
        return ReadResult.Ok(
            conversations = usable,
            emptyDropped = parsed.conversations.count { it.id.isNotBlank() && it.messages.isEmpty() },
        )
    }
}

/**
 * A conversation as Markdown, for reading anywhere else.
 *
 * Markdown rather than plain text because the conversation already is Markdown --
 * the model writes it, the app renders it -- and stripping the formatting to
 * produce an export loses the tables and code blocks that were the reason to keep
 * the answer.
 *
 * The model and the time are kept as a blockquote under each reply, so an answer
 * copied out of the app can still say which model produced it and when. Nothing
 * that was not said is added: no header, no footer, no name of this app.
 */
fun Conversation.toMarkdown(labels: MarkdownLabels): String = buildString {
    messages.forEach { message ->
        append(if (message.role == MessageRole.USER) "## " else "### ")
        appendLine(message.text)
        appendLine()
        if (message.role == MessageRole.ASSISTANT) {
            // The words for these come from the caller, like `ExportLabels` does for
            // the plain-text export. They were literals here -- "model:", "tokens:",
            // "tok/s" -- and an exported file is something a person opens and reads,
            // so English labels in it are copy in a language the app does not use.
            // The copy gate had skipped them for the same reason it skipped the
            // renderer's: "%.1f tok/s" contains a percent, and a literal with a
            // percent in it was assumed to be a template with nothing to translate.
            val notes =
                listOfNotNull(
                    message.modelId?.takeIf { it.isNotBlank() }?.let { "${labels.model} $it" },
                    message.tokenCount?.let { "${labels.tokens} $it" },
                    message.decodeTokensPerSecond?.let { labels.rate(it) },
                    labels.clock(message.createdAt),
                )
            if (notes.isNotEmpty()) {
                appendLine("> " + notes.joinToString(" · "))
                appendLine()
            }
        }
    }
}.trimEnd() + "\n"

/**
 * The words a Markdown export needs, so this layer holds no copy of its own.
 *
 * Separate from [ConversationExport.ExportLabels] rather than shared: the two
 * exports are read in different places -- one is a transcript, the other is a
 * document that goes into an editor -- and they label different things. Merging
 * them would mean every caller passing fields it does not use, which is how a
 * label ends up in the wrong export.
 *
 * @param model prefix for the model's name, as in "model: Qwen".
 * @param tokens prefix for the token count.
 * @param rate the whole rate, number and unit together, because the unit is a word
 *   and the number is not: splitting them here is what put "tok/s" in this file.
 * @param clock the time under each reply.
 */
data class MarkdownLabels(
    val model: String,
    val tokens: String,
    val rate: (Double) -> String,
    val clock: (Long) -> String,
)