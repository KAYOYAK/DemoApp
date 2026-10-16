package com.kayoyak.demoapp.ui.models

import com.kayoyak.demoapp.ui.UiMessage

import com.kayoyak.demoapp.R


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayoyak.demoapp.data.settings.Settings
import com.kayoyak.demoapp.data.settings.SettingsStore
import com.kayoyak.demoapp.inference.EngineState
import com.kayoyak.demoapp.inference.InferenceBackend
import com.kayoyak.demoapp.inference.SamplingProfile
import com.kayoyak.demoapp.inference.inferenceBackendNamed
import com.kayoyak.demoapp.inference.GenerationConfig
import com.kayoyak.demoapp.inference.GenerationEvent
import com.kayoyak.demoapp.inference.GenerationTiming
import com.kayoyak.demoapp.inference.LocalModel
import com.kayoyak.demoapp.inference.LlmEngine
import com.kayoyak.demoapp.model.ActiveDownload
import com.kayoyak.demoapp.model.ModelCatalogue
import com.kayoyak.demoapp.model.ImportProgress
import com.kayoyak.demoapp.model.ImportedModel
import com.kayoyak.demoapp.model.InMemoryModelRegistry
import com.kayoyak.demoapp.model.ModelImporter
import com.kayoyak.demoapp.model.ModelLicense
import com.kayoyak.demoapp.model.ModelRegistry
import com.kayoyak.demoapp.model.ModelDescriptor
import com.kayoyak.demoapp.model.ModelDownloadCoordinator
import com.kayoyak.demoapp.model.DeviceMemory
import com.kayoyak.demoapp.model.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Everything the screen needs that comes from the filesystem.
 *
 * Read once per change rather than per frame. The download coordinator reports
 * progress roughly every 200 ms, and re-stating every model and listing the
 * model directory on each of those would put a directory walk on the main
 * thread five times a second for a number that has not changed.
 */
private data class StoreSnapshot(
    val installed: Set<String>,
    val bytesOnDisk: Map<String, Long>,
    val freeBytes: Long,
    val usedBytes: Long,
) {
    companion object {
        val EMPTY = StoreSnapshot(emptySet(), emptyMap(), 0L, 0L)
    }
}

/**
 * Drives the model manager screen.
 *
 * Holds no download state of its own: the work belongs to
 * [ModelDownloadCoordinator] so it survives navigation, and this class only
 * turns that state plus the contents of the store into something renderable.
 */
class ModelManagerViewModel(
    private val store: ModelStore,
    private val coordinator: ModelDownloadCoordinator,
    /** The user's choices, and the engine that is running on them. */
    private val settingsStore: SettingsStore? = null,
    private val engine: LlmEngine? = null,
    /** Which models exist. Defaults to the catalogue for a caller with no container. */
    private val registry: ModelRegistry = InMemoryModelRegistry(),
    /**
     * How much memory the device has, for telling the user whether a model fits.
     *
     * Defaults to zero, which means "not known": no line is drawn rather than one
     * claiming nothing fits. A caller with no device -- a test -- gets that.
     */
    private val deviceMemory: DeviceMemory = DeviceMemory(0L),
    /** How the file the user picked is opened; null in a test, which supplies a stream instead. */
    private val openPicked: ((String) -> java.io.InputStream)? = null,
    private val importer: ModelImporter? = null,
) : ViewModel() {

    private val pendingDelete = MutableStateFlow<PendingDelete?>(null)

    /**
     * A model the user picked that this device may not have room for.
     *
     * Asked rather than refused. The memory figure is an estimate, the device's
     * free memory is not read at all, and someone who has just closed everything
     * else knows more about the moment than this does. Refusing outright would
     * take away a choice they can reasonably make; saying nothing would let the
     * app vanish with no explanation. The platform's own Android reference client
     * made the same call.
     */
    /**
     * Re-reads an installed model and compares it with what was published.
     *
     * Download and import already check what they wrote. This is for afterwards:
     * storage corrupts files long after they were verified, and so does anything
     * the user runs over the app's directory. A model damaged that way loads,
     * answers, and answers worse -- the only symptom is that the app seems to have
     * got dumber, which is a symptom with no next step. This is the next step.
     */
    fun verifyModel(descriptor: ModelDescriptor) {
        if (verifying.value != null) return
        verifying.value = descriptor.id
        verification.value = null
        viewModelScope.launch {
            val outcome = try {
                when (store.verify(descriptor, descriptor.sha256)) {
                    ModelStore.Verification.Intact -> VerificationOutcome.INTACT
                    is ModelStore.Verification.Damaged -> VerificationOutcome.DAMAGED
                    ModelStore.Verification.Missing -> VerificationOutcome.MISSING
                    is ModelStore.Verification.Unreadable -> VerificationOutcome.FAILED
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                verifying.value = null
                throw e
            } catch (t: Throwable) {
                VerificationOutcome.FAILED
            }
            verifying.value = null
            verification.value = ModelVerification(descriptor.displayName, outcome)
        }
    }

    /**
     * Measures how fast a model runs on this device.
     *
     * [prompt] is passed in rather than held here because it is user-visible text,
     * and this project keeps every word people read in one place. It is the same
     * prompt for every model, so the numbers can be compared with each other.
     *
     * The same prompt for every model, so the numbers can be compared with each
     * other -- a per-model prompt would measure the prompt as much as the model.
     *
     * The engine is left loaded afterwards rather than unloaded, because the
     * obvious next thing to do after a benchmark is to talk to the model.
     *
     * Nothing is written down. A measurement describes one run on one device with
     * whatever else was happening at the time, and a stored number would be read
     * later as a property of the model.
     */
    fun runBenchmark(descriptor: ModelDescriptor, prompt: String) {
        val engine = engine ?: return
        if (benchmarking.value != null) return

        benchmarking.value = descriptor.id
        benchmark.value = null
        viewModelScope.launch {
            val result = try {
                val model = LocalModel(
                    id = descriptor.id,
                    displayName = descriptor.displayName,
                    file = store.modelFile(descriptor),
                )
                // AUTO rather than a pinned backend: a benchmark that forced a
                // backend would measure a configuration the user is not running.
                engine.load(model).getOrThrow()

                // A fixed, short, ordinary question. Long enough that the numbers
                // are not dominated by start-up, short enough that running it on
                // four models is not a chore.
                var timing: GenerationTiming? = null
                engine.generate(
                    prompt,
                    // A fixed length and no sampling surprises: this measures speed.
                    GenerationConfig(maxTokens = BENCHMARK_TOKENS, temperature = 0.0),
                ).collect { event ->
                    if (event is GenerationEvent.Done) timing = event.timing
                }
                timing
            } catch (e: CancellationException) {
                benchmarking.value = null
                throw e
            } catch (t: Throwable) {
                null
            }

            benchmarking.value = null
            benchmark.value = result?.let { ModelBenchmark(descriptor.displayName, it) }
        }
    }

    /** No measurement has run, or the last one is being dismissed. */
    fun dismissBenchmark() {
        benchmark.value = null
    }
    /** The result has been read. */
    fun dismissVerification() {
        verification.value = null
    }
    private val pendingRamWarning = MutableStateFlow<PendingRamWarning?>(null)

    /** The model currently being re-read, and the last result. */
    private val verifying = MutableStateFlow<String?>(null)
    private val verification = MutableStateFlow<ModelVerification?>(null)

    /** The model currently being measured, and the last measurement. */
    private val benchmarking = MutableStateFlow<String?>(null)
    private val benchmark = MutableStateFlow<ModelBenchmark?>(null)
    private val snapshot = MutableStateFlow(StoreSnapshot.EMPTY)
    private val deleteError = MutableStateFlow<UiMessage?>(null)

    /** The file the user picked, waiting for the licence question to be answered. */
    /** The running copy, so Stop can stop it rather than only hiding it. */
    private var importJob: Job? = null

    /** The file the user picked, waiting for the licence question to be answered. */
    private val importDraft = MutableStateFlow<ImportDraft?>(null)
    /** How far the copy has got, or null when nothing is being imported. */
    private val importStage = MutableStateFlow<ImportProgress?>(null)
    private val importError = MutableStateFlow<UiMessage?>(null)
    private val importReport = MutableStateFlow<UiMessage?>(null)

    /** Serialises snapshot reads; see [refreshSnapshot]. */
    private val snapshotLock = Mutex()

    /**
     * The backend the user chose, and — separately — the settings themselves.
     *
     * The settings ride along because the per-model sampling choice is read from
     * them by model id, and the card that offers the choice is built from a row.
     * Carrying the whole file beats a second copy of the map in the row, which
     * would have to be kept in step with the one on disk.
     *
     * Its own `combine` rather than folded into the state's, because that one is
     * already at `combine`'s limit of five flows.
     */
    private data class Compute(
        val chosen: InferenceBackend,
        val inUse: InferenceBackend?,
        val settings: Settings,
        /** Which model is loaded, so a card can say whether its own choice is live. */
        val loadedModelId: String?,
    )

    /**
     * Everything the screen reports rather than displays as a record.
     *
     * Gathered into one flow because `combine` takes at most five, and these
     * five are all the same kind of thing: a message about something that just
     * happened, or a question waiting to be answered.
     */
    private data class Notices(
        val deleteError: UiMessage? = null,
        val importDraft: ImportDraft? = null,
        val importStage: ImportProgress? = null,
        val importError: UiMessage? = null,
        val importReport: UiMessage? = null,
    )

    private val notices: Flow<Notices> = combine(
        deleteError,
        importDraft,
        importStage,
        importError,
        importReport,
    ) { error, draft, stage, importError, report ->
        Notices(error, draft, stage, importError, report)
    }

    /**
     * The two operations that run for a while and then report a result: checking a
     * model's integrity, and benchmarking one.
     *
     * This exists because neither pair was in the state's `combine`, and that was a
     * bug rather than a tidiness question. `buildState` read `verifying`,
     * `verification`, `benchmarking` and `benchmark` off the instance, so writing to
     * one of them changed nothing that the screen observes -- the four fields were
     * collected into the state only when something *else* happened to recompute it.
     *
     * What that looked like depends on which flow moved. `dismissVerification` and
     * `dismissBenchmark` write a field and nothing else, so the card they are meant
     * to take off the screen stayed on it. The benchmark's result appeared only
     * because generating a reply moves the engine's state; a benchmark against a
     * model that was already loaded and produced no state change would have run to
     * completion and shown nothing at all.
     *
     * Its own `combine` rather than folded into the state's, for the reason the
     * `Notices` comment gives: that one is already at `combine`'s limit of five.
     */
    private data class Tooling(
        /** The id of the model being checked, or null. */
        val verifying: String? = null,
        val verification: ModelVerification? = null,
        /** The id of the model being benchmarked, or null. */
        val benchmarking: String? = null,
        val benchmark: ModelBenchmark? = null,
    )

    private val tooling: Flow<Tooling> = combine(
        verifying,
        verification,
        benchmarking,
        benchmark,
    ) { checking, outcome, running, measured ->
        Tooling(checking, outcome, running, measured)
    }

    private val compute: Flow<Compute> = combine(
        settingsStore?.settings ?: MutableStateFlow(Settings()),
        engine?.state ?: MutableStateFlow(EngineState.Idle),
    ) { settings, engineState ->
        Compute(
            chosen = inferenceBackendNamed(settings.backend),
            inUse = (engineState as? EngineState.Ready)?.backendInUse,
            settings = settings,
            loadedModelId = (engineState as? EngineState.Ready)?.modelId,
        )
    }

    /**
     * The five inputs the state is built from, in one typed value.
     *
     * This exists to get around `combine`'s arity, honestly. It is overloaded for one
     * through five flows with a typed lambda; past five it takes a vararg and hands
     * the lambda an `Array<Any?>`, which would mean casts and index arithmetic for no
     * gain. Combining five here and folding the sixth in below keeps every lambda in
     * this chain typed.
     */
    private data class Inputs(
        val active: ActiveDownload?,
        val disk: StoreSnapshot,
        val pending: PendingDelete?,
        val compute: Compute,
        val notices: Notices,
    )

    private val inputs: Flow<Inputs> = combine(
        coordinator.active,
        snapshot,
        pendingDelete,
        compute,
        notices,
    ) { active, disk, pending, compute, notices ->
        Inputs(active, disk, pending, compute, notices)
    }

    val state: StateFlow<ModelManagerUiState> =
        combine(inputs, tooling) { input, tooling ->
            buildState(input, tooling)
        }.stateIn(
            scope = viewModelScope,
            // Keeps the last value through a configuration change without
            // keeping the store query alive while nothing is observing it.
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = buildState(
                Inputs(
                    active = coordinator.active.value,
                    disk = StoreSnapshot.EMPTY,
                    pending = null,
                    compute = Compute(
                        chosen = settingsStore?.settings?.value?.let { inferenceBackendNamed(it.backend) }
                            ?: InferenceBackend.AUTO,
                        inUse = (engine?.state?.value as? EngineState.Ready)?.backendInUse,
                        settings = settingsStore?.settings?.value ?: Settings(),
                        loadedModelId = (engine?.state?.value as? EngineState.Ready)?.modelId,
                    ),
                    notices = Notices(),
                ),
                Tooling(),
            ),
        )

    init {
        viewModelScope.launch {
            refreshSnapshot()
            // The only events that can change what is on disk.
            coordinator.changes.collect { refreshSnapshot() }
        }
    }

    /** Starts or resumes a download. Ignored while another is running. */
    fun download(model: ModelDescriptor) {
        coordinator.dismiss()
        coordinator.start(model)
    }

    /** Stops the running download. The partial file is kept. */
    fun cancelDownload() = coordinator.cancel()

    /** Clears a failure or cancellation message from the row. */
    fun dismissMessage() = coordinator.dismiss()

    // --------------------------------------------------------- deletion ---

    /**
     * Deleting a model costs the user the whole download again -- up to 3.6 GB
     * of their data allowance. That is enough of a consequence to warrant one
     * confirmation step rather than acting on a single tap.
     *
     * The confirmation distinguishes a finished model from a stopped download,
     * because the two free different things: the first frees the space, the
     * second frees bytes the user is about to fetch again.
     */
    fun requestDelete(model: ModelDescriptor) {
        // From the snapshot, not a fresh stat: this is a click handler on the
        // main thread.
        val bytes = snapshot.value.bytesOnDisk[model.id] ?: 0L
        val complete = model.id in snapshot.value.installed
        pendingDelete.value = PendingDelete(
            descriptor = model,
            bytesFreed = bytes,
            // A partial file is the only way to be here without being installed.
            resumable = !complete && bytes > 0L,
        )
    }

    /**
     * Deletes the model the user confirmed.
     *
     * The result is reported. Deleting several gigabytes and having nothing
     * happen is indistinguishable from the app ignoring the tap, and the user
     * has no way to tell whether the space came back.
     */
    fun confirmDelete() {
        val pending = pendingDelete.value ?: return
        pendingDelete.value = null
        viewModelScope.launch {
            if (!coordinator.delete(pending.descriptor)) {
                deleteError.value =
                    UiMessage(R.string.err_delete_still_present, pending.descriptor.displayName)
            }
            refreshSnapshot()
        }
    }

    fun cancelDelete() {
        pendingDelete.value = null
    }

    /** Clears the message shown when a delete did not happen. */
    fun dismissDeleteError() {
        deleteError.value = null
    }

    // --------------------------------------------------------- the backend ---

    /**
     * Records which processor the model should be asked to run on.
     *
     * The choice is only read when a model is loaded, so it does not disturb one
     * that is already running. That is deliberate: reloading a multi-gigabyte
     * model because a preference changed would stop the user's conversation to
     * save nothing.
     */
    fun chooseBackend(backend: InferenceBackend) {
        viewModelScope.launch {
            settingsStore?.update { it.copy(backend = backend.name) }
        }
    }

    /**
     * Remembers how [modelId] should pick its words.
     *
     * Per model, because the thing being adjusted is a property of the model: a
     * 0.6B that repeats itself and a 2B that does not are the same screen, and one
     * global setting would make the user choose which of the two to fix.
     *
     * Takes effect on the next load. The runtime takes sampling when a conversation
     * is created and offers nothing per call, so a model that is already loaded
     * keeps the values it was loaded with — which is why the choice is stored rather
     * than applied, and why the card says what the next load will use.
     */
    fun chooseSamplingProfile(modelId: String, profile: SamplingProfile) {
        viewModelScope.launch {
            settingsStore?.update {
                it.copy(modelSampling = it.modelSampling + (modelId to profile.name))
            }
        }
    }

    // -------------------------------------------------------------- importing ---

    /**
     * Opens the form for a file the user picked.
     *
     * Nothing is copied yet. The licence question has to be answered first, and
     * it is not a formality: this app only carries models whose licence permits it,
     * and a file the user supplies has no licence record until they give one.
     * Asking afterwards would mean either refusing a file already copied onto
     * their device or keeping one that should not be there.
     */
    fun prepareImport(uri: String, fileName: String, sizeBytes: Long) {
        val name = fileName.substringAfterLast('/').ifBlank { "model.litertlm" }
        importDraft.value = ImportDraft(
            uri = uri,
            fileName = sanitiseFileName(name),
            displayName = name.substringBeforeLast('.').ifBlank { "model.litertlm" },
            sizeBytes = sizeBytes,
        )
    }

    fun cancelImport() {
        // Cancelling the job, not just clearing what is on screen. Clearing the
        // flows alone left the copy running: the next progress callback put the
        // dialog back, the picker stayed disabled, and the import finished and
        // announced itself -- under a button that says Stop.
        importJob?.cancel()
        importJob = null
        importDraft.value = null
        importStage.value = null
    }

    /**
     * Copies the file in and records it.
     *
     * The licence must be one this project accepts. A model the user cannot name a
     * licence for is one this app cannot carry, for the same reason none of the
     * catalogue's entries are under a licence that forbids redistribution.
     */
    fun confirmImport(displayName: String, licenseId: String, licenseSourceUrl: String) {
        val draft = importDraft.value ?: return
        val name = displayName.trim().ifBlank { draft.displayName }
        val source = licenseSourceUrl.trim()

        if (licenseId !in ModelCatalogue.ACCEPTED_LICENSES) {
            importError.value = UiMessage(R.string.err_import_need_licence)
            return
        }
        if (source.isBlank()) {
            importError.value = UiMessage(R.string.err_import_need_source)
            return
        }

        val open = openPicked
        val copier = importer
        if (open == null || copier == null) {
            importError.value = UiMessage(R.string.err_import_no_file_access)
            return
        }

        // One model per file name, because the name is what the file is. Without
        // this an import silently overwrites a verified download -- whose digest
        // is checked at download time and never again -- and the next download of
        // that model silently deletes the imported copy. The catalogue refuses two
        // of its own entries sharing a name for the same reason.
        val clash = registry.models.value.firstOrNull {
            it.fileName.equals(draft.fileName, ignoreCase = true)
        }
        if (clash != null) {
            importError.value =
                UiMessage(R.string.import_name_taken, draft.fileName, clash.displayName)
            return
        }

        importDraft.value = null
        // Held so Stop can actually stop it. Clearing the two state flows left the
        // copy running: the next progress callback put the dialog back on screen,
        // and the import finished and announced itself either way.
        importJob?.cancel()
        importJob = viewModelScope.launch {
            val id = "imported-${UUID.randomUUID()}"
            val target = store.modelFile(draft.fileName)

            try {
                val result = withContext(Dispatchers.IO) {
                    open(draft.uri).use { stream ->
                        copier.import(stream, draft.sizeBytes, target) { progress ->
                            importStage.value = progress
                        }
                    }
                }

                // Described only once it exists, so the size recorded is the number
                // of bytes written rather than the number the picker claimed. That
                // number decides whether the model counts as installed, so trusting
                // a provider that reports -1 or the wrong value would leave a valid
                // file permanently unusable, with no source to download it again
                // and no way to delete it.
                val descriptor = ModelDescriptor(
                    id = id,
                    displayName = name,
                    publisherRes = R.string.publisher_imported,
                    descriptionRes = R.string.imported_model_description,
                    url = null,
                    fileName = draft.fileName,
                    sizeBytes = result.sizeBytes,
                    sha256 = result.sha256,
                    license = ModelLicense(spdxId = licenseId, sourceUrl = source),
                    parametersBillions = 0.0,
                )

                registry.addImported(
                    ImportedModel(
                        id = id,
                        displayName = name,
                        fileName = draft.fileName,
                        sizeBytes = result.sizeBytes,
                        sha256 = result.sha256,
                        licenseSpdxId = licenseId,
                        licenseSourceUrl = source,
                        importedAt = System.currentTimeMillis(),
                    ),
                )
                importStage.value = null
                importReport.value = UiMessage(R.string.import_added, name)
                refreshSnapshot()
            } catch (t: CancellationException) {
                target.delete()
                importStage.value = null
                throw t
            } catch (t: Throwable) {
                target.delete()
                importStage.value = null
                importError.value = UiMessage(R.string.err_import_failed)
                        .withDetail(t.message ?: t::class.simpleName)
            }
        }
    }

    fun dismissImportReport() {
        importReport.value = null
    }

    fun dismissImportError() {
        importError.value = null
    }

    /**
     * A name that is safe to use as a file name.
     *
     * A file name reaches the filesystem, so anything that could point outside the
     * model directory is removed rather than escaped. The picker usually supplies
     * a plain name; "usually" is not a reason to skip this.
     */
    private fun sanitiseFileName(name: String): String {
        val cleaned = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_', '-')
        return cleaned.ifBlank { "imported-model.litertlm" }
    }

    /**
     * Re-reads the store.
     *
     * The snapshot normally refreshes when the download coordinator reports a
     * change, which covers everything this screen does itself. Anything that
     * changes model files from outside that path would otherwise show a stale row
     * until the next download.
     *
     * Called from the navigation entry's dispose, so returning to this screen --
     * including after the app has been in the background -- re-reads the device.
     */
    fun refresh() {
        viewModelScope.launch { refreshSnapshot() }
    }

    // -------------------------------------------------------- internals ---

    /**
     * Reads the store off the main thread.
     *
     * The read and the assignment both happen inside the lock, so the value that
     * lands is the one read by whichever caller held the lock last. Reading
     * outside and assigning after would let an older read finish later and
     * overwrite a newer one with stale contents -- which is not hypothetical:
     * the ViewModel's start-up and the screen's refresh can overlap, and the
     * start-up read can see the disk before a file appeared.
     */
    private suspend fun refreshSnapshot() {
        // The registry, not the catalogue. The rows on this screen are built from
        // the registry, so a snapshot taken from the catalogue has no entry for a
        // model the user imported: the file is on disk, and the screen reports it
        // as not downloaded -- with a Download button that does nothing, because
        // an imported model has no source to download from, and no Delete button,
        // because Delete is only offered once a model is known to be installed.
        val models = registry.models.value
        snapshotLock.withLock {
            snapshot.value = withContext(Dispatchers.IO) {
                // One read per model, so completeness and size cannot disagree.
                // Two separate reads let a file appear between them and produce
                // a state the device was never in: not installed, yet holding a
                // whole model's worth of bytes.
                val states = models.associate { it.id to store.stateOf(it) }
                StoreSnapshot(
                    installed = states.filterValues { it.installed }.keys,
                    bytesOnDisk = states.mapValues { it.value.bytesOnDisk },
                    freeBytes = store.availableBytes(),
                    usedBytes = store.totalBytesUsed(),
                )
            }
        }
    }

    /**
     * The screen's state, from values that were handed in.
     *
     * Every input is a parameter, and the four that used to be read off the instance
     * -- `verifying`, `verification`, `benchmarking`, `benchmark` -- now arrive inside
     * [tooling]. That is the fix rather than a refactor: a value read off the instance
     * here is not part of the `combine` that decides when to recompute, so changing it
     * changed nothing the screen saw. See [Tooling].
     */
    private fun buildState(
        inputs: Inputs,
        tooling: Tooling,
    ): ModelManagerUiState {
        val rows = registry.models.value.map { descriptor ->
            ModelRow(
                descriptor = descriptor,
                status = statusFor(descriptor, inputs.active, inputs.disk),
                // Read here rather than in the card, so the choice a running load
                // used and the one the card shows come from the same place.
                samplingProfile = inputs.compute.settings.chosenSamplingProfileFor(descriptor.id),
                samplingIsLive = inputs.compute.loadedModelId == descriptor.id,
            )
        }
        return ModelManagerUiState(
            rows = rows,
            freeBytes = inputs.disk.freeBytes,
            usedBytes = inputs.disk.usedBytes,
            deviceRamBytes = deviceMemory.total,
            verifying = tooling.verifying,
            benchmarking = tooling.benchmarking,
            benchmark = tooling.benchmark,
            verification = tooling.verification,
            pendingDelete = inputs.pending,
            deleteError = inputs.notices.deleteError,
            backend = inputs.compute.chosen,
            backendInUse = inputs.compute.inUse,
            importDraft = inputs.notices.importDraft,
            importStage = inputs.notices.importStage,
            importError = inputs.notices.importError,
            importReport = inputs.notices.importReport,
            // A row built from an empty snapshot must not be presented as fact.
            // Until the first read lands, every model looks absent and the disk
            // looks full.
            scanned = inputs.disk !== StoreSnapshot.EMPTY,
        )
    }

    private fun statusFor(
        descriptor: ModelDescriptor,
        active: ActiveDownload?,
        disk: StoreSnapshot,
    ): ModelStatus {
        // An installed model wins over any leftover message: the file on disk is
        // the fact, and showing a stale failure over a usable model would be
        // actively misleading.
        if (descriptor.id in disk.installed) {
            return ModelStatus.Ready(disk.bytesOnDisk[descriptor.id] ?: descriptor.sizeBytes)
        }

        if (active == null || active.modelId != descriptor.id) {
            val partial = disk.bytesOnDisk[descriptor.id] ?: 0L
            return if (partial > 0L) ModelStatus.Cancelled(partial) else ModelStatus.NotDownloaded
        }

        return when (active) {
            is ActiveDownload.Running -> ModelStatus.Downloading(
                bytesDone = active.bytesDone,
                bytesTotal = active.bytesTotal,
                bytesPerSecond = active.bytesPerSecond,
                etaSeconds = active.etaSeconds,
            )

            is ActiveDownload.Verifying -> ModelStatus.Verifying(active.bytesTotal)

            is ActiveDownload.Failed ->
                ModelStatus.Failed(active.message, active.retryable)

            is ActiveDownload.Cancelled ->
                ModelStatus.Cancelled(active.bytesDone)
        }
    }
}

/**
 * What every model is asked, so the numbers can be compared.
 *
 * A per-model prompt would measure the prompt as much as the model. This one is
 * short, ordinary, and in the app's language.
 */
private const val BENCHMARK_TOKENS = 128