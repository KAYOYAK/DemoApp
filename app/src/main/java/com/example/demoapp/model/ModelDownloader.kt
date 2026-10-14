package com.example.demoapp.model



import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Progress of a model download. */
sealed interface DownloadEvent {
    /** [bytesDone] and [bytesTotal] are for the whole file, not this attempt. */
    data class Progress(
        val bytesDone: Long,
        val bytesTotal: Long,
        val bytesPerSecond: Long,
        val etaSeconds: Long,
    ) : DownloadEvent

    /**
     * The bytes are all present and the digest is being computed.
     *
     * This is reported separately because it is not instant: SHA-256 over 3.6 GB
     * takes seconds, and a UI still showing "downloading" during that time looks
     * stuck at 100%.
     */
    data class Verifying(val bytesTotal: Long) : DownloadEvent

    /** The file is complete, verified and in place. */
    data object Completed : DownloadEvent

    /**
     * Terminal.
     *
     * The partial file is normally preserved so the next attempt resumes, which
     * is why the downloader does not restart from zero unless it has to. It is
     * discarded only when the bytes on disk cannot be a prefix of the wanted
     * file: an oversized partial, a range the server refuses, or content that
     * fails the digest.
     */
    data class Failed(val error: Throwable) : DownloadEvent
}

/**
 * Downloads and verifies model files.
 *
 * This is the only class in the application that makes network requests. That is
 * a deliberate constraint, not a coincidence: see docs/PRINCIPLES.md, where "the
 * network is used to download models, for nothing else" is one of the three
 * non-negotiable rules.
 *
 * The flow is cold and cancellable. Cancelling stops the transfer -- including
 * one blocked in a socket read -- and leaves the partial file on disk.
 *
 * @param store where files are written.
 * @param client the HTTP client. Callers should share one; connections and
 *   thread pools are expensive to build per download.
 */
class ModelDownloader(
    private val store: ModelStore,
    private val client: OkHttpClient,
) {

    /**
     * Downloads [model], resuming if a partial file exists.
     *
     * Never throws: every failure arrives as [DownloadEvent.Failed], so a caller
     * cannot leave a progress indicator running forever by forgetting a catch.
     */
    fun download(model: ModelDescriptor): Flow<DownloadEvent> = flow {
        try {
            if (store.isInstalled(model)) {
                emit(DownloadEvent.Completed)
                return@flow
            }

            val partial = store.partialFile(model)

            // A partial longer than the target cannot be a prefix of it.
            if (partial.isFile && partial.length() > model.sizeBytes) partial.delete()

            // A partial of exactly the right length has already been downloaded;
            // what it is missing is the digest check and the rename.
            //
            // This is not a corner case. Hashing 3.6 GB takes ten to twenty
            // seconds, and the process can be killed anywhere in that window --
            // being backgrounded is enough. Without this branch the next launch
            // would request "Range: bytes=<size>-", which is unsatisfiable, and
            // throw away a complete download.
            if (partial.isFile && partial.length() == model.sizeBytes) {
                finishAndInstall(partial, model) { emit(it) }
                return@flow
            }

            val needed = model.sizeBytes - store.bytesOnDisk(model)
            val available = store.availableBytes()
            if (needed > available) {
                emit(DownloadEvent.Failed(InsufficientStorage(model.sizeBytes, available)))
                return@flow
            }

            var lastError: Throwable? = null

            // Every URL, official first, and a failure of ANY kind moves to the
            // next one -- not only a failure to open.
            //
            // The case this exists for is a connection that succeeds and then
            // dies mid-transfer, which is how a blocked network usually behaves.
            // Failing over only on the initial connection meant the mirror was
            // never reached on exactly the network it was added for.
            for (url in model.downloadUrls) {
                try {
                    // Re-checked every attempt, not only before the loop. A
                    // previous attempt can leave a complete partial behind: the
                    // bytes are all written and the install step is what failed
                    // (a full disk stops the sync, a rename can be refused).
                    // Requesting then asks for "Range: bytes=<size>-", which the
                    // server refuses, and the recovery below would delete a
                    // complete download and fetch the same gigabytes again.
                    if (partial.isFile && partial.length() == model.sizeBytes) {
                        finishAndInstall(partial, model) { emit(it) }
                        return@flow
                    }

                    // Re-read the offset for each attempt. A previous attempt may
                    // have deleted the partial, and resuming from a stale offset
                    // would pad the file with zeros and fail the digest after a
                    // whole transfer had been spent.
                    val offset = if (partial.isFile) partial.length() else 0L
                    val opened = openFrom(url, model, partial, offset)
                    opened.body.use { body ->
                        writeTo(body, partial, model, opened.offset) { emit(it) }
                    }
                    finishAndInstall(partial, model) { emit(it) }
                    return@flow
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t
                }
            }

            emit(
                DownloadEvent.Failed(
                    lastError ?: IOException("No download URL is configured for ${model.displayName}")
                )
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            emit(DownloadEvent.Failed(t))
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------- internals ---

    private class Opened(val body: ResponseBody, val offset: Long)

    /**
     * Issues one request, asking the server to continue from [existing].
     *
     * The server is not obliged to honour a Range request: a 200 means it sent
     * the whole file from the beginning, and 416 means the requested range is
     * already satisfied or invalid. Both are handled rather than assumed away,
     * because a wrong assumption here corrupts the file silently.
     */
    private suspend fun openFrom(
        url: String,
        model: ModelDescriptor,
        partial: File,
        existing: Long,
    ): Opened {
        var response = request(url, existing).await()
        var offset = existing

        when {
            response.code == 416 -> {
                // The range was refused, so the server will not extend this
                // partial. Discard it and fetch the whole file -- unless it is
                // already the whole file, which happens when the transfer
                // finished and the install step failed afterwards (a full disk
                // stops the sync, a rename can be refused). Deleting it then
                // throws away a complete, verified-size download and pulls the
                // same gigabytes again, which is the exact waste the recovery
                // above exists to avoid.
                response.close()
                if (partial.length() >= model.sizeBytes) {
                    throw IOException(
                        "Range refused but the partial is already complete; retrying the install"
                    )
                }
                partial.delete()
                offset = 0L
                response = request(url, 0L).await()
            }

            response.code == 200 && existing > 0L -> {
                // Range ignored: the body is the entire file, not a remainder.
                // Appending it to the existing bytes would corrupt the result.
                offset = 0L
            }

            response.code == 206 && existing > 0L -> {
                // Confirm the server resumed where it was asked to. A 206 that
                // starts anywhere else would be appended at the wrong offset,
                // producing a file of the right length and the wrong contents --
                // caught only by the digest, after the whole transfer.
                val startedAt = response.header("Content-Range")
                    ?.substringAfter("bytes ")
                    ?.substringBefore("-")
                    ?.trim()
                    ?.toLongOrNull()
                if (startedAt != null && startedAt != existing) {
                    response.close()
                    throw IOException("Server resumed at byte $startedAt, not $existing")
                }
            }
        }

        if (!response.isSuccessful) {
            val code = response.code
            val host = url.substringAfter("://").substringBefore("/")
            response.close()
            throw IOException("Server returned HTTP $code from $host")
        }

        return Opened(response.body, offset)
    }

    private fun request(url: String, offset: Long): Call =
        client.newCall(
            Request.Builder()
                .url(url)
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                .build()
        )

    /**
     * Awaits a call, cancelling the socket when the collector is cancelled.
     *
     * Without this, the blocking `execute()` is not interruptible: cancelling
     * the coroutine would leave the transfer running to completion in the
     * background, holding the radio open and writing to a file nothing tracks
     * any more.
     */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        val response = try {
            execute()
        } catch (t: Throwable) {
            continuation.resumeWithException(t)
            return@suspendCancellableCoroutine
        }
        // Handed over only while the continuation is still live. Resuming a
        // cancelled continuation does nothing, and cancel() is a no-op for a call
        // that has already produced its response -- so the body would be
        // delivered to nobody and its connection never returned to the pool.
        if (continuation.isActive) {
            continuation.resume(response)
        } else {
            response.close()
        }
    }

    /**
     * Streams the body to disk, reporting progress and honouring cancellation.
     *
     * Progress is throttled to roughly [PROGRESS_INTERVAL_NANOS]: emitting per
     * buffer would produce thousands of events per second and bury the UI thread
     * in recompositions that all render the same percentage.
     */
    private suspend fun writeTo(
        body: ResponseBody,
        partial: File,
        model: ModelDescriptor,
        startOffset: Long,
        emit: suspend (DownloadEvent) -> Unit,
    ) {
        partial.parentFile?.mkdirs()

        // Truncate to the agreed offset: if the server ignored the Range header,
        // writing from the start of the existing file would interleave old and
        // new bytes.
        RandomAccessFile(partial, "rw").use { out ->
            out.setLength(startOffset)
            out.seek(startOffset)

            var written = startOffset
            var lastEmit = 0L
            val startedAt = System.nanoTime()
            val buffer = ByteArray(BUFFER_BYTES)

            body.byteStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    written += read

                    val now = System.nanoTime()
                    if (now - lastEmit >= PROGRESS_INTERVAL_NANOS) {
                        lastEmit = now
                        val elapsedSeconds = ((now - startedAt) / 1_000_000_000L).coerceAtLeast(1L)
                        val rate = (written - startOffset) / elapsedSeconds
                        emit(
                            DownloadEvent.Progress(
                                bytesDone = written,
                                bytesTotal = model.sizeBytes,
                                bytesPerSecond = rate,
                                etaSeconds = if (rate > 0) (model.sizeBytes - written) / rate else -1L,
                            )
                        )
                    }
                }
            }

            // A final progress event, so a row that was throttled past the end
            // still reaches 100%.
            //
            // It reports what was actually written, which is short of the model
            // size whenever the body ends early -- a truncated chunked response,
            // or a 206 carrying less than the remainder. The verification that
            // follows rejects those, so the number here is a report of the
            // transfer, not a claim that the file is whole.
            //
            // Without it a progress bar driven by the throttled events would sit
            // at 97% and then jump to "verifying", which reads as a stall.
            val totalSeconds = ((System.nanoTime() - startedAt) / 1_000_000_000L).coerceAtLeast(1L)
            emit(
                DownloadEvent.Progress(
                    bytesDone = written,
                    bytesTotal = model.sizeBytes,
                    bytesPerSecond = (written - startOffset) / totalSeconds,
                    etaSeconds = 0L,
                )
            )
            out.fd.sync()
        }
    }

    /**
     * Verifies a complete partial and moves it into place.
     *
     * The digest check is the last thing between untrusted bytes and a usable
     * model, so it is deliberately not skippable. It does honour cancellation
     * while it runs: hashing takes long enough that a cancel arriving during it
     * must take effect, rather than being noticed only after the model has
     * already been installed.
     */
    private suspend fun finishAndInstall(
        partial: File,
        model: ModelDescriptor,
        emit: suspend (DownloadEvent) -> Unit,
    ) {
        emit(DownloadEvent.Verifying(model.sizeBytes))
        verify(partial, model)

        // Cancellation is checked after hashing and before the rename, so a
        // cancelled download cannot install itself. Without this the rename --
        // none of which suspends -- would complete first and the caller would
        // never learn that a model had landed.
        currentCoroutineContext().ensureActive()

        val target = store.modelFile(model)
        install(partial, target)

        emit(DownloadEvent.Completed)
    }

    /**
     * Moves the verified file into place over whatever is there.
     *
     * The target is not deleted first. It used to be — `if (target.exists())
     * target.delete()` followed by `renameTo` — and that made a crash between the
     * two calls destroy a complete, verified download with nothing to show for it:
     * the model gone, the partial gone, and the only visible outcome a transfer
     * that silently restarts from zero.
     *
     * `ATOMIC_MOVE` with `REPLACE_EXISTING` replaces the target in one step, so
     * there is no window in which neither file exists. This is what
     * [ModelImporter] already did; the two ways a model can arrive should not
     * differ in whether they can lose one.
     *
     * The fallback is for a filesystem with no atomic replace — the same fallback,
     * for the same reason, as the importer's.
     *
     * `renameTo` was not wrong about atomicity: on Android it is a `rename(2)`, and
     * it replaced the target in one step too. What it could not do was *report*
     * that as a guarantee, and the delete in front of it was needed only because a
     * `rename(2)` over an existing file is not something the two call sites could
     * agree on without a documented flag. One flag is clearer than one deletion.
     */
    private fun install(partial: File, target: File) {
        try {
            Files.move(
                partial.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Confirms the file is exactly what the catalogue says it is.
     *
     * Two checks, and both matter. The length catches a truncated download; the
     * digest catches a complete-length file with wrong contents, which is what
     * happens when a provider replaces an artifact in place and a resumed
     * download splices two different versions together.
     */
    private suspend fun verify(file: File, model: ModelDescriptor) {
        val actualLength = file.length()
        if (actualLength != model.sizeBytes) {
            throw IOException(
                "Size mismatch for ${model.displayName}: expected ${model.sizeBytes} bytes, got $actualLength"
            )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }

        val actual = digest.digest().joinToString(separator = "") { "%02x".format(it) }
        if (!actual.equals(model.sha256, ignoreCase = true)) {
            file.delete()
            throw IOException(
                "Checksum mismatch for ${model.displayName}. " +
                    "The file was deleted; download it again."
            )
        }
    }

    private companion object {
        const val BUFFER_BYTES = 256 * 1024
        const val HASH_BUFFER_BYTES = 1 shl 20

        /**
         * Nanoseconds, and compared against `System.nanoTime` rather than the wall
         * clock -- the same shape [ModelImporter] uses.
         *
         * It was `PROGRESS_INTERVAL_MS = 200L`, compared against a `nanoTime()`
         * difference. That is not 200 milliseconds, it is 200 *nanoseconds*, and a
         * loop that has just read and written a buffer cannot come back inside that:
         * the throttle never fired once, and every 256 KB of a multi-gigabyte
         * download emitted a Progress. Each one rewrote the coordinator's active
         * state, rebuilt every model row, and recomposed the list -- precisely what
         * the comment above the check says the throttle is there to prevent.
         */
        const val PROGRESS_INTERVAL_NANOS = 200_000_000L
    }
}

/** Raised before a download starts, when the device has no room for it. */
class InsufficientStorage(val required: Long, val available: Long) :
    IOException("Not enough space: need ${required / 1024 / 1024} MB, have ${available / 1024 / 1024} MB")
