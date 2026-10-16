package com.kayoyak.demoapp.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Measures the one sampling question this project has not answered.
 *
 * `docs/ROADMAP.md` carries the item: `topK` was brought from 40 to 20 on the
 * publisher's recommendation for the smallest models here, and nothing in this
 * project has checked that it helps. The benchmark cannot: it runs at
 * `temperature = 0.0`, where decoding is greedy and the sampler -- `topK`,
 * `topP`, the seed together -- does not participate at all. A measurement needs
 * a temperature above zero, and this is it.
 *
 * What it does: the app's own benchmark prompt, two settings, three seeds each,
 * everything recorded -- the text, the tokens, the time and a repetition count.
 * What it does not do: decide. Three samples per setting can show that two
 * settings differ observably; it cannot establish which is better, and the
 * timings are an emulator's CPU, which is not a phone. The summary says both of
 * those things rather than leaving them to the reader.
 *
 * The engine takes `topK`, `topP`, `temperature` and the seed together at load
 * time ([LlmEngine.load]'s `sampling`), which is why each sample reloads: a seed
 * that never changes makes every sample identical, and identical samples measure
 * nothing. This is also why the test asserts that the samples within a setting
 * are not all the same text -- if they are, the seed or the temperature did not
 * reach the decoder and the rest of the numbers mean nothing.
 *
 * **Both channels are measured.** These catalogue models are reasoning models:
 * `docs/ROADMAP.md` records the first real run of the catalogue's Qwen3 ending in
 * `Done(fullText=, elapsedMillis=45779, tokenCount=4)`, which is a reply whose
 * text channel is empty because everything the model wrote went into its
 * reasoning. A comparison that counted only the visible answer would call that
 * "nothing was generated" and measure six empty strings; so the answer and the
 * reasoning are recorded and printed separately, and the repetition count is
 * taken over both.
 *
 * **Which model it runs on, and why that one is named.** The first run of this
 * test, on 2026-10-16, loaded the catalogue entry `TestModel` prefers -- the
 * 4-bit Qwen3 -- and got six samples of zero tokens, with an empty reasoning
 * channel as well. The engine's own test failed the same way on the same model
 * (`generation produced no tokens`, `LiteRtLlmEngineTest.kt:142`), which makes
 * the silence the model and this emulator rather than the sampler. To measure
 * anything at all the comparison names the one model on this machine whose
 * output can be read, and [TestModel] logs it when that name is not on the
 * device and the catalogue entry answers instead.
 *
 * A run without a model on the device prints SKIPPED and passes, like the engine
 * tests; read the log rather than trusting a green result.
 */
@RunWith(AndroidJUnit4::class)
class SamplerComparisonTest {

    /** The prefix every line this test prints carries, so a batch's log names it. */
    private val log = "SAMPLER"

    /**
     * The two settings under comparison: what the runtime did before this app
     * changed it, and what this app asks for now ([SamplingProfile.BALANCED]).
     */
    private val topKValues = listOf(40, 20)

    /**
     * Independent samples per setting.
     *
     * Three, because the question is "does a tighter cut show up in the text"
     * and not "by how much"; each one costs a reload, which on this device is
     * most of the wall clock.
     */
    private val seeds = listOf(1, 2, 3)

    /** Long enough to reach an answer, short enough to finish several of them. */
    private val maxTokens = 128

    /** Above zero, so the sampler participates; the app's own default is 0.8. */
    private val temperature = 0.8

    /** The app's own default, so the comparison moves one value and not two. */
    private val topP = 0.95

    /**
     * The file this comparison loads, named rather than left to the catalogue.
     *
     * See the class comment: the catalogue's 4-bit Qwen3 answered with nothing on
     * the emulator this was measured on, so the run names the model it can read.
     * When the file is absent, [TestModel] falls through to the catalogue entry
     * and says so, and a silent model then fails the first assertion below
     * instead of being reported as a clean result.
     */
    private val modelFile = "SmolLM2_360M_instruct.litertlm"

    @Test
    fun comparesTheTighterTopKAtATemperatureThatUsesIt() = runBlocking {
        val underTest = TestModel.orNull(log, fileName = modelFile)
        if (underTest == null) {
            println("$log: SKIPPED - no model on this device, so the comparison cannot run")
            return@runBlocking
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The prompt is read from the app rather than written here, so this
        // measures the same sentence the benchmark measures.
        val prompt = context.getString(com.kayoyak.demoapp.R.string.models_benchmark_prompt)
        val engine = LiteRtLlmEngine(cacheDir = File(context.filesDir, "inference-cache").apply { mkdirs() })

        println("$log: model=${underTest.id} (${underTest.file.length()} bytes, catalogue=${underTest.catalogueFile})")
        println("$log: prompt=>>>$prompt<<<")
        println("$log: topK ${topKValues.joinToString(" vs ")} - $maxTokens max tokens, temperature=$temperature, topP=$topP, seeds=$seeds")

        val samples = mutableListOf<Sample>()
        try {
            for (topK in topKValues) {
                for (seed in seeds) {
                    samples += sample(engine, underTest, prompt, topK, seed)
                }
            }
        } finally {
            runCatching { engine.unload() }
        }

        report(samples)

        assertTrue(
            "no sample produced any text on either channel, so nothing was measured:\n" +
                samples.joinToString("\n") { it.line() },
            samples.any { it.combined().isNotBlank() },
        )
        assertTrue(
            "the sampler never varied its words: every sample in a setting came out identical, " +
                "which means the seed or the temperature did not reach the decoder and the numbers above " +
                "say nothing about topK. Re-run before reading them.",
            topKValues.all { topK ->
                samples.filter { it.topK == topK }.map { it.combined() }.distinct().size > 1
            },
        )
    }

    /** One load, one sample, one unload-by-next-load. */
    private suspend fun sample(
        engine: LiteRtLlmEngine,
        underTest: TestModel.UnderTest,
        prompt: String,
        topK: Int,
        seed: Int,
    ): Sample {
        val sampling =
            GenerationConfig(
                maxTokens = maxTokens,
                temperature = temperature,
                topK = topK,
                topP = topP,
                seed = seed,
            )
        val loaded = engine.load(LocalModel(underTest.id, underTest.displayName, underTest.file), sampling = sampling)
        val backend = loaded.getOrElse { cause -> throw AssertionError("load failed for topK=$topK seed=$seed", cause) }

        // A fresh context per sample: the previous answer must not become part
        // of the next one's input, or the samples are a sequence and not samples.
        engine.resetConversation()

        val events = engine.generate(prompt, GenerationConfig(maxTokens = maxTokens)).toList()
        val done = events.filterIsInstance<GenerationEvent.Done>().singleOrNull()
        val failures = events.filterIsInstance<GenerationEvent.Failed>()
        if (done == null || failures.isNotEmpty()) {
            throw AssertionError(
                "generation did not finish for topK=$topK seed=$seed: " +
                    "done=$done, failures=${failures.map { it.error }}",
            )
        }

        val sample =
            Sample(
                topK = topK,
                seed = seed,
                backend = backend.toString(),
                text = done.fullText.trim(),
                reasoning = done.reasoning?.trim().orEmpty(),
                tokenCount = done.tokenCount,
                elapsedMillis = done.elapsedMillis,
            )
        println("$log: ${sample.line()}")
        return sample
    }

    /** Prints every number this run produced, and what it cannot support. */
    private fun report(samples: List<Sample>) {
        println("$log: ---- summary ----")
        for (topK in topKValues) {
            val group = samples.filter { it.topK == topK }
            val repeated = group.map { it.repetition() }
            val tokensPerSecond = group.mapNotNull { it.tokensPerSecond() }
            println(
                "$log: topK=$topK -> ${group.size} sample(s), " +
                    "distinct texts=${group.map { it.combined() }.distinct().size}, " +
                    "median repeat ratio=${median(repeated)}, " +
                    "median chars=${median(group.map { it.combined().length.toDouble() })}, " +
                    "median tokens/s=${if (tokensPerSecond.isEmpty()) "n/a" else median(tokensPerSecond)}",
            )
        }
        println(
            "$log: what these numbers are: an emulator's CPU, one prompt, ${seeds.size} samples per setting, " +
                "temperature fixed at $temperature. They can show that the two settings differ; " +
                "they cannot say which one writes better, and they are not a phone's timings.",
        )
    }

    private fun median(values: List<Double>): String {
        if (values.isEmpty()) return "n/a"
        val sorted = values.sorted()
        val middle = sorted.size / 2
        val value = if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
        return String.format(java.util.Locale.US, "%.3f", value)
    }

    /** One generated answer and what it cost. */
    private data class Sample(
        val topK: Int,
        val seed: Int,
        val backend: String,
        val text: String,
        val reasoning: String,
        val tokenCount: Int,
        val elapsedMillis: Long,
    ) {

        /**
         * Everything the model wrote, answer and reasoning together.
         *
         * This is what a repetition has to be counted over: a model that thinks
         * in circles repeats itself on the channel it thinks on, and the two
         * channels are one generation either way.
         */
        fun combined(): String = (text + "\n" + reasoning).trim()

        fun tokensPerSecond(): Double? =
            if (elapsedMillis <= 0L || tokenCount <= 0) null else tokenCount * 1000.0 / elapsedMillis

        /**
         * The share of the generation's character 8-grams that occur more than
         * once, over [combined].
         *
         * The reason a tighter `topK` is said to help is that a small model can
         * fall into repeating itself; this is that failure made countable. Eight
         * characters is short enough that a repeated phrase counts and long
         * enough that ordinary Chinese prose rarely repeats by accident.
         */
        fun repetition(): Double {
            val body = combined()
            if (body.length < WINDOW) return 0.0
            val windows = body.length - WINDOW + 1
            val counts = HashMap<String, Int>(windows)
            for (start in 0..body.length - WINDOW) {
                val window = body.substring(start, start + WINDOW)
                counts[window] = (counts[window] ?: 0) + 1
            }
            val repeated = counts.count { it.value > 1 }
            return repeated.toDouble() / windows
        }

        fun line(): String =
            "topK=$topK seed=$seed backend=$backend | $tokenCount tokens in ${elapsedMillis} ms " +
                "(${tokensPerSecond()?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "n/a"} tok/s) | " +
                "repeat=${String.format(java.util.Locale.US, "%.3f", repetition())} | " +
                "chars=${text.length} answer + ${reasoning.length} reasoning | " +
                ">>>$text<<<" +
                if (reasoning.isBlank()) "" else " | reasoning=>>>$reasoning<<<"
    }

    private companion object {
        /** The repeated-phrase window, in characters. */
        const val WINDOW = 8
    }
}
