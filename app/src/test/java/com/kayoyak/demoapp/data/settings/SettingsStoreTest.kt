package com.kayoyak.demoapp.data.settings

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.kayoyak.demoapp.inference.SamplingProfile
import java.io.File
import java.nio.file.Files

/**
 * The settings file is small and unglamorous, and that is exactly why it is
 * tested: everything here is a value the app reads at start-up and then trusts,
 * so a store that silently loses a write changes how the app behaves with no
 * visible cause.
 */
class SettingsStoreTest {

    private lateinit var root: File
    private lateinit var file: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("settings-test").toFile()
        file = File(root, "settings.json")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a missing file reads as the defaults`() {
        val store = FileSettingsStore(file)
        assertEquals(Settings(), store.settings.value)
        assertTrue("Nothing should be written just by reading", !file.exists())
    }

    @Test
    fun `a written value survives a restart`() = runBlocking {
        FileSettingsStore(file).update { it.copy(backend = "GPU") }

        // A second store over the same file is what a relaunch looks like.
        assertEquals("GPU", FileSettingsStore(file).settings.value.backend)
    }

    @Test
    fun `updates are applied to the current value, not a stale copy`() = runBlocking {
        val store = FileSettingsStore(file)
        store.update { it.copy(backend = "CPU") }
        store.update { it.copy(backend = "NPU") }

        assertEquals("NPU", store.settings.value.backend)
        assertEquals("NPU", FileSettingsStore(file).settings.value.backend)
    }

    @Test
    fun `an unchanged write is not a write`() = runBlocking {
        val store = FileSettingsStore(file)
        store.update { it.copy(backend = "CPU") }
        val written = file.lastModified()

        Thread.sleep(10)
        store.update { it.copy(backend = "CPU") }

        assertEquals("Rewriting identical settings is pointless work", written, file.lastModified())
    }

    @Test
    fun `a corrupt file reads as the defaults rather than refusing to start`() {
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json")

        // Every value here has a defensible default. An app that will not open
        // because a preference was malformed is worse than one that opens with
        // the preference reset.
        assertEquals(Settings(), FileSettingsStore(file).settings.value)
    }

    @Test
    fun `an unknown field from a newer build is ignored`() {
        file.parentFile?.mkdirs()
        file.writeText("""{"backend":"GPU","somethingAddedLater":true}""")

        assertEquals("GPU", FileSettingsStore(file).settings.value.backend)
    }

    @Test
    fun `a temporary file left by a crash is cleared on the next start`() {
        file.parentFile?.mkdirs()
        File(root, "settings.json.abc123.tmp").writeText("half a write")

        FileSettingsStore(file)

        assertTrue(
            "A leftover temporary file must not survive into the next run",
            root.listFiles()?.none { it.name.endsWith(".tmp") } ?: true,
        )
    }

    @Test
    fun `a backend name this build does not know falls back rather than throwing`() {
        file.parentFile?.mkdirs()
        file.writeText("""{"backend":"QUANTUM"}""")

        // The store is a persistence layer and keeps what it was given. The
        // fallback belongs where the value is turned into a backend, which is
        // also the only place that knows the set of backends.
        val stored = FileSettingsStore(file).settings.value.backend
        assertEquals("QUANTUM", stored)
        assertEquals(
            "An unknown name must become the default, not a crash",
            com.kayoyak.demoapp.inference.InferenceBackend.AUTO,
            com.kayoyak.demoapp.inference.inferenceBackendNamed(stored),
        )
    }

    @Test
    fun `every backend this build knows survives a round trip`() = runBlocking {
        // The stored form is the enum's name. An ordinal would come to mean a
        // different backend if the enum were ever reordered, and the user's
        // choice would change without anyone deciding to change it.
        com.kayoyak.demoapp.inference.InferenceBackend.entries.forEach { backend ->
            FileSettingsStore(file).update { it.copy(backend = backend.name) }
            assertEquals(
                backend,
                com.kayoyak.demoapp.inference.inferenceBackendNamed(
                    FileSettingsStore(file).settings.value.backend,
                ),
            )
        }
    }

    @Test
    fun `a sampling profile chosen for one model does not change another`() = runBlocking {
        // The whole point of the map. One global choice would make a user with a
        // 0.6B that repeats itself and a 2B that does not pick which of the two to
        // fix; this asserts the choice stayed where it was put.
        FileSettingsStore(file).update {
            it.copy(modelSampling = it.modelSampling + ("qwen3-0.6b" to SamplingProfile.VARIED.name))
        }

        val reloaded = FileSettingsStore(file).settings.value
        assertEquals(
            SamplingProfile.VARIED,
            reloaded.chosenSamplingProfileFor("qwen3-0.6b"),
        )
        assertEquals(
            "An untouched model keeps the default",
            SamplingProfile.BALANCED,
            reloaded.chosenSamplingProfileFor("minicpm5-2b-int4"),
        )
    }

    @Test
    fun `every sampling profile survives a round trip`() = runBlocking {
        // Stored by name, like the backend and the theme. An ordinal would come to
        // mean a different profile if the enum were reordered, and the user's
        // choice would change without anyone deciding to change it.
        SamplingProfile.entries.forEach { profile ->
            FileSettingsStore(file).update {
                it.copy(modelSampling = mapOf("m" to profile.name))
            }
            assertEquals(
                profile,
                FileSettingsStore(file).settings.value.chosenSamplingProfileFor("m"),
            )
        }
    }

    @Test
    fun `a sampling profile name this build does not know falls back rather than throwing`() {
        file.parentFile?.mkdirs()
        file.writeText("""{"modelSampling":{"m":"ECSTATIC"}}""")

        // Same rule as the backend: a file edited by hand, or written by a later
        // version, is the ordinary case for this and not something to report.
        assertEquals(
            SamplingProfile.BALANCED,
            FileSettingsStore(file).settings.value.chosenSamplingProfileFor("m"),
        )
    }

    @Test
    fun `the settings file written before per-model sampling existed still loads`() {
        file.parentFile?.mkdirs()
        file.writeText("""{"temperatureTenths":5}""")

        val settings = FileSettingsStore(file).settings.value
        assertEquals(5, settings.temperatureTenths)
        assertEquals(emptyMap<String, String>(), settings.modelSampling)
        assertEquals(
            SamplingProfile.BALANCED,
            settings.chosenSamplingProfileFor("anything"),
        )
    }

    @Test
    fun `the temperature slider still reaches a model that has a profile`() {
        // The slider is the one sampling value the settings screen has always
        // owned. Taking it away from a model with a profile would be a regression
        // for whoever had set it, so the two compose: the profile decides topK,
        // topP and the rest, the slider decides how adventurous the wording is.
        val settings = Settings(
            temperatureTenths = 13,
            modelSampling = mapOf("m" to SamplingProfile.PRECISE.name),
        )

        val sampling = settings.samplingFor("m")

        assertEquals("the slider wins on temperature", 1.3, sampling.temperature, 0.0001)
        assertEquals("the profile still wins on the rest", 10, sampling.topK)
    }
}
