package com.kayoyak.demoapp.ui.settings

import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

import com.kayoyak.demoapp.R

import com.kayoyak.demoapp.data.settings.InMemorySettingsStore
import com.kayoyak.demoapp.data.settings.Settings
import com.kayoyak.demoapp.inference.InferenceBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val AWAIT_TIMEOUT_MS = 15_000L

/**
 * The settings that shape every reply. Each of these is read at the moment it is
 * needed rather than held, so a value that does not reach the store is a setting
 * that silently does nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var store: InMemorySettingsStore
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        store = InMemorySettingsStore()
        viewModel = SettingsViewModel(store)
    }

    @After
    fun tearDown() {
        // Wait for work already handed to the IO dispatcher before the main
        // dispatcher is changed back.
        //
        // Closing a scope stops the coroutine, but a task already dispatched to
        // Dispatchers.IO still runs, and when it resumes it comes back to Main --
        // by then the next test's dispatcher, which fails as "Dispatchers.Main is
        // used concurrently with setting it", reported against whichever test
        // happened to be running. Queueing one more IO task and waiting for it
        // drains everything ahead of it.
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
    }

    @Test
    fun `the shipped defaults are what the screen starts with`() = runBlocking {
        val state = withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first() }
        assertEquals(InferenceBackend.AUTO, state.backend)
        assertEquals(512, state.maxReplyTokens)
        assertEquals(8, state.temperatureTenths)
        assertEquals(0.8, state.temperature, 0.0001)
        assertTrue("No prompt written means the shipped one", state.usingDefaultPrompt)
        assertEquals(Settings.DEFAULT_SYSTEM_PROMPT, state.effectiveSystemPrompt)
    }

    @Test
    fun `every setting reaches the store`() = runBlocking {
        viewModel.chooseBackend(InferenceBackend.CPU)
        viewModel.setSystemPrompt("Answer in one sentence.")
        viewModel.setMaxReplyTokens(256)
        viewModel.setTemperatureTenths(2)

        val saved = store.settings.value
        assertEquals("CPU", saved.backend)
        assertEquals("Answer in one sentence.", saved.systemPrompt)
        assertEquals(256, saved.maxReplyTokens)
        assertEquals(2, saved.temperatureTenths)
        assertEquals(0.2, saved.temperature, 0.0001)
    }

    @Test
    fun `an edited prompt is what is actually sent`() = runBlocking {
        viewModel.setSystemPrompt("Speak like a pirate.")

        val state = withTimeout(AWAIT_TIMEOUT_MS) {
            viewModel.state.first { it.effectiveSystemPrompt == "Speak like a pirate." }
        }
        assertFalse("It is no longer the default", state.usingDefaultPrompt)
    }

    @Test
    fun `resetting puts the shipped wording back without copying it into the file`() = runBlocking {
        // Stored empty rather than pre-filled, so a later change to the default
        // reaches a user who never edited it. A copy of the old wording would pin
        // them to text they never chose.
        viewModel.setSystemPrompt("Something of my own")
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.effectiveSystemPrompt == "Something of my own" } }

        viewModel.resetSystemPrompt()

        val state = withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first { it.usingDefaultPrompt } }
        assertEquals(Settings.DEFAULT_SYSTEM_PROMPT, state.effectiveSystemPrompt)
        assertEquals("Nothing is written for the default", "", store.settings.value.systemPrompt)
    }

    @Test
    fun `a prompt of only spaces counts as the default`() = runBlocking {
        viewModel.setSystemPrompt("   ")

        val state = withTimeout(AWAIT_TIMEOUT_MS) { viewModel.state.first() }
        assertTrue(state.usingDefaultPrompt)
        assertEquals(Settings.DEFAULT_SYSTEM_PROMPT, state.effectiveSystemPrompt)
    }

    @Test
    fun `the offered values are all usable`() {
        // A chip the control offers that the model cannot accept would be a button
        // that breaks the app when tapped.
        assertTrue(Settings.REPLY_LENGTHS.all { it > 0 })
        assertTrue(Settings.REPLY_LENGTHS.contains(Settings().maxReplyTokens))
        assertTrue(Settings.TEMPERATURES.all { it >= 0 })
        assertTrue(
            "A temperature of zero is legitimate: it means always the most likely word",
            Settings.TEMPERATURES.contains(0),
        )
    }

    @Test
    fun `a stored backend this build does not know reads as the default`() = runBlocking {
        val strange = SettingsViewModel(InMemorySettingsStore(Settings(backend = "QUANTUM")))

        val state = withTimeout(AWAIT_TIMEOUT_MS) { strange.state.first() }
        assertEquals(InferenceBackend.AUTO, state.backend)
    }

    // ------------------------------------------------- what a stored file may say ---

    @Test
    fun `a reply length outside the range is brought into it`() {
        // The settings file is an ordinary JSON file. A value of zero or a negative
        // one was handed straight to the model, where the result is whatever the
        // runtime does with it -- and the screen could explain neither.
        assertEquals(
            Settings.MAX_REPLY_TOKENS,
            Settings(maxReplyTokens = Int.MAX_VALUE).sanitised().maxReplyTokens,
        )
        assertEquals(
            Settings.MIN_REPLY_TOKENS,
            Settings(maxReplyTokens = 0).sanitised().maxReplyTokens,
        )
        assertEquals(
            Settings.MIN_REPLY_TOKENS,
            Settings(maxReplyTokens = -500).sanitised().maxReplyTokens,
        )
    }

    @Test
    fun `a temperature outside the range is brought into it`() {
        assertEquals(0, Settings(temperatureTenths = -100).sanitised().temperatureTenths)
        assertEquals(
            Settings.MAX_TEMPERATURE_TENTHS,
            Settings(temperatureTenths = 9_999).sanitised().temperatureTenths,
        )
    }

    @Test
    fun `a value inside the range is left exactly as it is`() {
        // Sanitising must not quietly normalise everything onto the offered chips:
        // a value another build wrote is still a value the user, or that build,
        // chose.
        val unusual = Settings(maxReplyTokens = 300, temperatureTenths = 3)
        assertEquals(unusual, unusual.sanitised())
    }

    @Test
    fun `a store hands back a value the model can actually be asked for`() = runBlocking {
        val file = java.io.File(java.nio.file.Files.createTempDirectory("settings-range").toFile(), "s.json")
        file.writeText("""{"maxReplyTokens":0,"temperatureTenths":-4,"backend":"CPU"}""")

        val stored = com.kayoyak.demoapp.data.settings.FileSettingsStore(file).settings.value

        assertEquals(Settings.MIN_REPLY_TOKENS, stored.maxReplyTokens)
        assertEquals(0, stored.temperatureTenths)
        assertEquals("A known field is still read", "CPU", stored.backend)
    }

    @Test
    fun `an error from the store is reported rather than swallowed`() = runBlocking {
        // A setting that silently did not save is one the user finds reverted the
        // next time they open the app, with nothing to explain it.
        val failing = object : com.kayoyak.demoapp.data.settings.SettingsStore {
            override val settings = kotlinx.coroutines.flow.MutableStateFlow(Settings())
            override suspend fun update(update: (Settings) -> Settings) {
                throw java.io.IOException("no space left on device")
            }
        }
        val vm = SettingsViewModel(failing)

        vm.setMaxReplyTokens(128)

        val state = withTimeout(AWAIT_TIMEOUT_MS) { vm.state.first { it.error != null } }
        assertNotNull(state.error)
        assertEquals(R.string.err_settings_save, state.error!!.textRes)
    }
}