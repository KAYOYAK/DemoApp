package com.kayoyak.demoapp.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Where a thinking model's output goes, and whether the app can see it.
 *
 * Written after a run of the app's own default catalogue model — Qwen3-0.6B, 4-bit —
 * came back empty six times in a row: `SamplerComparisonTest` measured 128 tokens per
 * sample and got no text, no reasoning and about 31 seconds, and `LiteRtLlmEngineTest`
 * failed on "generation produced no tokens". A second, plain build of the same model
 * then answered "Name one colour, one word only." with "red  apple" and produced no
 * visible text at all for "Count from one to ten". The model generates; the question
 * was where the text went.
 *
 * The runtime's [Message] has two places to put model output: `contents`, which the
 * app reads, and `channels`, a map of channel name to text, which it does not. Qwen3's
 * template declares a `thought` channel, so a model that spends its budget thinking
 * produces a reply that is empty in `contents` and full in `channels`. The app then
 * reports a reply in which there is nothing to show.
 *
 * The first test is the instrument: it prints, for one prompt, what the runtime
 * actually hands back, with the model's own thinking setting and with thinking switched
 * off. The second is the contract that has to hold either way — a run that produced
 * text must not come out of the engine empty.
 */
@RunWith(AndroidJUnit4::class)
class ThinkingChannelTest {

    /** The prefix every line this test prints carries, so a batch's log names it. */
    private val log = "THINKING"

    /** The prompt that produced nothing visible on the plain build of the same model. */
    private val prompt = "Count from one to ten, separating each number with a space."

    /**
     * What the app itself sends as the system instruction.
     *
     * A copy of `LiteRtLlmEngine`'s own fallback, because this test drives the runtime
     * directly and the point is to measure the app's configuration rather than a
     * convenient one. The two are equal for the app's default settings.
     */
    private val systemPrompt = "你是一个乐于助人的助手。"

    private fun cacheDir(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.filesDir, "inference-cache").apply { mkdirs() }
    }

    /**
     * A conversation configured the way the app configures one, with thinking either
     * left to the model's own template or switched off.
     *
     * The first arm is the setting that was in place when the catalogue's model
     * produced six empty samples; it is kept because the comparison is the finding.
     * The second is what `LiteRtLlmEngine.configFor` passes now. Nothing else differs
     * between the two, so a difference between the rows is caused by that flag.
     */
    private fun conversationConfig(thinkingOff: Boolean): ConversationConfig {
        val appConfig = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            samplerConfig = SamplerConfig(topK = 20, topP = 0.95, temperature = 0.8, seed = 0),
        )
        return if (thinkingOff) {
            appConfig.copy(thinkingConfig = ThinkingConfig(enableThinking = false))
        } else {
            appConfig
        }
    }

    @Test
    fun printsWhatTheRuntimeHandsBackForEachThinkingSetting() = runBlocking {
        val underTest = TestModel.orNull(log)
        if (underTest == null) {
            println("$log: SKIPPED (channel probe) - no model on this device")
            return@runBlocking
        }

        val engine = Engine(
            EngineConfig(
                modelPath = underTest.file.absolutePath,
                backend = Backend.CPU(),
                cacheDir = cacheDir().absolutePath,
            )
        )
        engine.initialize()

        try {
            for (thinkingOff in listOf(false, true)) {
                val setting = if (thinkingOff) "thinking off" else "model default"
                val conversation = engine.createConversation(conversationConfig(thinkingOff))
                var messages = 0
                var answerChars = 0
                val channelChars = LinkedHashMap<String, Int>()
                val partKinds = LinkedHashSet<String>()
                val answer = StringBuilder()
                val firstChannels = StringBuilder()
                try {
                    conversation.sendMessageAsync(Message.user(prompt), maxOutputToken = 256)
                        .collect { message ->
                            messages++
                            for (part in message.contents.contents) {
                                partKinds += part::class.simpleName ?: "?"
                                if (part is Content.Text) {
                                    answerChars += part.text.length
                                    if (answer.length < 120) answer.append(part.text)
                                }
                            }
                            for ((name, text) in message.channels) {
                                channelChars[name] = (channelChars[name] ?: 0) + text.length
                                if (firstChannels.length < 120) firstChannels.append('[').append(name).append(']').append(text)
                            }
                            if (messages <= 2) {
                                println(
                                    "$log: [$setting] message[$messages] role=${message.role} " +
                                        "parts=${message.contents.contents.map { it::class.simpleName }} " +
                                        "channels=${message.channels.mapValues { it.value.length }} " +
                                        "toolCalls=${message.toolCalls.size}",
                                )
                            }
                        }
                } finally {
                    runCatching { conversation.close() }
                }
                println(
                    "$log: [$setting] messages=$messages answerChars=$answerChars " +
                        "partKinds=$partKinds " +
                        "channels=${channelChars.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "none" }}",
                )
                println("$log: [$setting] answer=>>>${answer.toString().replace("\n", "\\n").take(120)}<<<")
                println("$log: [$setting] channel text=>>>${firstChannels.toString().replace("\n", "\\n").take(120)}<<<")
            }
        } finally {
            runCatching { engine.close() }
        }

        // A measurement rather than a verdict: the two rows are the finding, and the
        // channel column is what the app did not read until 2026-10-16.
        println("$log: the channel column is what the app did not read; compare the two rows")
    }

    /**
     * The reply must not be empty when the model produced something.
     *
     * This is the contract the engine exists to keep: it is a seam between the runtime
     * and the screen, and the screen has two places for model output — the answer and
     * the reasoning trace. A reply that carries neither, for a run in which the runtime
     * reported text on one channel or the other, means the app is looking at part of
     * what it was handed, and the user sees a model that said nothing.
     *
     * Measured red on this emulator before the engine read channels, with the catalogue's
     * own 4-bit Qwen3 and with the plain build of the same model.
     */
    @Test
    fun theEnginePassesOnTextTheRuntimeProduced() = runBlocking {
        val underTest = TestModel.orNull(log)
        if (underTest == null) {
            println("$log: SKIPPED - no model on this device")
            return@runBlocking
        }

        val engine = LiteRtLlmEngine(cacheDir = cacheDir())
        val model = LocalModel(
            id = underTest.id,
            displayName = underTest.displayName,
            file = underTest.file,
        )

        val backend = engine.load(model, InferenceBackend.CPU).getOrElse { error ->
            throw AssertionError("load failed for ${underTest.file.name}", error)
        }
        println("$log: loaded ${underTest.file.name} on $backend")

        val events = engine.generate(prompt, GenerationConfig(maxTokens = 256)).toList()
        val failure = events.filterIsInstance<GenerationEvent.Failed>().firstOrNull()
        val done = events.filterIsInstance<GenerationEvent.Done>().singleOrNull()

        assertTrue("generation failed: ${failure?.error?.message}", failure == null)
        assertTrue("the stream ended without a Done event", done != null)

        val reply = done!!.fullText
        println(
            "$log: reply=>>>${reply.replace("\n", "\\n").take(200)}<<< " +
                "reasoning=>>>${(done.reasoning ?: "").replace("\n", "\\n").take(200)}<<< " +
                "${done.tokenCount} fragment(s) in ${done.elapsedMillis} ms",
        )

        assertTrue(
            "the engine reported an empty reply for a run that produced text on this device " +
                "(model=${underTest.file.name}, backend=$backend): it carried neither an answer nor a " +
                "reasoning trace, so the screen had nothing to show and the model looked broken",
            reply.isNotBlank() || !done.reasoning.isNullOrBlank(),
        )

        engine.unload()
    }
}
