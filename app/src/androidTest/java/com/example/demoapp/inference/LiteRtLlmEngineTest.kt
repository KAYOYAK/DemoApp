package com.example.demoapp.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.demoapp.model.ModelCatalogue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises [LlmEngine] on a real device against a real model.
 *
 * This tests the abstraction the application actually uses, not the underlying
 * library API. That distinction matters: the seam exists so the runtime can be
 * replaced, and a test written against the library would have to be rewritten
 * along with it.
 *
 * The model is not in the repository (see docs/PRINCIPLES.md -- weights are
 * never bundled), so the test finds it in one of two places, in this order:
 *
 *  1. `files/models/` inside the app, where the application's own download puts
 *     it. This is the faithful path and the one to use when it is available:
 *
 *         adb push Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm /data/local/tmp/
 *         adb shell "run-as com.example.demoapp sh -c \
 *           'cat /data/local/tmp/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm \
 *            > files/models/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm'"
 *
 *  2. Any `.litertlm` in `/data/local/tmp/models`, which is for a machine that
 *     has a variant of the model but not that exact file. The engine still
 *     loads real weights and generates real tokens; what is not exercised is the
 *     catalogue's file. The log says which one was used.
 *
 * With neither present the model tests report SKIPPED and pass, so a run without
 * a 344 MB download is not broken. Read the log rather than trusting a green
 * result: a skipped run proves nothing.
 *
 * **This file was written but never compiled or run.** It named `smollm2-360m`,
 * which left the catalogue when the app narrowed to Chinese models, and the run
 * that finally happened reported `Model 'smollm2-360m' is no longer in the
 * catalogue` three times. The lesson is in [modelOrNull] rather than in a
 * resolution to be more careful: a test that cannot run must SAY it did not run,
 * and this one asserted its way past the skip.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtLlmEngineTest {

    /** Whichever catalogue entry the tests exercise: the first, and the smallest. */
    private val MODEL_ID = "qwen3-0.6b-int4"

    /** Where the model went the last time somebody set this machine up. */
    private val handDroppedDir = File("/data/local/tmp/models")

    /**
     * The model this run can actually exercise, with the name to log it under.
     *
     * The catalogue entry is the point: it is the file the application itself
     * would download, in the directory the application keeps it in, and a test
     * that used something else would be testing a different program. It is also
     * 344 MB fetched over the network, so a run on a machine that never did that
     * download would answer no questions at all.
     *
     * Hence the fallback. A variant of the same model, left where somebody put it
     * by hand, still exercises the thing under test -- the runtime loading weights
     * and generating tokens -- while not exercising the catalogue's exact file.
     * The return value carries `catalogueFile = false` so the log says which of
     * the two happened rather than leaving it to be inferred from the name.
     *
     * Returns null when neither is there, and then every model test skips with a
     * printed reason. Note that this cannot throw for a stale id: the previous
     * version did, which turned "nothing to test" into a red build.
     */
    private fun modelOrNull(): ModelUnderTest? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as com.example.demoapp.DemoApplication).container

        val descriptor = ModelCatalogue.byId(MODEL_ID)
        if (descriptor == null) {
            println("LITERT-ENGINE: SKIPPED - '$MODEL_ID' is not in the catalogue any more")
            return null
        }

        val catalogue = container.modelStore.modelFile(descriptor)
        if (catalogue.isFile && catalogue.length() > 0L) {
            return ModelUnderTest(MODEL_ID, descriptor.displayName, catalogue, catalogueFile = true)
        }

        val handDropped =
            handDroppedDir.listFiles()
                ?.filter { it.isFile && it.length() > 0L && it.name.endsWith(".litertlm") }
                ?.sortedBy { it.length() }
                ?.firstOrNull()
        if (handDropped != null) {
            println(
                "LITERT-ENGINE: NOTE - ${descriptor.fileName} is not installed; " +
                    "falling back to the hand-dropped ${handDropped.name} " +
                    "(${handDropped.length()} bytes). The engine is exercised; " +
                    "the catalogue's own file is not.",
            )
            return ModelUnderTest(handDropped.name, handDropped.name, handDropped, catalogueFile = false)
        }

        println(
            "LITERT-ENGINE: SKIPPED - neither ${descriptor.fileName} in the app's " +
                "files/models, nor any .litertlm in $handDroppedDir",
        )
        return null
    }

    private data class ModelUnderTest(
        val id: String,
        val displayName: String,
        val file: File,
        val catalogueFile: Boolean,
    )

    private fun newEngine(): LiteRtLlmEngine {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as com.example.demoapp.DemoApplication).container
        // The same directory the application passes in production.
        return LiteRtLlmEngine(cacheDir = File(context.filesDir, "inference-cache").apply { mkdirs() })
    }

    /**
     * The backend the engine reports must be one that actually works.
     *
     * This is the test that would have caught the real-device failure where a
     * phone without an OpenCL library was told its GPU was fine: `initialize()`
     * returns successfully there, and the missing library only surfaces on the
     * first inference. Without this, a device was marked Ready and the user's
     * first message came back as a native status code.
     *
     * Asserting the answer is not 2 (failed) is the point, not asserting the
     * backend is a particular one -- which backend works is a property of the
     * device, and the engine is allowed to fall back.
     */
    @Test
    fun theBackendItReportsReadyIsOneThatCanActuallyGenerate() = runBlocking {
        val underTest = modelOrNull()
        if (underTest == null) {
            println("LITERT-ENGINE: SKIPPED (backend readiness) - no model on this device")
            return@runBlocking
        }

        val engine = newEngine()
        val model = LocalModel(
            id = underTest.id,
            displayName = underTest.displayName,
            file = underTest.file,
        )

        val backend = engine.load(model, InferenceBackend.AUTO).getOrElse { error ->
            throw AssertionError(
                "load() reported failure, so a fallback that should have worked did not: " +
                    "${error.message}",
                error,
            )
        }
        println("LITERT-ENGINE: probe says $backend can generate on this device")

        // And prove it with a real request on the engine that was just declared
        // ready. A backend that passes the probe and fails here means the probe
        // is not exercising what it claims to.
        val events = engine.generate("hello", GenerationConfig(maxTokens = 4)).toList()
        val failure = events.filterIsInstance<GenerationEvent.Failed>().firstOrNull()
        assertTrue(
            "The backend $backend was reported Ready and then failed to generate: " +
                "${failure?.error?.message}",
            failure == null,
        )
        assertTrue(
            "A generation must end with Done",
            events.any { it is GenerationEvent.Done },
        )

        engine.unload()
    }

    @Test
    fun loadsModelGeneratesAndStreams() = runBlocking {
        val underTest = modelOrNull()
        if (underTest == null) {
            println("LITERT-ENGINE: SKIPPED - no model on this device")
            return@runBlocking
        }

        val engine = newEngine()
        val model = LocalModel(
            id = underTest.id,
            displayName = underTest.displayName,
            file = underTest.file,
        )

        // ---- load -----------------------------------------------------------
        val loadResult = engine.load(model, InferenceBackend.AUTO)
        println("LITERT-ENGINE: load -> $loadResult")
        val backendUsed = loadResult.getOrElse { error ->
            throw AssertionError("load() failed", error)
        }
        println("LITERT-ENGINE: backend actually used = $backendUsed")

        val ready = engine.state.value
        assertTrue("state must be Ready after a successful load", ready is EngineState.Ready)
        assertEquals(model.id, (ready as EngineState.Ready).modelId)

        // ---- generate -------------------------------------------------------
        val events = engine.generate("Name one colour, one word only.").toList()

        val tokens = events.filterIsInstance<GenerationEvent.Token>()
        val done = events.filterIsInstance<GenerationEvent.Done>().singleOrNull()
        val failures = events.filterIsInstance<GenerationEvent.Failed>()

        println("LITERT-ENGINE: ${tokens.size} token event(s)")
        tokens.take(12).forEachIndexed { i, t -> println("LITERT-ENGINE:   token[$i] = >>>${t.text}<<<") }
        println("LITERT-ENGINE: done = $done")
        failures.forEach { println("LITERT-ENGINE: FAILED -> ${it.error}") }

        assertTrue("generation must not report failure", failures.isEmpty())
        assertTrue("generation produced no tokens", tokens.isNotEmpty())

        val completed = done ?: throw AssertionError("no Done event; the stream ended without completing")
        assertTrue("Done carried no text", completed.fullText.isNotBlank())

        // The contract of the delta logic: concatenating every Token must
        // reproduce exactly what Done reports. If the two disagree, the UI
        // would render something different from what the engine believes it
        // produced, and the test above would still pass.
        val joined = tokens.joinToString(separator = "") { it.text }
        assertEquals(
            "Token stream does not reconstruct Done.fullText",
            completed.fullText,
            joined,
        )

        println("LITERT-ENGINE: reply = >>>${completed.fullText}<<<")
        println("LITERT-ENGINE: ${completed.tokenCount} tokens in ${completed.elapsedMillis} ms")
        println("LITERT-ENGINE: PASSED")

        // ---- unload ---------------------------------------------------------
        engine.unload()
        assertTrue("state must return to Idle after unload", engine.state.value is EngineState.Idle)
        println("LITERT-ENGINE: unload OK")
    }

    @Test
    fun generateWithoutLoadedModelFailsCleanly() = runBlocking {
        val engine = newEngine()
        val events = engine.generate("This should not work.").toList()
        val failure = events.filterIsInstance<GenerationEvent.Failed>().singleOrNull()
        assertTrue("generating with no model must emit Failed, not throw", failure != null)
        println("LITERT-ENGINE: no-model failure reported as ${failure!!.error::class.simpleName}")
    }

    /**
     * Determines whether generation actually streams.
     *
     * This is not a correctness test, it is a question the UI design depends on.
     * A one-word reply is a single token, so the previous test cannot tell
     * "streams incrementally" apart from "arrives in one piece at the end".
     * If it arrives in one piece, a token-by-token animation would be a lie and
     * the screen should show a spinner instead.
     *
     * Prints the gap between token events: a genuinely streaming runtime shows
     * many small gaps, a buffered one shows a single gap followed by all the
     * tokens at once.
     */
    @Test
    fun reportsWhetherGenerationActuallyStreams() = runBlocking {
        val underTest = modelOrNull()
        if (underTest == null) {
            println("LITERT-ENGINE: SKIPPED (streams probe) - no model on this device")
            return@runBlocking
        }

        val engine = newEngine()
        engine.load(
            LocalModel(underTest.id, underTest.displayName, underTest.file),
            InferenceBackend.CPU,
        ).getOrElse { throw AssertionError("load failed", it) }

        val started = System.currentTimeMillis()
        var lastAt = started
        val arrivals = mutableListOf<Pair<Long, String>>()
        val seenKinds = mutableListOf<String>()

        engine.generate(
            "Count from one to ten, separating each number with a space.",
            GenerationConfig(maxTokens = 64),
        ).collect { event ->
            seenKinds += event.javaClass.simpleName
            if (event is GenerationEvent.Token) {
                val now = System.currentTimeMillis()
                arrivals += (now - lastAt) to event.text
                lastAt = now
            }
        }
        // Printed before the verdict because a verdict computed from an empty list
        // cannot be told apart from a collector that never ran.
        println("LITERT-ENGINE: streams probe - ${arrivals.size} Token event(s) out of $seenKinds")
        if (seenKinds.none { it == "Token" }) {
            println("LITERT-ENGINE: streams probe - no Token arrived, so this run cannot answer the question")
            engine.unload()
            return@runBlocking
        }
        arrivals.take(20).forEach { (gap, text) ->
            println("LITERT-ENGINE:   +${gap}ms  >>>$text<<<")
        }

        if (arrivals.size <= 1) {
            println("LITERT-ENGINE: VERDICT = buffered. The flow delivers the whole reply at once.")
            println("LITERT-ENGINE:   UI consequence: show a progress indicator, not a token animation.")
        } else {
            val gaps = arrivals.drop(1).map { it.first }
            val average = gaps.average().toLong()
            println("LITERT-ENGINE: VERDICT = streaming. $average ms average gap between tokens.")
            println("LITERT-ENGINE:   UI consequence: incremental rendering is honest here.")
        }

        engine.unload()
    }
}
