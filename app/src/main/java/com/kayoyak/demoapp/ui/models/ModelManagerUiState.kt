package com.kayoyak.demoapp.ui.models

import com.kayoyak.demoapp.ui.UiMessage

import com.kayoyak.demoapp.inference.InferenceBackend
import com.kayoyak.demoapp.inference.SamplingProfile
import com.kayoyak.demoapp.model.ImportProgress
import com.kayoyak.demoapp.model.ModelDescriptor

/**
 * What the model manager shows for one catalogue entry.
 *
 * Every state here exists because a real scenario reaches it. The rules this
 * screen follows are the project's UI/UX governance ones: cover the states the
 * operation actually has rather than only the happy path, offer cancellation
 * for long work, and explain a failure with something the user can do next.
 */
sealed interface ModelStatus {

    /** Nothing on disk. */
    data object NotDownloaded : ModelStatus

    /** Bytes are arriving. */
    data class Downloading(
        val bytesDone: Long,
        val bytesTotal: Long,
        val bytesPerSecond: Long,
        /** Negative when the rate is not yet known. */
        val etaSeconds: Long,
    ) : ModelStatus {
        val fraction: Float
            get() = if (bytesTotal <= 0L) 0f else (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Bytes are all present; the digest is being checked.
     *
     * Its own state because hashing several gigabytes takes seconds. Leaving it
     * inside [Downloading] at 100% would look like a stall.
     */
    data class Verifying(val bytesTotal: Long) : ModelStatus

    /** Present, verified, usable. */
    data class Ready(val bytes: Long) : ModelStatus

    /**
     * Failed, with a message that says what to do.
     *
     * [retryable] distinguishes "try again" from "trying again will not help",
     * because offering a Retry button for a permanent condition is a way of
     * wasting the user's time. Running out of storage is retryable only after
     * something is deleted, so it is not offered as a plain retry.
     */
    data class Failed(val message: UiMessage, val retryable: Boolean) : ModelStatus

    /**
     * A download was stopped, leaving bytes on disk.
     *
     * Resumable when the server honours a Range request, which is the normal
     * case but not a promise: a server that ignores the header, or answers 416,
     * makes the downloader start again from zero. The screen therefore offers
     * Resume without claiming the bytes are kept.
     */
    data class Cancelled(val bytesDone: Long) : ModelStatus
}

/** One row of the model list. */
data class ModelRow(
    val descriptor: ModelDescriptor,
    val status: ModelStatus,
    /**
     * How this model was told to pick its words.
     *
     * On the row rather than looked up from the card, so the value shown is the one
     * the settings held when this state was built. A card reading the settings
     * itself would be a second observer with its own timing.
     */
    val samplingProfile: SamplingProfile = SamplingProfile.BALANCED,
    /**
     * Whether this model is loaded right now.
     *
     * The runtime takes sampling when it creates a conversation, so a choice made
     * while a model is loaded does not apply to it — the card says so rather than
     * letting the user change a value that appears to do nothing.
     */
    val samplingIsLive: Boolean = false,
)

/**
 * A file the user picked, before anything has been done with it.
 *
 * Held between picking and confirming because the licence question has to be
 * answered first: this app only carries models whose licence permits it, and a
 * file the user supplies has no licence record until they give one.
 */
data class ImportDraft(
    /** The document the user picked, as the platform named it. */
    val uri: String,
    /** A name safe to use as a file name, derived from what the platform supplied. */
    val fileName: String,
    /** Pre-filled from the file name; the user can change it. */
    val displayName: String,
    /** What the platform says the file weighs, or negative when it will not say. */
    val sizeBytes: Long,
)

/** Which model, if any, the user is being asked to confirm deleting. */
data class PendingDelete(
    val descriptor: ModelDescriptor,
    /** Bytes on disk for this model, complete or partial. */
    val bytesFreed: Long,
    /**
     * True when the only file present is a resumable partial.
     *
     * The confirmation has to say something different for this case. A finished
     * model frees space; a partial one frees bytes the user is about to fetch
     * again, and presenting it as recovered space would be a false claim about
     * the device.
     */
    val resumable: Boolean = false,
)

data class ModelManagerUiState(
    val rows: List<ModelRow> = emptyList(),
    /**
     * Free space, and what the models already take.
     *
     * Shown because a 3.6 GB download is not a decision a user can make without
     * knowing whether it fits. Presenting the size without the room available
     * would leave the failure to be discovered after the attempt.
     */
    val freeBytes: Long = 0L,
    val usedBytes: Long = 0L,
    /**
     * The device's total memory, or zero when it could not be read.
     *
     * On the state rather than read in the card so every card in one list judges
     * against the same number, and so a test can render the list for a device it
     * chooses rather than for whatever machine ran it.
     */
    val deviceRamBytes: Long = 0L,
    /**
     * A model the user asked for that this device may not have room for.
     *
     * A question rather than a refusal, for the reason [PendingRamWarning] records.
     */
    val ramWarning: PendingRamWarning? = null,
    /**
     * The model currently being re-read and compared with its published digest.
     *
     * A check reads the whole file, which for this app's largest model is two
     * gigabytes. Saying which one is running is the difference between a slow app
     * and an app that is working.
     */
    val verifying: String? = null,
    /**
     * What the last check found, or null when none has run.
     *
     * Null and "not run" are the same thing on purpose: there is no result to show
     * until there is one, and an empty result would read as a failure.
     */
    val verification: ModelVerification? = null,
    /** The model currently being measured, if any. */
    val benchmarking: String? = null,
    /** The last measurement, or null when none has run. */
    val benchmark: ModelBenchmark? = null,
    /**
     * The size of the file that would be removed, and what the user loses.
     *
     * [bytesFreed] is only meaningful for a finished model. A partial file is
     * resumable, so removing it frees the bytes but the user's next action is to
     * fetch them again; the dialog says so rather than presenting it as space
     * recovered.
     */
    val pendingDelete: PendingDelete? = null,
    /**
     * False until the device has been read.
     *
     * Without it the first state is indistinguishable from "nothing is installed
     * and the disk is full": every row offers a Download button for a model that
     * is already present, and the summary claims zero bytes free on a device with
     * tens of gigabytes. Pressing Download in that window is silently discarded,
     * because the coordinator sees the model is already there. The chat screen
     * carries the same flag for the same reason.
     */
    val scanned: Boolean = false,
    /**
     * Set when a delete did not happen.
     *
     * Releasing several gigabytes and seeing nothing change is indistinguishable
     * from the app ignoring the tap, so a failure has to be said out loud rather
     * than leaving the row as it was.
     */
    val deleteError: UiMessage? = null,
    /** Which processor the user has asked the model to run on. */
    val backend: InferenceBackend = InferenceBackend.AUTO,
    /** Which processor it is actually running on, once something is loaded.
     *
     * Null when nothing is loaded. Shown because the choice and the outcome are
     * different things: a device that cannot run a model on its graphics hardware
     * falls back, and a user who chose Graphics needs to see that what happened
     * was a fallback rather than a failure to notice their choice.
     */
    val backendInUse: InferenceBackend? = null,
    /** The file the user picked, waiting for the licence question to be answered. */
    val importDraft: ImportDraft? = null,
    /** How far a running import has got, or null when none is running. */
    val importStage: ImportProgress? = null,
    val importError: UiMessage? = null,
    val importReport: UiMessage? = null,
) {
    /** True while the device has not been read yet. */
    val scanning: Boolean get() = !scanned

    /** The model the chat screen would use. */
    val readyModels: List<ModelRow> get() = rows.filter { it.status is ModelStatus.Ready }

    val hasAnythingDownloaded: Boolean get() = readyModels.isNotEmpty()

    /** A download is running, so the storage figures are about to change. */
    val downloading: Boolean get() = rows.any { it.status is ModelStatus.Downloading || it.status is ModelStatus.Verifying }
}

/**
 * A load the user is being asked about, with the numbers to decide with.
 *
 * Both figures travel together so the dialog cannot show one without the other:
 * "needs 2.9 GB" is a fact the user cannot act on without knowing what they have.
 */
data class PendingRamWarning(
    val descriptor: com.kayoyak.demoapp.model.ModelDescriptor,
    val requiredBytes: Long,
    val budgetBytes: Long,
)

/**
 * The outcome of re-reading one model file.
 *
 * Carries the model's name as well as the result, because the check outlives the
 * scroll position that started it: by the time it finishes the row may be off
 * screen, and a message about "the model" with no name in it is one the user
 * cannot act on.
 */
data class ModelVerification(
    val displayName: String,
    val outcome: VerificationOutcome,
)

/** What a check found, in the terms the screen shows. */
enum class VerificationOutcome { INTACT, DAMAGED, MISSING, FAILED }

/**
 * One model's measured speed.
 *
 * Kept on the state rather than written anywhere: a measurement describes one run
 * on one device with whatever else was happening at the time, and storing it would
 * make it look like a property of the model.
 */
data class ModelBenchmark(
    val displayName: String,
    val timing: com.kayoyak.demoapp.inference.GenerationTiming,
)
