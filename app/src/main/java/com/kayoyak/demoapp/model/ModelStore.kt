package com.kayoyak.demoapp.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.io.File

/**
 * Where model files live on the device.
 *
 * Models are the largest thing this application owns: between 356 MB and 3.6 GB
 * each. That makes storage a real concern rather than an implementation detail,
 * so this class is where every question about "what is on disk" is answered,
 * and nothing else in the application touches model files directly.
 *
 * Layout:
 *
 * ```
 * <root>/
 *   <model-id>.litertlm          complete, verified, usable
 *   <model-id>.litertlm.part     in progress; resumable
 * ```
 *
 * The partial file keeps its own name rather than being hidden, so a user
 * looking at app storage can tell the difference between a model that works and
 * one that was interrupted.
 *
 * @param root directory for model files. Must be app-private: these are user
 *   data by choice, they are large, and no other application has any business
 *   reading them.
 * @param allocatableBytes how much space the platform could make available for
 *   this app, including cache it would evict. Suspending because the only real
 *   implementation asks the system, which is a binder call. Null means "use the
 *   plain filesystem figure", which is what a caller without a Context gets.
 */
class ModelStore(
    private val root: File,
    private val allocatableBytes: (suspend () -> Long)? = null,
) {

    init {
        if (!root.exists()) root.mkdirs()
    }

    /** The installed, verified file for [descriptor]. May not exist yet. */
    fun modelFile(descriptor: ModelDescriptor): File = modelFile(descriptor.fileName)

    /**
     * The file a given name resolves to. May not exist yet.
     *
     * By name rather than by descriptor, because an import has to place a file
     * before it can describe it: the size that decides whether the file counts as
     * installed is the number of bytes actually written, and that is not known
     * until the copy is done.
     */
    fun modelFile(fileName: String): File = File(root, safeName(fileName))

    /** The partially downloaded file for [descriptor]. May not exist. */
    fun partialFile(descriptor: ModelDescriptor): File =
        File(root, safeName(descriptor.fileName) + PARTIAL_SUFFIX)

    /** What is on disk for one model, read in a single pass. */
    data class ModelState(val installed: Boolean, val bytesOnDisk: Long)

    /**
     * Reads completeness and size together, so the two cannot disagree.
     *
     * Asking [isInstalled] and [bytesOnDisk] separately reads the filesystem
     * twice, and a file that appears between the two reads yields a
     * self-contradictory answer: not installed, yet holding a whole model's
     * worth of bytes. That combination reaches the user as "paused at 356 MB",
     * which is a state the device was never in.
     */
    fun stateOf(descriptor: ModelDescriptor): ModelState {
        val complete = modelFile(descriptor)
        if (complete.isFile && complete.length() == descriptor.sizeBytes) {
            return ModelState(installed = true, bytesOnDisk = descriptor.sizeBytes)
        }
        val partial = partialFile(descriptor)
        return ModelState(
            installed = false,
            bytesOnDisk = if (partial.isFile) partial.length() else 0L,
        )
    }

    /**
     * True when a complete model is present.
     *
     * Checks size, not only existence: a file that stopped short because the
     * process died between writing and renaming would otherwise be treated as
     * installed, and loading it would fail in a way that looks like a corrupt
     * model rather than an incomplete download.
     */
    fun isInstalled(descriptor: ModelDescriptor): Boolean = stateOf(descriptor).installed

    /** Bytes already downloaded for [descriptor], complete or partial. */
    fun bytesOnDisk(descriptor: ModelDescriptor): Long = stateOf(descriptor).bytesOnDisk

    /**
     * Models currently installed, in the order they were given.
     *
     * Takes the list rather than reading the catalogue, because which models
     * exist stopped being a constant the moment the user could add their own. A
     * caller that read the catalogue here would find every imported model missing
     * from the one place that decides what is usable.
     */
    fun installedModels(models: List<ModelDescriptor>): List<ModelDescriptor> =
        models.filter { isInstalled(it) }

    /** Total bytes held by model files, complete and partial. */
    fun totalBytesUsed(): Long =
        root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /**
     * Free space this app may actually use, in bytes.
     *
     * Used to refuse a download before it starts, so the number has to be one the
     * download would agree with. [File.usableSpace] is not that number: it reports
     * what is free *now*, while Android can reclaim cached files belonging to
     * other apps when a large allocation needs the room. A phone with 3 GB free
     * and a 3.6 GB model was refused -- correctly by this code's own arithmetic,
     * and wrongly for the user, who has a device that would have taken it.
     *
     * The caller supplies the better answer when it has a Context, which is where
     * `StorageManager.getAllocatableBytes` lives. Without one -- every test, and
     * any caller that has only a directory -- this falls back to the plain
     * filesystem figure, which is the behaviour this replaced.
     */
    // `UsableSpace` is suppressed here rather than satisfied, and the reason is the
    // whole point of the parameter above: `getAllocatableBytes` needs a
    // `StorageManager`, which needs a Context, and this class is deliberately
    // constructible from a directory alone. The two uses of `root.usableSpace`
    // below are both the fallback path, where asking the platform is not possible.
    // The callers that can ask do.
    @Suppress("UsableSpace")
    suspend fun availableBytes(): Long {
        val provider = allocatableBytes ?: return root.usableSpace
        // Falling back on failure rather than propagating it. The provider asks a
        // system service, and a device that will not answer is not a device with
        // no space -- the same distinction `AppSignature` draws between "not
        // signed by us" and "could not be read". A refusal here would block a
        // download the user asked for and can complete.
        return runCatching { provider() }.getOrNull() ?: root.usableSpace
    }

    /**
     * Deleting a model removes both the finished file and any partial download.
     * A partial left behind would silently consume gigabytes with nothing in the
     * UI to explain it.
     */
    fun delete(descriptor: ModelDescriptor): Boolean {
        val a = modelFile(descriptor).delete()
        val b = partialFile(descriptor).delete()
        return a || b
    }

    /** Removes every model file. Used by "clear all storage" and by tests. */
    fun deleteAll() {
        root.listFiles()?.forEach { it.delete() }
    }

    /** Root directory, for callers that need to point the runtime at it. */
    fun directory(): File = root

    /**
     * Re-reads an installed model and compares it with the digest it was published
     * under.
     *
     * Download and import already check what they wrote. This is for afterwards:
     * storage can corrupt a file long after it was verified, and so can anything
     * the user runs over the app's directory. A model damaged that way does not
     * announce itself -- it loads, answers, and answers worse -- so without this
     * the only symptom is that the app seems to have got dumber.
     *
     * Streamed rather than read whole. These files are hundreds of megabytes to
     * two gigabytes, and reading one into memory to hash it would be the app
     * proving a point by running out of memory.
     *
     * Suspends between chunks so a long check can be cancelled and does not hold a
     * thread while it runs.
     */
    suspend fun verify(
        descriptor: ModelDescriptor,
        expectedSha256: String,
    ): Verification = withContext(Dispatchers.IO) {
        val file = modelFile(descriptor)
        if (!file.isFile) return@withContext Verification.Missing

        val digest = MessageDigest.getInstance("SHA-256")
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                while (true) {
                    // Checked per chunk: a two-gigabyte read is long enough that a
                    // user who leaves the screen should not be made to wait for it.
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return@withContext Verification.Unreadable(t.message ?: t::class.simpleName.orEmpty())
        }

        val actual = digest.digest().joinToString(separator = "") { "%02x".format(it) }
        if (actual.equals(expectedSha256, ignoreCase = true)) {
            Verification.Intact
        } else {
            Verification.Damaged(expected = expectedSha256, actual = actual)
        }
    }

    /** What a check found. */
    sealed interface Verification {
        /** Matches what was published. */
        data object Intact : Verification

        /** The file is not there at all. */
        data object Missing : Verification

        /** Read, and different from what was published. */
        data class Damaged(val expected: String, val actual: String) : Verification

        /** Could not be read; [detail] says why. */
        data class Unreadable(val detail: String) : Verification
    }


    /**
     * Rejects a file name that would escape the model directory.
     *
     * File names come from the catalogue, which is code rather than remote
     * input, so this cannot currently trigger. It stays because the cost of
     * being wrong here is writing outside app storage, and the check is one
     * line.
     */
    private fun safeName(name: String): String {
        val cleaned = File(name).name
        require(cleaned == name && !name.contains("..")) {
            "Unsafe model file name: $name"
        }
        return cleaned
    }

    private companion object {
        const val PARTIAL_SUFFIX = ".part"

        /**
         * One megabyte: large enough that the read is not the cost of the
         * check, small enough to cancel between chunks.
         */
        const val HASH_BUFFER_BYTES = 1024 * 1024
    }
}
