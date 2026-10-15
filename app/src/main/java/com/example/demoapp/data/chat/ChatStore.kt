package com.example.demoapp.data.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.example.demoapp.data.FileCipher
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Where conversations are kept.
 *
 * An interface so the storage mechanism is replaceable; the JSON implementation
 * below is the current one. Nothing outside this file knows how a conversation
 * is serialised.
 */
interface ChatStore {

    /** Conversations, most recently updated first. */
    suspend fun list(): List<Conversation>

    suspend fun load(id: String): Conversation?

    suspend fun save(conversation: Conversation)

    /** Removes a conversation and its file. */
    suspend fun delete(id: String)

    /**
     * Adds conversations from a backup.
     *
     * Returns which were added and which were skipped as already present. Skipping
     * rather than overwriting is the honest default for a restore: the file on the
     * device may have messages the backup predates, and a restore that replaced it
     * would lose the newer conversation to the older one with no prompt.
     *
     * An id already present is the signal for that. Ids are generated per
     * conversation, so a collision means the same conversation, not two that happen
     * to share a name.
     */
    suspend fun restore(conversations: List<Conversation>): RestoreReport

    /**
     * How many conversations the last [list] could not read.
     *
     * A conversation whose keystore entry is gone -- cleared by the platform, or
     * absent because the app was restored onto another device -- is ciphertext that
     * will never decrypt again. Skipping it is right: one unreadable file must not
     * make the history screen unusable. Skipping it *silently* is not, because a
     * conversation that vanishes from the list is indistinguishable from one that
     * was deleted, and the user did not do that.
     *
     * A count rather than the ids: what the reader needs is that it happened and
     * how often, and the ids are not something they can act on.
     */
    val unreadable: Int

    /** Total bytes of stored history, for a storage screen. */
    suspend fun bytesUsed(): Long

    /**
     * How many bytes each conversation takes, by id.
     *
     * One read rather than a call per row: the history screen shows all of them at
     * once, and asking the filesystem per conversation would be a scan of the
     * directory per conversation.
     */
    suspend fun sizes(): Map<String, Long>
}

/**
 * What a restore did.
 *
 * Reported rather than silent because the two outcomes mean different things to
 * someone who just picked a file: "12 conversations restored" and "12 conversations
 * were already here" are both successes, and a user who cannot tell them apart
 * cannot tell whether their backup worked.
 */
data class RestoreReport(
    val added: Int,
    val skipped: Int,
    /** Conversations the store refused to write, with why. */
    val failed: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = added == 0 && skipped == 0 && failed.isEmpty()
}
/**
 * Conversations stored as one JSON file each.
 *
 * One file per conversation rather than a single file for everything: appending
 * a message then rewrites only the conversation that changed, and a file
 * damaged by an interrupted write costs one conversation instead of all of
 * them.
 *
 * A conversation that fails to parse is skipped rather than throwing, so one
 * unreadable file cannot make the history screen unusable.
 *
 * @param root directory for conversation files. App-private: a chat log is
 *   about as personal as data gets.
 */
class FileChatStore(
    private val root: File,
    /**
     * Replaces the second file with the first.
     *
     * A parameter rather than a direct call to `Files.move` for one reason: a move
     * that is refused has to be provable in a test. On Windows a rename is refused
     * for as long as something that is not this app holds the file -- a virus
     * scanner that has just seen it closed, or the search indexer -- and the retry
     * below is the difference between a save that lands and a save the user is told
     * failed while the data was never in question.
     */
    private val moveFile: (File, File) -> Unit = ::replaceFile,
) : ChatStore {

    /** Serialises writes; see [save]. */
    private val writeLock = Mutex()

    /**
     * When each id was last deleted.
     *
     * A delete removes a file, but a save for the same conversation can already be
     * queued behind the lock, and when it runs the file is gone -- so the "newest
     * wins" check below, which compares against what is stored, has nothing to
     * compare against and writes the conversation back. The user removed it and it
     * reappears, most easily by leaving the app: closing the chat screen saves.
     *
     * A conversation whose `updatedAt` is at or before the deletion is a version
     * the user deleted, so it is not written. One that has genuinely changed since
     * is stamped newer -- `Conversation.stamp` is monotonic against the wall clock
     * as well as against itself -- and is written, which is what continuing a
     * deleted conversation should do.
     *
     * In memory only, and that is correct: a queued save cannot outlive the
     * process that queued it.
     */
    private val deletedAt = mutableMapOf<String, Long>()

    /** Set by [list]; see [ChatStore.unreadable] for why it is reported rather than dropped. */
    private var unreadableCount = 0

    override val unreadable: Int get() = unreadableCount

    /**
     * What [save] did, so a caller can tell "the write was refused" from "there was
     * no write".
     *
     * Added because a test could not tell those apart and the guess was wrong three
     * times. `ChatViewModelTest` waits for a conversation to reach disk; when the
     * wait times out it reports what is on disk and nothing else, so a timeout reads
     * identically whether the save was rejected by one of the two guards below or
     * whether `save` was never called at all. Every attempt at an explanation --
     * teardown, polling starvation, a stuck mutex -- had to assume one of the two,
     * and each was refuted by the next measurement.
     *
     * `internal` rather than on the [ChatStore] interface: this is an observation for
     * tests and for a diagnosis, not something a caller decides anything with, and
     * putting it on the interface would oblige every implementation to keep the same
     * three counts in step forever.
     *
     * Plain `Int`s read from a test thread. They are written under [writeLock], so
     * they can only be read mid-update if a reader looks while a save is between two
     * increments -- and a reader deciding "did anything happen" does not care which.
     */
    internal data class SaveAudit(
        /** Calls that reached [save] and got past the lock. */
        val attempted: Int,
        /** Refused because a delete for this id is newer. See [deletedAt]. */
        val refusedAsDeleted: Int,
        /** Refused because what is on disk is newer. The "newest wins" rule. */
        val refusedAsOlder: Int,
        /** Writes that actually reached the filesystem. */
        val written: Int,
        /**
         * The last move that used up every attempt, or null when none has.
         *
         * The failure a caller sees does not carry this. `Files.move` throws a
         * `FileSystemException` whose message is `source -> target` plus a reason
         * that a Windows host does not always supply, and it is caught and
         * reported as a save error -- so two rounds of widening
         * [MOVE_RETRY_MILLIS] were spent without ever learning what refused the
         * move. What is recorded here is the exception's own class, its reason or
         * the fact that none was given, and how many attempts waited how long, so
         * the next occurrence answers the question instead of widening a guess.
         */
        val lastMoveRefusal: String?,
    )

    private var savesAttempted = 0
    private var savesRefusedAsDeleted = 0
    private var savesRefusedAsOlder = 0
    private var savesWritten = 0

    /** The last refusal that outlived every attempt. See [SaveAudit.lastMoveRefusal]. */
    private var lastMoveRefusal: String? = null

    /**
     * The four counts, as they stand.
     *
     * These were declared, documented, and never incremented, and every reader of
     * [SaveAudit] read zero for as long as that was true. The consequence was not a
     * missing report but a false one: `attempted=0 written=0` is the shape of "the
     * write was never attempted", so a diagnosis built on this answered "upstream of
     * the store" for every failure, including the ones where the store was the
     * cause. Three explanations for the intermittent save timeout were tried and
     * refuted before anyone checked that the counters moved.
     *
     * They are incremented in [save], under [writeLock], on the path each one names.
     */
    internal fun saveAudit(): SaveAudit =
        SaveAudit(
            savesAttempted,
            savesRefusedAsDeleted,
            savesRefusedAsOlder,
            savesWritten,
            lastMoveRefusal,
        )

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        if (!root.exists()) root.mkdirs()
        // Temporary files are only ever created by a write that is in progress.
        // Anything left at construction is from a write interrupted by process
        // death, and nothing will ever move it into place.
        root.listFiles { f -> f.isFile && f.name.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }
    }

    override suspend fun list(): List<Conversation> = withContext(Dispatchers.IO) {
        var unreachable = 0
        val read = root.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }
            ?.mapNotNull { file ->
                val conversation = readOrNull(file) { unreachable++ }
                conversation
            }
            ?.sortedWith(PINNED_FIRST)
            ?: emptyList()
        unreadableCount = unreachable
        read
    }

    override suspend fun load(id: String): Conversation? = withContext(Dispatchers.IO) {
        readOrNull(fileFor(id))
    }

    /**
     * Writes [conversation] to disk.
     *
     * Safe to call concurrently. Every write goes through its own uniquely named
     * temporary file and is moved into place atomically, so the stored file is
     * always one of the versions written and never a mixture.
     *
     * It does NOT order concurrent writes. If the same conversation is saved
     * twice at once, the file ends up as whichever write lands last, which is not
     * necessarily the newest version. A caller that can overwrite itself has to
     * serialise its own saves; [com.example.demoapp.ui.chat.ChatViewModel] does.
     */
    override suspend fun save(conversation: Conversation): Unit = withContext(Dispatchers.IO) {
        writeLock.withLock {
            // Past the lock, so this call is about to be decided. Counted here and
            // not at the top of `save`: a call still waiting on `writeLock` has not
            // been attempted yet, and that is a different failure from one that was
            // decided. See [SaveAudit].
            savesAttempted++

            // Written before the delete and arriving after it. See [deletedAt].
            val removedAt = deletedAt[conversation.id]
            if (removedAt != null && conversation.updatedAt <= removedAt) {
                savesRefusedAsDeleted++
                return@withLock
            }

            // Newest wins.
            //
            // Callers launch saves asynchronously, so two can be in flight for
            // the same conversation and the lock does not promise first-come
            // order. Without this check an older version can land after a newer
            // one and revert the file -- losing, for example, the reply the user
            // just watched being generated.
            val stored = readOrNull(fileFor(conversation.id))
            if (stored != null && stored.updatedAt > conversation.updatedAt) {
                savesRefusedAsOlder++
                return@withLock
            }
            writeNow(conversation)

            // Counted after the move, so it means the file was replaced rather
            // than that a write was started. A `writeNow` that threw leaves all
            // four counts where they were and the exception reaches the caller.
            savesWritten++

            // The conversation is on disk again with content newer than the
            // deletion, so the tombstone has done its job and can go.
            if (removedAt != null) deletedAt.remove(conversation.id)
        }
    }

    private fun writeNow(conversation: Conversation) {
        val target = fileFor(conversation.id)

        // A unique name per write, not "<id>.tmp". A fixed name meant two
        // overlapping saves for the same conversation wrote into the same file
        // and raced to move it, which fails outright on Windows and silently
        // interleaves the content on Linux.
        val temp = File(root, "${conversation.id}.${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            temp.outputStream().use { out ->
                // Through the cipher. This is the file with the user's
                // conversations in it, and on a device with no screen lock the app
                // directory is readable by anything that can read the filesystem.
                out.write(FileCipher.encrypt(json.encodeToString(conversation).toByteArray()))
                // Flush to the device before the rename. Without it a power loss
                // can commit the rename before the data blocks reach the medium,
                // leaving an empty file where a valid one used to be. Process
                // death does not need this; power loss does.
                out.flush()
                out.fd.sync()
            }

            var attempt = 1
            while (true) {
                try {
                    moveFile(temp, target)
                    break
                } catch (refused: IOException) {
                    // A refusal that is not about the data. The source has been
                    // written and synced, the operation is idempotent, and whatever
                    // holds the target holds it for a moment: a few short attempts
                    // cost nothing and turn an intermittent failed save into a save.
                    // Past them the refusal is the caller's to hear about, and its
                    // own account is kept for the report. See
                    // [SaveAudit.lastMoveRefusal].
                    if (attempt >= MOVE_ATTEMPTS) {
                        lastMoveRefusal = describeRefusal(refused, attempt)
                        throw refused
                    }
                    Thread.sleep(MOVE_RETRY_MILLIS[attempt - 1])
                    attempt++
                }
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        // Under the same lock as writes. A delete racing a save could otherwise
        // be undone by that save's move, and the conversation would reappear.
        writeLock.withLock {
            val file = fileFor(id)
            // Overwritten before it is unlinked, and the limits of that are
            // recorded in SecureDelete rather than promised here. It is not a
            // guarantee; it is the part that costs nothing and helps on some
            // devices.
            if (!SecureDelete.delete(file)) {
                throw IOException("Could not delete ${file.name}")
            }
            // Recorded even when there was no file, so a save queued for a
            // conversation that was never written is also refused rather than
            // creating it after the user removed it.
            deletedAt[id] = System.currentTimeMillis()
        }
    }

    override suspend fun restore(conversations: List<Conversation>): RestoreReport =
        withContext(Dispatchers.IO) {
            var added = 0
            var skipped = 0
            val failed = mutableListOf<String>()

            for (conversation in conversations) {
                // Everything that can reject this conversation happens inside the
                // `runCatching`, including the two guards.
                //
                // The guards were outside it, and `fileFor` throws on an id it will
                // not turn into a filename. That made the whole restore depend on the
                // guard above it agreeing with the one inside `fileFor` -- two copies
                // of the same rule, and a backup file is external input. A file whose
                // ids trip the inner check but not the outer one took the entire
                // restore down instead of being reported as one conversation that did
                // not come back, which is the failure this whole method is built to
                // avoid: the user picked a file to get their data back, and the
                // report is what tells them what happened.
                val outcome = runCatching {
                    // Already here. Kept, not replaced: the file on the device may
                    // hold messages the backup predates, and overwriting would trade
                    // the newer conversation for the older one without asking.
                    if (fileFor(conversation.id).isFile) {
                        Outcome.Skipped
                    } else {
                        writeNow(conversation)
                        Outcome.Added
                    }
                }

                when (outcome.getOrNull()) {
                    // An id this build cannot store is a conversation it cannot
                    // restore. Named rather than dropped silently, because a user
                    // counting what came back deserves to know which one did not.
                    null -> failed += conversation.id
                    Outcome.Skipped -> skipped++
                    Outcome.Added -> added++
                }
            }

            RestoreReport(added = added, skipped = skipped, failed = failed)
        }

    /** What [restore] did with one conversation, decided inside the `runCatching`. */
    private enum class Outcome {
        Added,
        Skipped,
    }

    override suspend fun bytesUsed(): Long = withContext(Dispatchers.IO) {
        root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    override suspend fun sizes(): Map<String, Long> = withContext(Dispatchers.IO) {
        root.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }
            ?.associate { it.name.removeSuffix(EXTENSION) to it.length() }
            ?: emptyMap()
    }

    private fun fileFor(id: String): File {
        // Ids are generated by the app, not supplied by a user, but a name that
        // escapes the directory would write outside app storage.
        require(SAFE_ID.matches(id)) { "Unsafe conversation id: $id" }
        return File(root, "$id$EXTENSION")
    }

    private fun readOrNull(file: File, onUnreadable: () -> Unit = {}): Conversation? = runCatching {
        // Through the cipher when it is ciphertext, and as it stands when it is a
        // file written before the cipher existed. Null when the marker is there and
        // the key is gone: showing the raw ciphertext as a conversation would be
        // worse than showing nothing, and an empty conversation would look like the
        // messages had been deleted.
        //
        // The callback is what keeps that from being the last word. Without it a
        // conversation whose key is gone simply stops existing, and "the messages
        // were deleted" is what the user is left to conclude -- which is the
        // outcome the paragraph above says is worse than showing nothing.
        val text = if (file.isFile) FileCipher.read(file, onUnreadable)?.decodeToString() else null
        if (text != null) json.decodeFromString<Conversation>(text) else null
    }.getOrNull()

    /**
     * Internal rather than private so the tests can assert against the schedule
     * instead of a copy of its numbers; nothing outside the module sees it.
     */
    internal companion object {
        const val EXTENSION = ".json"
        const val TEMP_SUFFIX = ".tmp"

        /**
         * The waits before each retry of a refused move, in milliseconds.
         *
         * The list is the schedule and the count at once: one attempt, then one wait
         * per entry. The waits grow because what refuses the move is another process
         * -- a virus scanner that has just seen the file closed, a search indexer --
         * and it does not let go on a fixed schedule. The first version waited 20 ms
         * three times; a stress batch on 2026-10-15 caught a refusal that outlived all
         * three, a batch of sixty caught one that outlived six, and a batch of a
         * hundred and fifty caught one that outlived seven, so the window now runs
         * past three seconds. The first waits stay short so the ordinary case is not
         * slowed down by the rare one.
         *
         * Widening is a guess, and the measurements say it is a partly right one: the
         * rate fell from three in forty to one in sixty to one in a hundred and fifty
         * as the window grew, which is the shape of a refuser that lets go after a
         * while. The part that stops it being a guess is
         * [SaveAudit.lastMoveRefusal]: every widening before that was done without
         * knowing what the operating system objected to, because the failure the
         * caller sees carries no reason. See the entry on `c80b15cb` in
         * `docs/OPEN-WIRING.md`.
         *
         * What refuses the move in practice is a Windows host running this suite under
         * a Gradle daemon with a scanner watching the tree; the same code on a device's
         * own file system has never been seen to refuse. That is also why the last
         * resort stays a reported failure rather than a copy over the target: a torn
         * file reads back as an unreadable conversation, which is worse than a save
         * that says it failed.
         */
        val MOVE_RETRY_MILLIS = longArrayOf(10L, 25L, 50L, 100L, 200L, 400L, 800L, 1600L)

        /** How many times a refused move is tried before the save is reported as failed. */
        val MOVE_ATTEMPTS = MOVE_RETRY_MILLIS.size + 1

        /** Ids are generated by the app. This rejects anything carrying a path separator or a dot. */
        val SAFE_ID = Regex("^[A-Za-z0-9-]{1,64}$")

        /**
         * Pinned conversations first, oldest pin first; everything else by when it
         * was last used.
         *
         * Two orderings rather than one, because pinning means something specific:
         * this one is not to be lost in the list. Within the pinned group the order
         * is by when they were pinned, so the arrangement does not reshuffle every
         * time a pinned conversation is opened — a list that rearranges itself
         * under the finger is worse than one in the wrong order.
         */
        val PINNED_FIRST: Comparator<Conversation> =
            // Archived last, then pinned first, then most recent.
            //
            // Archived last rather than hidden: a conversation that vanished from
            // the list when it was put away would be indistinguishable from one
            // that was deleted, and the user would have no way to tell which they
            // had done.
            compareBy<Conversation> { it.archivedAt != null }
                .thenByDescending { it.pinnedAt != null }
                .thenBy { it.pinnedAt ?: 0L }
                .thenByDescending { it.updatedAt }
    }
}

/**
 * Replaces [to] with [from], atomically where the filesystem supports it.
 *
 * `ATOMIC_MOVE` is a request rather than a guarantee. Falling back keeps saving
 * possible on a filesystem that cannot rename atomically instead of failing every
 * write from then on.
 *
 * Top level rather than a member, because it is the default for a constructor
 * parameter and a primary constructor's default arguments cannot name an instance
 * member.
 */
private fun replaceFile(from: File, to: File) {
    try {
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * What refused the move, in the terms the retry schedule is guessing about.
 *
 * The class is named rather than implied. A `FileSystemException` from a Windows
 * host is a sharing violation as often as it is anything else, and `reason` is
 * the only field that says which of them it was; the message the caller ends up
 * with does not always carry it. A refusal with no reason is recorded as its own
 * answer, because "the operating system did not say" and "we never looked" are
 * different facts about the same failure -- and the three widenings of
 * [FileChatStore.MOVE_RETRY_MILLIS] before this function existed were made without
 * telling them apart.
 *
 * [attempts] is what the loop used, and the waits it slept are summed from the
 * same schedule it read, so the report cannot describe a window other than the
 * one that was actually waited.
 */
private fun describeRefusal(refused: IOException, attempts: Int): String {
    val waited = FileChatStore.MOVE_RETRY_MILLIS.take(attempts - 1).sum()
    val reason = (refused as? java.nio.file.FileSystemException)?.reason
    return "${refused.javaClass.simpleName} reason=${reason ?: "none given"} " +
        "after $attempts attempts in ${waited}ms"
}
