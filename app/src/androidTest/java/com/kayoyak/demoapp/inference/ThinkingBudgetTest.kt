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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What a thinking budget buys, measured on questions whose answers can be read.
 *
 * `ThinkingConfig(enableThinking = false)` is what the app passes, and it is what turned
 * the catalogue's own model from a silent one into one that answers: with its template's
 * thinking on and nothing bounding it, the model spent whole generations in the `thought`
 * channel and `contents` stayed empty, which the app reported as a model that said
 * nothing. Turning thinking off costs whatever the model gains from thinking, so the
 * question this test asks is whether bounding it keeps the gain.
 *
 * The runtime does bound it, and the budget is applied on this model rather than ignored:
 * with `thinkingTokenBudget = 32` the `thought` channel held 49 to 53 characters across
 * four questions, where the same first question unbounded had held 951. A larger budget
 * was measured too: 128 left a 210-character trace, ran the generation into the token cap
 * and took 65 s against 41 s. So both settings work, and the rows below are the answer to
 * which one the app should use.
 *
 * **The answers are the evidence, and there is no automated column for them.** An earlier
 * version asked whether the answer contained the expected digit, and it was wrong: the
 * wrong answer to the first question -- `18 - 12 + 5 = 1` -- contains a 5 while restating
 * the question, so the column said 4 of 4 for an arm that had 3 of 4. Reading the printed
 * answers is what found the wrong ones, which is the same reason `ChatTurnTest` prints its
 * answers instead of judging them.
 */
@RunWith(AndroidJUnit4::class)
class ThinkingBudgetTest {

    /** The prefix every line this test prints carries, so a batch's log names it. */
    private val log = "BUDGET"

    /** One question, and what answering it tests. */
    private data class Question(val prompt: String, val tests: String)

    /**
     * Four questions a small model can plausibly get right, so that a wrong answer is
     * information rather than noise and a difference between settings means something.
     * The first is deliberately the hard one -- inclusion-exclusion with the answer
     * stated nowhere in the question -- and the other three are not.
     */
    private val questions = listOf(
        Question(
            "一个班有 30 名学生：18 人参加游泳，12 人参加骑车，5 人两项都参加。" +
                "两项都没参加的有多少人？只回答一个数字。",
            "inclusion-exclusion, 30 - (18 + 12 - 5), answer 5",
        ),
        Question("3 个苹果和 2 个苹果一共几个？只回答一个数字。", "addition, answer 5"),
        Question("如果今天是星期三，后天是星期几？只回答一个词。", "weekday arithmetic, answer 星期五"),
        Question("一本书 12 元，买 4 本要多少钱？只回答一个数字。", "multiplication, answer 48"),
    )

    /** What the app itself sends as the system instruction. */
    private val systemPrompt = "你是一个乐于助人的助手。"

    /** One setting to measure. */
    private data class Arm(val label: String, val thinking: ThinkingConfig)

    /**
     * The two rows, and the reason each one is here.
     *
     * `off` is the app's current setting and the control: if it answered nothing, the
     * run would say nothing about the budget beside it. `budget 32` differs only in that
     * thinking is on and bounded.
     */
    private val arms = listOf(
        Arm("off", ThinkingConfig(enableThinking = false)),
        Arm("budget 32", ThinkingConfig(enableThinking = true, thinkingTokenBudget = 32)),
    )

    private fun cacheDir(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.filesDir, "inference-cache").apply { mkdirs() }
    }

    /** A conversation configured the way the app configures one, plus the arm's thinking. */
    private fun configFor(thinking: ThinkingConfig): ConversationConfig =
        ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            samplerConfig = SamplerConfig(topK = 20, topP = 0.95, temperature = 0.8, seed = 0),
            thinkingConfig = thinking,
        )

    @Test
    fun comparesTwoThinkingSettingsOnQuestionsWhoseAnswersCanBeRead() = runBlocking {
        val underTest = TestModel.orNull(log)
        if (underTest == null) {
            println("$log: SKIPPED - no model on this device")
            return@runBlocking
        }
        println("$log: model=${underTest.file.name} (${underTest.file.length()} bytes)")

        val engine = Engine(
            EngineConfig(
                modelPath = underTest.file.absolutePath,
                backend = Backend.CPU(),
                cacheDir = cacheDir().absolutePath,
            )
        )
        engine.initialize()

        var answeredWithThinkingOff = false
        try {
            for (arm in arms) {
                var totalMillis = 0L
                for ((index, question) in questions.withIndex()) {
                    val conversation = engine.createConversation(configFor(arm.thinking))
                    var messages = 0
                    var answerChars = 0
                    val channelChars = LinkedHashMap<String, Int>()
                    val answer = StringBuilder()
                    val started = System.currentTimeMillis()
                    try {
                        conversation.sendMessageAsync(Message.user(question.prompt), maxOutputToken = 256)
                            .collect { message ->
                                messages++
                                for (part in message.contents.contents) {
                                    if (part is Content.Text) {
                                        answerChars += part.text.length
                                        answer.append(part.text)
                                    }
                                }
                                for ((name, text) in message.channels) {
                                    channelChars[name] = (channelChars[name] ?: 0) + text.length
                                }
                            }
                    } finally {
                        runCatching { conversation.close() }
                    }
                    val elapsed = System.currentTimeMillis() - started
                    totalMillis += elapsed

                    println(
                        "$log: [${arm.label}] q${index + 1} (${question.tests}) $elapsed ms " +
                            "messages=$messages answerChars=$answerChars " +
                            "channels=${channelChars.entries.joinToString { "${it.key}=${it.value}" }
                                .ifEmpty { "none" }}",
                    )
                    println(
                        "$log: [${arm.label}] q${index + 1} said=>>>" +
                            "${answer.toString().takeLast(80).replace("\n", "\\n")}<<<",
                    )
                    if (arm.label == "off" && index == 0 && answerChars > 0) answeredWithThinkingOff = true
                }
                println("$log: [${arm.label}] four questions in ${totalMillis} ms")
            }
        } finally {
            runCatching { engine.close() }
        }

        assertTrue(
            "the arm with thinking off produced no answer text (model=${underTest.file.name}), " +
                "so this run cannot say anything about the budget beside it",
            answeredWithThinkingOff,
        )
    }
}
