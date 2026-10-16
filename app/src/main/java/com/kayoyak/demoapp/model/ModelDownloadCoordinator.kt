package com.kayoyak.demoapp.model

import com.kayoyak.demoapp.ui.UiMessage

import com.kayoyak.demoapp.R

import com.kayoyak.demoapp.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

/** Progress of whichever download is running, or nothing. */
sealed interface ActiveDownload {
    val modelId: String

    data class Running(
        override val modelId: String,
        val bytesDone: Long,
        val bytesTotal: Long,
        val bytesPerSecond: Long,
        val etaSeconds: Long,
    ) : ActiveDownload

    data class Verifying(override val modelId: String, val bytesTotal: Long) : ActiveDownload

    data class Failed(
        override val modelId: String,
        val message: UiMessage,
        val retryable: Boolean,
    ) : ActiveDownload

    /** Stopped by the user. The partial file is kept, so this is resumable. */
    data class Cancelled(override val modelId: String, val bytesDone: Long) : ActiveDownload
}

/**
 * Runs model downloads at application scope.
 *
 * Deliberately not owned by a ViewModel. A model is up to 3.6 GB; a download
 * that dies because the user opened another screen would be unusable, and one
 * that restarted from zero each time would be worse. The coordinator outlives
 * any screen and the screens observe it.
 *
 * One download at a time. Two concurrent multi-gigabyte transfers compete for
 * the same connection and the same free space, and a user who starts three has a
 * worse experience than one who starts one.
 *
 * That guarantee is enforced against the job's COMPLETION, not merely whether it
 * is still active. A cancelled coroutine is no longer active but is still
 * running: it can sit inside a socket read for as long as the timeout allows.
 * Starting a second transfer in that window would give two writers the same
 * `.part` file.
 */
class ModelDownloadCoordinator(
    private val scope: CoroutineScope,
    private val store: ModelStore,
    /**
     * Where bytes come from.
     *
     * A function rather than [ModelDownloader] itself so the state machine can
     * be exercised without a server. Production passes `downloader::download`.
     */
    private val download: (ModelDescriptor) -> Flow<DownloadEvent>,
    /** Used only to name a model whose bytes are being reported; see [cancel]. */
    private val models: List<ModelDescriptor> = ModelCatalogue.models,
) {

    private val _active = MutableStateFlow<ActiveDownload?>(null)
    val active: StateFlow<ActiveDownload?> = _active.asStateFlow()

    /** Emitted after a file lands or is deleted, so observers can re-read the store. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /**
     * The transfer that is running, and which model it belongs to.
     *
     * One field for both, because the two must agree. They used to be separate:
     * the job lived in a guarded field and the model id was read back out of
     * [active], which is the DISPLAY state and is not written until the first
     * progress event -- seconds after the transfer begins, once DNS, connect,
     * TLS, the response headers and the first chunk are done. For that whole
     * window the two disagreed, and [cancel] and [delete] act on the job while
     * naming the model from the display state.
     */
    private class Running(val modelId: String, val job: Job)

    /** Guarded by [lock]. Read and written from any thread. */
    private var running: Running? = null
    private val lock = Any()

    /**
     * True while a transfer is actually moving bytes.
     *
     * `isActive` is false as soon as cancellation is requested, even though the
     * coroutine is still running: it can sit in a socket read until the timeout
     * expires. That is why [start] joins a cancelled predecessor rather than
     * assuming it has stopped.
     */
    val isRunning: Boolean
        get() = synchronized(lock) { running?.job?.isActive == true }

    /**
     * Starts downloading [model], or resumes a partial one.
     *
     * Ignored while another download is actually running. If the previous one was
     * cancelled but has not finished stopping, the new one waits for it rather
     * than sharing the `.part` file with it.
     */
    fun start(model: ModelDescriptor) {
        if (store.isInstalled(model)) return
        // Nothing to fetch. Starting anyway would put the row into a downloading
        // state and fail on the first request, which looks like a broken app
        // rather than a file that is simply not there.
        if (model.downloadUrls.isEmpty()) return

        // Filled in by the critical section below, before the job is allowed to
        // run. The job reads it, so it cannot be a local captured at launch time.
        val abandoned = AtomicReference<Job?>(null)

        val launched = scope.launch(start = CoroutineStart.LAZY) {
            // Never overlap two transfers. A cancelled predecessor can still be
            // inside a socket read for as long as its timeout allows.
            abandoned.get()?.join()
            download(model)
                .onEach { event ->
                    _active.value = when (event) {
                        is DownloadEvent.Progress -> ActiveDownload.Running(
                            modelId = model.id,
                            bytesDone = event.bytesDone,
                            bytesTotal = event.bytesTotal,
                            bytesPerSecond = event.bytesPerSecond,
                            etaSeconds = event.etaSeconds,
                        )

                        is DownloadEvent.Verifying ->
                            ActiveDownload.Verifying(model.id, event.bytesTotal)

                        DownloadEvent.Completed -> {
                            _changes.update { it + 1 }
                            null
                        }

                        is DownloadEvent.Failed ->
                            ActiveDownload.Failed(model.id, describe(event.error), isRetryable(event.error))
                    }
                }
                .collect { }
        }

        // One critical section for the check, the record and the display update.
        // Checking in one block and recording in another let two callers both see
        // no running transfer and both start one, writing the same `.part` file
        // from two offsets; only one of the two was then reachable by cancel or
        // delete, so the other downloaded to completion unseen.
        val previous: Job?
        synchronized(lock) {
            val current = running
            if (current?.job?.isActive == true) {
                launched.cancel()
                return
            }
            previous = current?.job
            running = Running(model.id, launched)
            // The display state is set here, not when the first byte arrives.
            // Leaving it stale for the connect window made the screen offer
            // "Resume" for a transfer that was already running.
            _active.value = ActiveDownload.Running(
                modelId = model.id,
                bytesDone = store.bytesOnDisk(model),
                bytesTotal = model.sizeBytes,
                bytesPerSecond = -1L,
                etaSeconds = -1L,
            )
        }

        // Handed to the job before it can run, so its first act is to wait for a
        // predecessor that is still winding down.
        abandoned.set(previous)
        launched.start()
    }

    /**
     * Stops the running download, leaving the partial file in place.
     *
     * The paused state is reported only once the transfer has actually stopped,
     * so the byte count shown is what is genuinely on disk. Reporting it up front
     * would show a figure that is still growing.
     */
    fun cancel() {
        // Both taken from the same guarded field, so the model named and the
        // transfer stopped cannot be different ones.
        val stopped = synchronized(lock) { running } ?: return
        stopped.job.cancel()

        scope.launch {
            stopped.job.join()
            // Only when the cancellation is what ended it. A transfer that
            // finished or failed while this was waiting has already reported
            // that, and overwriting it would claim a paused download for a model
            // that is installed, or replace the reason it failed.
            if (!stopped.job.isCancelled) return@launch

            val model = models.firstOrNull { it.id == stopped.modelId }
            _active.value = ActiveDownload.Cancelled(
                modelId = stopped.modelId,
                bytesDone = model?.let { store.bytesOnDisk(it) } ?: 0L,
            )
            _changes.update { it + 1 }
        }
    }

    /** Clears a terminal message so the row returns to its resting state. */
    fun dismiss() {
        // Compared and cleared as one action. Reading and then writing let a
        // failure written in between be discarded instead of shown.
        _active.update { current ->
            if (current is ActiveDownload.Failed || current is ActiveDownload.Cancelled) null else current
        }
    }

    /**
     * Deletes a model and anything partially downloaded for it.
     *
     * Stops a running download of the same model first. Deleting a file that a
     * transfer still holds open does not stop the transfer: on Android the
     * unlinked file stays alive while the handle exists, so the app would keep
     * pulling the whole remaining file over the network into an inode nothing can
     * reach, while reporting the model as deleted.
     *
     * @return true when the model is gone afterwards. False means a file is still
     *   there, and the caller has to say so: asking the user to confirm deleting
     *   several gigabytes and then doing nothing looks like the app ignoring the
     *   tap.
     */
    suspend fun delete(model: ModelDescriptor): Boolean {
        // The transfer's own model id, not the display state's. Reading it from
        // [active] cancelled whichever job happened to be running whenever the
        // display still named this model -- which, during the seconds before the
        // first progress event, meant cancelling a different model's download
        // entirely, and meant not cancelling at all when the display named
        // nothing while a transfer for this very model was live.
        synchronized(lock) { running }
            ?.takeIf { it.modelId == model.id }
            ?.job
            ?.cancelAndJoin()

        val removed = store.delete(model)

        // A terminal message about this model is now false: the file it describes
        // is gone. Without this the row keeps offering "Resume" for a model with
        // nothing on disk.
        _active.update { current -> if (current?.modelId == model.id) null else current }
        _changes.update { it + 1 }

        // "Nothing was removed" is not the same as "it is still there". A model
        // with nothing on disk is the state the caller asked for, and reporting
        // that as a failure raises an alarm about a delete that did exactly what
        // was wanted.
        return removed || !hasAnythingLeft(model)
    }

    /** True when any file at all remains for [model]. */
    private fun hasAnythingLeft(model: ModelDescriptor): Boolean =
        store.isInstalled(model) || store.bytesOnDisk(model) > 0L

    /**
     * Turns an exception into something a user can act on.
     *
     * `IOException` tells the user nothing. Each branch below names the situation
     * and what to do about it, which is the difference between a failure the user
     * can recover from and one they can only report.
     *
     * The sentence comes from the string resources and the exception's own text
     * goes into `detail`, untranslated. Those are two different things and
     * combining them produces neither: a Chinese sentence with an English stack
     * trace spliced into it reads as a bug, and the raw text is what someone
     * actually needs to report the problem.
     */
    private fun describe(error: Throwable): UiMessage = when {
        error is InsufficientStorage ->
            UiMessage(
                R.string.err_download_no_space,
                formatBytes(error.required),
                formatBytes(error.available),
            )

        error.message?.contains("Checksum mismatch") == true ->
            UiMessage(R.string.err_download_checksum).withDetail(error.message)

        error.message?.contains("Size mismatch") == true ->
            UiMessage(R.string.err_download_incomplete).withDetail(error.message)

        else -> UiMessage(R.string.err_download_failed)
            .withDetail(error.message ?: error::class.simpleName)
    }

    private fun isRetryable(error: Throwable): Boolean = when (error) {
        // Retryable only after the user frees space, so it gets its own wording
        // above rather than a plain Retry that would fail identically.
        is InsufficientStorage -> false
        else -> true
    }
}
