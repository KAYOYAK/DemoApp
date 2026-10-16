package com.kayoyak.demoapp.ui.models

import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

import com.kayoyak.demoapp.R
import com.kayoyak.demoapp.ui.UiMessage

import com.kayoyak.demoapp.data.settings.InMemorySettingsStore
import com.kayoyak.demoapp.inference.InferenceBackend
import com.kayoyak.demoapp.model.ActiveDownload
import com.kayoyak.demoapp.model.DownloadEvent
import com.kayoyak.demoapp.model.InMemoryModelRegistry
import com.kayoyak.demoapp.model.ModelCatalogue
import com.kayoyak.demoapp.model.ModelDescriptor
import com.kayoyak.demoapp.model.ModelDownloadCoordinator
import com.kayoyak.demoapp.model.ModelImporter
import com.kayoyak.demoapp.model.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * Covers the states this screen can be in.
 *
 * The governance rule this follows is to cover the states the operation
 * actually has rather than only the happy path: a download has an initial,
 * running, verifying, paused, failed and complete state, and each is reachable
 * in real use.
 */
@OptIn(ExperimentalCoroutinesApi::class)
/** See the note in ChatViewModelTest; the same reasoning applies here. */
private const val AWAIT_TIMEOUT_MS = 15_000L

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagerViewModelTest {

    private lateinit var root: File
    private lateinit var store: ModelStore
    private lateinit var coordinator: ModelDownloadCoordinator
    private lateinit var viewModel: ModelManagerViewModel

    /** Events the fake downloader will emit, per model id. */
    private val scripted = MutableSharedFlow<Pair<String, DownloadEvent>>(extraBufferCapacity = 64)

    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    private val first = ModelCatalogue.models[0]
    private val second = ModelCatalogue.models[1]

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        root = Files.createTempDirectory("viewmodel-test").toFile()
        store = ModelStore(root)

        coordinator = ModelDownloadCoordinator(
            scope = testScope,
            store = store,
            download = { model ->
                flow {
                    // Never completes on its own: the test drives it, which is
                    // what lets a mid-download state be observed at all.
                    scripted.collect { (id, event) -> if (id == model.id) emit(event) }
                }
            },
        )
        viewModel = tracked({ ModelManagerViewModel(store, coordinator) }, ModelManagerViewModel::class.java)
    }

    @After
    fun tearDown() {
        // Everything the test started, stopped. A download left running, or a
        // ViewModel whose scope nobody closed, holds a coroutine on the shared
        // dispatcher; the next test's setUp() calls setMain underneath it, which
        // fails as "Dispatchers.Main is used concurrently with setting it" -- in
        // a different test from the one that caused it.
        viewModels.clear()
        testScope.cancel()
        coordinator.cancel()

        // Closing a scope stops the coroutine, but a task already handed to
        // Dispatchers.IO still runs, and when it resumes it comes back to Main --
        // by then the next test's dispatcher. Queueing one more IO task and waiting
        // for it drains everything ahead of it, so nothing is left in flight.
        runBlocking {
            // Both pools. A ViewModel's work goes to IO, but a sharing coroutine or
            // a flow operator may be on Default, and either one resuming after the
            // main dispatcher has been swapped is the same failure.
            withContext(Dispatchers.IO) { }
            withContext(Dispatchers.Default) { }
            // Then let anything those completions dispatched run before the swap.
            yield()
            yield()
        }

        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    /**
     * The state, after a subscription has actually produced it.
     *
     * The screen's state is a StateFlow that runs only while something collects
     * it. Read without a subscriber it is the value it was built with, so a test
     * that calls into the ViewModel and then reads `.value` sees nothing change
     * -- and fails, or passes, for a reason that has nothing to do with the
     * behaviour under test. Every read below subscribes and waits for the
     * condition, which is what the screen does.
     */
    private suspend fun awaitImportDraft(): ImportDraft =
        withTimeout(AWAIT_TIMEOUT_MS) {
            viewModelUnderTest.state.first { it.importDraft != null }
        }.importDraft!!

    private suspend fun awaitImportError(): UiMessage =
        withTimeout(AWAIT_TIMEOUT_MS) {
            viewModelUnderTest.state.first { it.importError != null }
        }.importError!!

    private suspend fun awaitImportReport(): UiMessage =
        withTimeout(AWAIT_TIMEOUT_MS) {
            viewModelUnderTest.state.first { it.importReport != null }
        }.importReport!!

    /** The ViewModel the import helpers read, so they share one subscription. */
    private lateinit var viewModelUnderTest: ModelManagerViewModel

    /**
     * Holds every ViewModel this test built, so [tearDown] can close their scopes.
     *
     * `ViewModelStore.clear()` is the public way to do what the framework does
     * when a screen goes away. Without it a job still running at the end of a
     * test outlives it, and the next test's `setMain` fails -- reported against a
     * different test from the one that caused it.
     */
    private val viewModels = androidx.lifecycle.ViewModelStore()
    private var slot = 0

    private fun <T : androidx.lifecycle.ViewModel> tracked(make: () -> T, type: Class<T>): T {
        val made = make()
        val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : androidx.lifecycle.ViewModel> create(modelClass: Class<VM>): VM =
                made as VM
        }
        androidx.lifecycle.ViewModelProvider(viewModels, factory).get("slot${slot++}", type)
        return made
    }

    /** Shared by the import tests, so a rebuilt ViewModel keeps the same list. */
    private var registry: InMemoryModelRegistry = InMemoryModelRegistry()

    /**
     * A ViewModel whose import path is wired up.
     *
     * The stream comes from an array rather than a document provider, which is
     * what makes the whole import reachable without a device.
     */
    private fun importVm(payload: ByteArray = ByteArray(32)): ModelManagerViewModel {
        registry = InMemoryModelRegistry()
        val created = ModelManagerViewModel(
            store = store,
            coordinator = coordinator,
            registry = registry,
            openPicked = { java.io.ByteArrayInputStream(payload) },
            importer = ModelImporter(store),
        )
        viewModelUnderTest = created
        return created
    }

    /** Makes a file of exactly the catalogue size without writing its bytes. */
    private fun install(model: ModelDescriptor) {
        val file = store.modelFile(model)
        file.parentFile?.mkdirs()
        java.io.RandomAccessFile(file, "rw").use { it.setLength(model.sizeBytes) }
    }

    private fun writePartial(model: ModelDescriptor, bytes: Int) {
        val f = store.partialFile(model)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
    }

    private suspend fun rows() = withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first().rows }

    /**
     * Waits for one row to reach an expected state.
     *
     * The store is read off the main thread, so the first emission is the
     * ViewModel's initial value rather than what is on disk. Asserting on that
     * first emission tests the loading placeholder, not the behaviour.
     */
    private suspend fun awaitStatus(model: ModelDescriptor, predicate: (ModelStatus) -> Boolean): ModelStatus =
        withTimeout(AWAIT_TIMEOUT_MS) {
            viewModel.state
                .map { state -> state.rows.first { it.descriptor.id == model.id }.status }
                .first(predicate)
        }

    /** Waits for the coordinator to report a state of [model] matching [predicate]. */
    private suspend fun awaitActive(
        model: ModelDescriptor,
        predicate: (ActiveDownload) -> Boolean,
    ): ActiveDownload = withTimeout(AWAIT_TIMEOUT_MS) {
        coordinator.active.first { it != null && it.modelId == model.id && predicate(it) }
            as ActiveDownload
    }

    // ----------------------------------------------------------- states ---

    @Test
    fun `nothing on disk reads as not downloaded`() = runBlocking {
        val row = rows().first { it.descriptor.id == first.id }
        assertEquals(ModelStatus.NotDownloaded, row.status)
    }

    @Test
    fun `a present file of the right size reads as ready`() = runBlocking {
        // Real size, written sparsely: allocating 356 MB in a test is wasteful
        // and the store only checks length.
        install(first)
        // The file was created behind the ViewModel's back, so nothing has told
        // it to re-read the store.
        viewModel.refresh()

        val status = awaitStatus(first) { it is ModelStatus.Ready }
        assertTrue("Expected Ready, got $status", status is ModelStatus.Ready)
    }

    @Test
    fun `a partial file reads as paused rather than absent`() = runBlocking {
        // A user who cancelled a download and came back should see that their
        // progress is still there, not a fresh untouched row.
        writePartial(first, 4096)
        viewModel.refresh()

        val status = awaitStatus(first) { it is ModelStatus.Cancelled }
        assertTrue("Expected Cancelled, got $status", status is ModelStatus.Cancelled)
        assertEquals(4096L, (status as ModelStatus.Cancelled).bytesDone)
    }

    @Test
    fun `a running download reports progress`() = runBlocking {
        viewModel.download(first)
        scripted.tryEmit(first.id to DownloadEvent.Progress(1000, first.sizeBytes, 500, 60))

        val row = rows().first { it.descriptor.id == first.id }
        val status = row.status
        assertTrue("Expected Downloading, got $status", status is ModelStatus.Downloading)
        assertEquals(1000L, (status as ModelStatus.Downloading).bytesDone)
    }

    @Test
    fun `progress fraction is correct and clamped`() {
        val half = ModelStatus.Downloading(50, 100, 0, 0)
        assertEquals(0.5f, half.fraction, 0.001f)

        // A server reporting more bytes than the catalogue expects must not
        // drive a progress bar past its end.
        val over = ModelStatus.Downloading(200, 100, 0, 0)
        assertEquals(1f, over.fraction, 0.001f)
    }

    @Test
    fun `verification is its own state`() = runBlocking {
        viewModel.download(first)
        scripted.tryEmit(first.id to DownloadEvent.Verifying(first.sizeBytes))

        val status = rows().first { it.descriptor.id == first.id }.status
        assertTrue(
            "Hashing several gigabytes is not instant, so it must not look like a stalled download: $status",
            status is ModelStatus.Verifying,
        )
    }

    @Test
    fun `a failure is reported with a message and a retry decision`() = runBlocking {
        viewModel.download(second)
        scripted.tryEmit(second.id to DownloadEvent.Failed(java.io.IOException("connection reset")))

        val status = rows().first { it.descriptor.id == second.id }.status
        assertTrue("Expected Failed, got $status", status is ModelStatus.Failed)
        val failed = status as ModelStatus.Failed
        assertTrue("A network failure is worth retrying", failed.retryable)
        // A message with a resource behind it, and a detail, is one a user can act on.
        // Asserting the wording itself would pin the test to strings.xml.
        assertTrue("The failure must carry a message", failed.message.textRes != 0)
    }

    @Test
    fun `running out of space is not offered as a plain retry`() = runBlocking {
        viewModel.download(second)
        scripted.tryEmit(
            second.id to DownloadEvent.Failed(
                com.kayoyak.demoapp.model.InsufficientStorage(10_000_000_000L, 1_000_000L)
            )
        )

        val status = rows().first { it.descriptor.id == second.id }.status as ModelStatus.Failed
        assertFalse(
            "Retrying with the disk still full fails identically, which teaches the user the button is broken",
            status.retryable,
        )
        assertEquals(R.string.err_download_no_space, status.message.textRes)
        assertTrue(
            "Both numbers must be passed so the user can decide: ${status.message.args}",
            status.message.args.size == 2,
        )
    }

    @Test
    fun `an installed model outranks a leftover error`() = runBlocking {
        // If a download failed and then the file turned out to be present and
        // complete, the file is the fact. Showing a failure over a usable model
        // would send the user chasing a problem they do not have.
        viewModel.download(first)
        scripted.tryEmit(first.id to DownloadEvent.Failed(java.io.IOException("boom")))
        awaitStatus(first) { it is ModelStatus.Failed }

        install(first)
        viewModel.refresh()

        val status = awaitStatus(first) { it is ModelStatus.Ready }
        assertTrue("Expected Ready, got $status", status is ModelStatus.Ready)
    }

    @Test
    fun `starting a download while another runs is ignored`() = runBlocking {
        viewModel.download(first)
        scripted.tryEmit(first.id to DownloadEvent.Progress(10, first.sizeBytes, 1, 1))
        val before = coordinator.active.value

        viewModel.download(second)

        assertEquals(
            "Two simultaneous multi-gigabyte transfers compete for the same connection and space",
            before,
            coordinator.active.value,
        )
    }

    // ----------------------------------------------------------- delete ---

    @Test
    fun `deleting asks first and reports what it would free`() = runBlocking {
        install(first)
        viewModel.refresh()
        awaitStatus(first) { it is ModelStatus.Ready }

        viewModel.requestDelete(first)

        val pending = withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.pendingDelete != null }.pendingDelete }
        assertTrue("Deleting a multi-gigabyte download should not happen on one tap", pending != null)
        assertEquals(first.id, pending!!.descriptor.id)
        assertEquals(first.sizeBytes, pending.bytesFreed)
        assertTrue("Nothing should be removed until confirmed", store.isInstalled(first))
    }

    @Test
    fun `cancelling the confirmation keeps the model`() = runBlocking {
        java.io.RandomAccessFile(store.modelFile(first), "rw").use { it.setLength(first.sizeBytes) }

        viewModel.requestDelete(first)
        viewModel.cancelDelete()

        val state = withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first() }
        assertNull(state.pendingDelete)
        assertTrue(store.isInstalled(first))
    }

    @Test
    fun `confirming removes the file and clears the confirmation`() = runBlocking {
        java.io.RandomAccessFile(store.modelFile(first), "rw").use { it.setLength(first.sizeBytes) }

        viewModel.requestDelete(first)
        viewModel.confirmDelete()

        // Deleting is asynchronous -- it stops any running transfer for that
        // model before removing the file -- so the row settles after the call
        // returns, not during it.
        val status = awaitStatus(first) { it is ModelStatus.NotDownloaded }
        assertEquals(ModelStatus.NotDownloaded, status)
        assertFalse(store.isInstalled(first))
        assertNull("The confirmation must be dismissed", viewModel.state.value.pendingDelete)
    }

    // ------------------------------------------------- round-2 regressions ---

    @Test
    fun `deleting one model does not stop another model's download`() = runBlocking {
        // The transfer to cancel was found by comparing the model id against the
        // DISPLAY state, which is not written until the first progress event --
        // seconds after a transfer starts. Deleting a model therefore cancelled
        // whichever job happened to be running while the display still named the
        // deleted one, so another model's download died with no message.
        java.io.RandomAccessFile(store.modelFile(first), "rw").use { it.setLength(first.sizeBytes) }

        coordinator.start(second)
        // No progress yet, so before the fix the display still named nothing and
        // the delete below would not cancel at all.
        viewModel.requestDelete(first)
        viewModel.confirmDelete()
        awaitStatus(first) { it is ModelStatus.NotDownloaded }

        assertTrue(
            "The download the user did not touch must still be running",
            coordinator.isRunning,
        )
        assertEquals(
            "The running transfer must still be the one that was started",
            second.id,
            coordinator.active.value?.modelId,
        )
    }

    @Test
    fun `a download reports itself as running before the first byte arrives`() = runBlocking {
        // Without this the row offered "Resume" for a transfer that was already
        // moving bytes, because the coordinator only published a state on the
        // first progress event -- which needs DNS, connect, TLS and the first
        // chunk to complete first.
        coordinator.start(first)

        assertEquals(
            "A started download must be visible immediately",
            first.id,
            coordinator.active.value?.modelId,
        )
        assertTrue(coordinator.isRunning)
    }

    @Test
    fun `a second start while one is running is refused`() = runBlocking {
        // The check and the record must be one action. Split across two critical
        // sections, two callers could both see nothing running and both start,
        // writing the same `.part` file from two offsets.
        coordinator.start(first)
        coordinator.start(second)

        assertEquals(
            "The first transfer keeps the device",
            first.id,
            coordinator.active.value?.modelId,
        )
    }

    @Test
    fun `deleting a model clears the paused message about it`() = runBlocking {
        // The coordinator's terminal state is what the row renders, and it
        // outlives the file. Without clearing it, a deleted model kept claiming
        // "stopped at N bytes" and offering Resume, for a file that was gone.
        coordinator.start(first)
        awaitActive(first) { it is ActiveDownload.Running }
        coordinator.cancel()
        awaitActive(first) { it is ActiveDownload.Cancelled }

        viewModel.requestDelete(first)
        viewModel.confirmDelete()

        val status = awaitStatus(first) { it is ModelStatus.NotDownloaded }
        assertEquals(ModelStatus.NotDownloaded, status)
        assertNull("No paused message may outlive the file", coordinator.active.value)
    }
    // ------------------------------------------------------- the backend ---

    @Test
    fun `choosing a backend is remembered and shown`() = runBlocking {
        val settings = InMemorySettingsStore()
        val vm = ModelManagerViewModel(store, coordinator, settings)

        assertTrue("Nothing loaded yet, so nothing is in use", vm.state.value.backendInUse == null)
        assertEquals(InferenceBackend.AUTO, vm.state.value.backend)

        vm.chooseBackend(InferenceBackend.CPU)

        val state = withTimeout(AWAIT_TIMEOUT_MS) { vm.state.first { it.backend == InferenceBackend.CPU } }
        assertEquals("The choice is what will be asked for", InferenceBackend.CPU, state.backend)
        assertEquals("And it is written down", "CPU", settings.settings.value.backend)
    }

    @Test
    fun `what actually runs is shown separately from what was asked for`() = runBlocking {
        // A device whose graphics hardware cannot run the model falls back. The
        // screen has to be able to say that, because "I chose Graphics and it is
        // using the processor" and "it ignored my choice" look identical
        // otherwise.
        val settings = InMemorySettingsStore()
        val engine = FakeEngine()
        val vm = ModelManagerViewModel(store, coordinator, settings, engine)

        vm.chooseBackend(InferenceBackend.GPU)
        withTimeout(AWAIT_TIMEOUT_MS) { vm.state.first { it.backend == InferenceBackend.GPU } }

        // The engine reports that it settled on CPU.
        engine.reportReady("some-model", InferenceBackend.CPU)

        val state = withTimeout(AWAIT_TIMEOUT_MS) { vm.state.first { it.backendInUse != null } }
        assertEquals("The request is the user's", InferenceBackend.GPU, state.backend)
        assertEquals("The outcome is the device's", InferenceBackend.CPU, state.backendInUse)
    }
    // ------------------------------------------------------------- importing ---

    @Test
    fun `picking a file opens the licence form without copying anything`() = runBlocking {
        val vm = importVm()

        vm.prepareImport("content://picked/1", "My Model.litertlm", 4_096L)

        val draft = awaitImportDraft()
        // The space becomes an underscore: the name is sanitised before it is
        // shown, because it is the name a file will be created under.
        assertEquals("My_Model.litertlm", draft.fileName)
        assertEquals("My Model", draft.displayName)
        assertFalse(
            "Nothing is on disk until the licence question is answered",
            store.modelFile(ModelCatalogue.models[0]).parentFile?.listFiles()
                ?.any { it.name.contains("Model") } ?: false,
        )
    }

    @Test
    fun `a picked file name cannot escape the model directory`() = runBlocking {
        // The name reaches the filesystem. The picker usually supplies a plain
        // name, and "usually" is not a reason to skip removing the separators.
        val vm = importVm()

        vm.prepareImport("content://picked/1", "../../../../etc/passwd", 16L)

        val draft = awaitImportDraft()!!
        assertFalse("No traversal", draft.fileName.contains(".."))
        assertFalse("No separators", draft.fileName.contains('/'))
        assertFalse("No separators", draft.fileName.contains('\\'))
    }

    @Test
    fun `importing without naming a licence is refused`() = runBlocking {
        // This is the gate the whole feature turns on. Every model in the
        // catalogue is there because its licence was checked; a file the user
        // supplies has no such record until they give one.
        val vm = importVm()
        vm.prepareImport("content://picked/1", "model.litertlm", 32L)

        vm.confirmImport("model", licenseId = "", licenseSourceUrl = "https://example.com")

        assertTrue("It says why", awaitImportError().textRes != 0)
        assertNotNull("And the form stays open to be answered", vm.state.value.importDraft)
    }

    @Test
    fun `importing without saying where the licence was checked is refused`() = runBlocking {
        val vm = importVm()
        vm.prepareImport("content://picked/1", "model.litertlm", 32L)

        vm.confirmImport("model", licenseId = "Apache-2.0", licenseSourceUrl = "   ")

        assertTrue(awaitImportError().textRes != 0)
        assertNotNull(vm.state.value.importDraft)
    }

    @Test
    fun `a Windows-style path in a file name cannot escape either`() = runBlocking {
        // The earlier test used a Unix path, and the code strips forward slashes
        // before the sanitiser ever runs -- so it passed on the strength of a step
        // that does not handle backslashes at all. A document provider on a device
        // that reports a Windows-shaped name reaches the sanitiser intact.
        val vm = importVm()

        vm.prepareImport("content://picked/1", "..\\..\\..\\Windows\\System32\\evil.litertlm", 16L)

        val name = awaitImportDraft().fileName
        assertFalse("No traversal: $name", name.contains(".."))
        assertFalse("No forward slash: $name", name.contains('/'))
        assertFalse("No backslash: $name", name.contains('\\'))
    }

    @Test
    fun `a file name with characters a filesystem dislikes is made safe`() = runBlocking {
        val vm = importVm()

        vm.prepareImport("content://picked/1", "my model (v2) — naïve*.litertlm", 16L)

        val name = awaitImportDraft().fileName
        assertTrue("Only safe characters survive: $name", name.all { it.isLetterOrDigit() || it in "._-" })
        assertFalse("A name cannot be empty", name.isBlank())
        assertTrue("And it still says what it is: $name", name.endsWith(".litertlm"))
    }

    @Test
    fun `a file name that is nothing but punctuation still yields a usable name`() = runBlocking {
        val vm = importVm()

        vm.prepareImport("content://picked/1", "***", 16L)

        val name = awaitImportDraft().fileName
        assertTrue("Not blank: '$name'", name.isNotBlank())
        assertFalse("Not a run of filler: '$name'", name.all { it == '_' || it == '.' || it == '-' })
    }

    @Test
    fun `a licence this project does not accept is refused`() = runBlocking {
        val vm = importVm()
        vm.prepareImport("content://picked/1", "model.litertlm", 32L)

        vm.confirmImport("model", licenseId = "GPL-3.0", licenseSourceUrl = "https://example.com")

        assertTrue("A copyleft licence is not carried", awaitImportError().textRes != 0)
        assertNotNull(vm.state.value.importDraft)
    }

    @Test
    fun `an accepted import is copied, recorded and reported`() = runBlocking {
        val payload = ByteArray(64_000) { it.toByte() }
        val vm = importVm(payload)

        vm.prepareImport("content://picked/1", "My Model.litertlm", payload.size.toLong())
        vm.confirmImport("My Model", licenseId = "Apache-2.0", licenseSourceUrl = "https://example.com/x")

        val report = awaitImportReport()
        assertNull("The form is closed", vm.state.value.importDraft)
        assertNull("And no progress is left showing", vm.state.value.importStage)
        assertEquals("It says what happened", R.string.import_added, report.textRes)
        assertEquals("And names the model", listOf("My Model"), report.args)

        val added = registry.models.value.firstOrNull { it.url == null }
        assertNotNull("It is in the list of models", added)
        assertTrue(
            "And its file is where the app looks for models",
            store.modelFile(added!!).isFile,
        )
        assertEquals("With the bytes that were copied", 64_000L, store.modelFile(added).length())
    }

    @Test
    fun `cancelling the form copies nothing`() = runBlocking {
        val vm = importVm()

        vm.prepareImport("content://picked/1", "model.litertlm", 32L)
        vm.cancelImport()

        assertNull(vm.state.value.importDraft)
        assertNull(vm.state.value.importStage)
    }

    // ------------------------------------------------------------- benchmark ---

    /**
     * A ViewModel with an engine attached.
     *
     * `runBenchmark` returns early when there is no engine -- `val engine = engine
     * ?: return` -- which is why 29 tests could exist in this file without one of
     * them reaching the benchmark: the shared `viewModel` in `setUp` has no engine,
     * and nothing ever gave it one.
     */
    private fun benchmarkVm(engine: FakeEngine): ModelManagerViewModel =
        ModelManagerViewModel(store = store, coordinator = coordinator, engine = engine)

    /**
     * Waits for the screen's state to satisfy [predicate], and keeps a subscription
     * open while doing it.
     *
     * `state` is `SharingStarted.WhileSubscribed(5_000)`, so nothing recomputes until
     * something collects it. A test that acts and then reads `.value` sees the initial
     * value, and one that acts and then subscribes can subscribe too late: the flag it
     * is waiting for may already have been set and cleared while nobody was watching.
     *
     * Subscribing first and acting second is what removes that race, which is why the
     * benchmark tests take a `body` rather than each opening its own `first { }`.
     */
    private suspend fun <T> awaitState(
        vm: ModelManagerViewModel,
        predicate: (ModelManagerUiState) -> Boolean,
        body: suspend () -> T,
    ): T {
        val read = kotlinx.coroutines.CompletableDeferred<ModelManagerUiState>()
        val watcher = testScope.launch {
            vm.state.collect { state -> if (predicate(state)) read.complete(state) }
        }
        try {
            val value = body()
            withTimeout(AWAIT_TIMEOUT_MS) { read.await() }
            return value
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun `the benchmark sends the prompt it was given, and nothing else`() = runBlocking {
        // This is the behaviour `scripts/mutation-sweep.ps1` reported as a survivor:
        // "the benchmark prompt is dropped at the call site". The chain is
        // stringResource -> a screen parameter -> onBenchmark, and the hole is that
        // the parameter's default is empty, so a call site that omitted it would
        // pass "" and the benchmark would run an empty prompt -- measuring start-up
        // and nothing else -- while looking like it worked.
        //
        // `check-ui-wiring.ps1` now fails a build that omits it, which guards the
        // future. This guards the present: that the value which arrives is the one
        // the engine is asked, unchanged.
        val engine = FakeEngine()
        engine.timing = com.kayoyak.demoapp.inference.GenerationTiming(decodeTokensPerSecond = 12.5)
        val vm = benchmarkVm(engine)

        val asked = "用一句话解释什么是量子纠缠。"
        awaitState(vm, { it.benchmark != null }) { vm.runBenchmark(first, asked) }

        assertEquals("the engine must be asked exactly the prompt", listOf(asked), engine.prompts)
    }

    @Test
    fun `the benchmark measures speed, so it pins the settings that would add noise`() = runBlocking {
        // `GenerationConfig(maxTokens = BENCHMARK_TOKENS, temperature = 0.0)`, with
        // the reason in the source: "A fixed length and no sampling surprises: this
        // measures speed." A benchmark at a non-zero temperature would produce
        // different text on every run, and the point of the fixed prompt is that two
        // models can be compared.
        //
        // Asserted as a pair rather than as one value, because either half alone
        // leaves the measurement unrepeatable: length without temperature varies the
        // work, temperature without length lets a model answer for as long as it
        // likes and report a throughput that depends on that.
        val engine = FakeEngine()
        engine.timing = com.kayoyak.demoapp.inference.GenerationTiming(decodeTokensPerSecond = 12.5)
        val vm = benchmarkVm(engine)

        awaitState(vm, { it.benchmark != null }) { vm.runBenchmark(first, "p") }

        val config = engine.configs.single()
        assertEquals("greedy, so two runs are comparable", 0.0, config.temperature, 0.0)
        assertTrue("a fixed length", config.maxTokens in 1..4096)
    }

    @Test
    fun `a second benchmark does not start while one is running`() = runBlocking {
        // `if (benchmarking.value != null) return`, and it is not a nicety: each run
        // loads a model and generates a reply, so two at once on a phone is the
        // memory situation the inference seam exists to prevent -- and the result
        // of the first would be overwritten by whichever finished second, with no
        // way for the user to tell which model the number describes.
        //
        // The gate is what makes the running state observable. Without it the run
        // reaches `benchmarking.value = null` before the first assertion, and a test
        // that cannot see the window cannot test what happens inside it.
        val engine = FakeEngine()
        engine.timing = com.kayoyak.demoapp.inference.GenerationTiming(decodeTokensPerSecond = 12.5)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.gate = gate
        val vm = benchmarkVm(engine)

        // The running flag is watched for while the first run is still gated, so the
        // second call below lands inside the window rather than after it.
        val running = kotlinx.coroutines.CompletableDeferred<Unit>()
        val watcher = testScope.launch {
            vm.state.collect { if (it.benchmarking != null) running.complete(Unit) }
        }
        try {
            vm.runBenchmark(first, "first")
            withTimeout(AWAIT_TIMEOUT_MS) { running.await() }

            vm.runBenchmark(second, "second")

            assertEquals(
                "the second must not have reached the engine",
                listOf("first"),
                engine.prompts,
            )
        } finally {
            watcher.cancel()
        }

        gate.complete(Unit)
        withTimeout(AWAIT_TIMEOUT_MS) { vm.state.first { it.benchmarking == null } }
        assertNotNull("the first must still produce its result", vm.state.value.benchmark)
    }

    @Test
    fun `a benchmark that fails reports nothing rather than a number`() = runBlocking {
        // `timing` is captured from the Done event, and a run that produces no Done
        // leaves it null, so `benchmark` becomes null. That is the right shape: a
        // figure shown after a failed run would be read as a measurement of the
        // model rather than of the failure.
        //
        // This is reachable in real use -- the model file can be gone, or the load
        // can fail -- and the screen must not be left showing a spinner, so what is
        // asserted is both halves: nothing reported, and the flag cleared.
        val engine = FakeEngine()
        engine.timing = null
        val vm = benchmarkVm(engine)

        awaitState(vm, { it.benchmarking == null && it.benchmark == null }) {
            vm.runBenchmark(first, "p")
        }

        assertNull("no figure for a run that produced none", vm.state.value.benchmark)
    }

    @Test
    fun `a reported figure can be dismissed`() = runBlocking {
        // The result is a card on the screen, and a card with no way off it is a
        // screen the user cannot get back. `dismissBenchmark` is the only exit.
        //
        // This is the test that found the bug: `dismissBenchmark` writes one flow
        // that the state's `combine` did not include, so the card stayed on screen
        // and the assertion below failed. See `ModelManagerViewModel.Tooling`.
        val engine = FakeEngine()
        engine.timing = com.kayoyak.demoapp.inference.GenerationTiming(decodeTokensPerSecond = 12.5)
        val vm = benchmarkVm(engine)

        awaitState(vm, { it.benchmark != null }) { vm.runBenchmark(first, "p") }
        awaitState(vm, { it.benchmark == null }) { vm.dismissBenchmark() }

        assertNull(vm.state.value.benchmark)
    }

    @Test
    fun `a benchmark names the model it measured`() = runBlocking {
        // The figure is only useful with the model attached to it: a throughput of
        // 12 tok/s is good on one model and disappointing on another, and the screen
        // shows several. The label comes from the catalogue entry rather than from
        // the engine, which reports only an id.
        val engine = FakeEngine()
        engine.timing = com.kayoyak.demoapp.inference.GenerationTiming(decodeTokensPerSecond = 12.5)
        val vm = benchmarkVm(engine)

        awaitState(vm, { it.benchmark != null }) { vm.runBenchmark(second, "p") }

        val reported = vm.state.value.benchmark!!
        assertEquals(second.displayName, reported.displayName)
    }

    @Test
    fun `a verification result can be dismissed`() = runBlocking {
        // The same defect as the benchmark, in the other half of the same subsystem:
        // `dismissVerification` writes `verification`, and that flow was not in the
        // state's `combine` either. Checked separately rather than assumed to follow,
        // because they are two functions with two call sites and the fix is one line
        // per flow -- which is exactly the kind of change that gets half applied.
        val engine = FakeEngine()
        val vm = benchmarkVm(engine)

        // Reach the outcome the way the screen does.
        vm.verifyModel(first)
        withTimeout(AWAIT_TIMEOUT_MS) { vm.state.first { it.verification != null } }

        awaitState(vm, { it.verification == null }) { vm.dismissVerification() }

        assertNull(vm.state.value.verification)
    }
}

/**
 * An engine that reports whatever a test tells it to.
 *
 * Only the state is used here. The tests around it ask which backend was
 * requested and which one the device ended up using, which is a question about
 * the two being reported separately rather than about anything the engine does
 * with a model.
 */
/**
 * The engine, recorded.
 *
 * `generate` used to return an empty flow, which is a valid engine and a useless
 * one: a benchmark against it collects nothing, so `timing` stays null and the
 * result is a failure. That made the whole benchmark path untestable, and it is why
 * it had no tests -- the survivor in `scripts/mutation-sweep.ps1` named
 * "the benchmark prompt is dropped at the call site" was the symptom rather than
 * the cause.
 *
 * Three things are needed to test it, and each is one field: what was asked (the
 * prompt and the config), when it is allowed to answer ([gate]), and what it
 * answers ([timing]).
 */
private class FakeEngine : com.kayoyak.demoapp.inference.LlmEngine {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<com.kayoyak.demoapp.inference.EngineState>(
        com.kayoyak.demoapp.inference.EngineState.Idle
    )
    override val state: kotlinx.coroutines.flow.StateFlow<com.kayoyak.demoapp.inference.EngineState> = _state

    /** Every prompt handed to [generate], in order. */
    val prompts = mutableListOf<String>()

    /** Every config handed to it, in order, paired with its prompt. */
    val configs = mutableListOf<com.kayoyak.demoapp.inference.GenerationConfig>()

    /** Every model handed to [load]. */
    val loaded = mutableListOf<String>()

    /** What the engine reports when it finishes. Null means "never finishes". */
    var timing: com.kayoyak.demoapp.inference.GenerationTiming? = null

    /**
     * Holds [generate] open until the test releases it.
     *
     * Null means "answer immediately". A non-null gate is what makes a running
     * benchmark observable at all: without one, the coroutine reaches
     * `benchmarking.value = null` before the test's first assertion.
     */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun reportReady(modelId: String, backend: com.kayoyak.demoapp.inference.InferenceBackend) {
        _state.value = com.kayoyak.demoapp.inference.EngineState.Ready(modelId, backend)
    }

    override suspend fun load(
        model: com.kayoyak.demoapp.inference.LocalModel,
        requested: com.kayoyak.demoapp.inference.InferenceBackend,
        systemPrompt: String,
        sampling: com.kayoyak.demoapp.inference.GenerationConfig,
    ): Result<com.kayoyak.demoapp.inference.InferenceBackend> {
        loaded += model.id
        return Result.success(com.kayoyak.demoapp.inference.InferenceBackend.CPU)
    }

    override suspend fun unload() {
        _state.value = com.kayoyak.demoapp.inference.EngineState.Idle
    }

    override suspend fun resetConversation(history: List<com.kayoyak.demoapp.inference.EngineTurn>) = Unit

    override fun generate(
        prompt: String,
        config: com.kayoyak.demoapp.inference.GenerationConfig,
    ): kotlinx.coroutines.flow.Flow<com.kayoyak.demoapp.inference.GenerationEvent> {
        prompts += prompt
        configs += config
        return kotlinx.coroutines.flow.flow {
            gate?.await()
            val t = timing ?: return@flow
            emit(com.kayoyak.demoapp.inference.GenerationEvent.Done("benchmark", 1, 1, timing = t))
        }
    }
}
