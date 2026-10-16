package com.kayoyak.demoapp.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing what to send when a conversation does not fit.
 *
 * The failure this prevents has no symptom: the model gets worse, the user assumes
 * the model is bad, and nothing on screen says the conversation outgrew the window
 * six replies ago. So the tests are about the three ways a naive window goes wrong
 * rather than about the arithmetic.
 */
class ContextBudgetTest {

    private fun turn(user: Boolean, text: String) = EngineTurn(fromUser = user, text = text)

    /** Roughly [tokens] worth of text under the estimator's own figure. */
    private fun sized(tokens: Int) = "x".repeat(tokens * 2)

    @Test
    fun `everything is sent when it all fits`() {
        val messages = listOf(turn(true, "hi"), turn(false, "hello"))

        val window = ContextBudget.window(messages, budgetTokens = 3584)

        assertEquals(messages, window.turns)
        assertEquals(0, window.dropped)
    }

    @Test
    fun `an empty conversation stays empty`() {
        val window = ContextBudget.window(emptyList(), budgetTokens = 3584)

        assertTrue(window.turns.isEmpty())
        assertEquals(0, window.dropped)
    }

    @Test
    fun `the newest turns are the ones kept`() {
        // What the next reply follows on from.
        val messages = listOf(
            turn(true, sized(50)),
            turn(false, sized(50)),
            turn(true, sized(400)),
            turn(false, sized(400)),
            turn(true, sized(400)),
            turn(false, sized(400)),
        )

        val window = ContextBudget.window(messages, budgetTokens = 400)

        assertTrue(
            "The newest is kept",
            window.turns.last() == messages.last(),
        )
        assertTrue("Something was dropped", window.dropped > 0)
    }

    @Test
    fun `the opening exchange is kept whatever else goes`() {
        // A window over "what is a monad?" eventually loses the question, and the
        // model then continues a conversation about a subject nobody named.
        val opening = turn(true, "what is a monad")
        val messages = buildList {
            add(opening)
            add(turn(false, sized(50)))
            repeat(20) { add(turn(it % 2 == 0, sized(300))) }
        }

        val window = ContextBudget.window(messages, budgetTokens = 500)

        assertEquals("The question survives", opening, window.turns.first())
        assertTrue("And the newest", window.turns.last() == messages.last())
    }

    @Test
    fun `the middle is what goes, not a prefix`() {
        // A naive window takes from the front and loses the end; a turn-count one
        // takes the first N turns and loses the recent context. Neither is what a
        // conversation needs.
        val messages = buildList {
            add(turn(true, "the opening question"))
            add(turn(false, "the opening answer"))
            repeat(10) { add(turn(it % 2 == 0, sized(200))) }
            add(turn(true, "the newest question"))
        }

        val window = ContextBudget.window(messages, budgetTokens = 600)

        assertTrue(window.turns.contains(messages.first()))
        assertTrue(window.turns.contains(messages.last()))
        assertTrue("Something in the middle went", window.dropped > 0)
    }

    @Test
    fun `the dropped count is what is actually missing`() {
        val messages = buildList { repeat(12) { add(turn(it % 2 == 0, sized(200))) } }

        val window = ContextBudget.window(messages, budgetTokens = 500)

        assertEquals(messages.size - window.turns.size, window.dropped)
    }

    @Test
    fun `room is held back for the system prompt and the question`() {
        // The reserve is not in the list yet when this runs. A window that fills with
        // history and then cannot fit the question answers the wrong thing.
        val messages = buildList { repeat(10) { add(turn(it % 2 == 0, sized(200))) } }

        val generous = ContextBudget.window(messages, budgetTokens = 1000)
        val tight = ContextBudget.window(messages, budgetTokens = 2800)

        assertTrue(
            "Less is kept when more is reserved",
            generous.turns.size < tight.turns.size,
        )
    }

    @Test
    fun `a window that cannot hold the reserve sends everything rather than nothing`() {
        // Sending nothing would be worse than sending too much: the model would answer
        // with no idea what was asked.
        val messages = listOf(turn(true, "a genuinely long question"), turn(false, "an answer"))

        val window = ContextBudget.window(messages, budgetTokens = -400)

        assertEquals(messages, window.turns)
    }

    @Test
    fun `an unknown window size sends everything`() {
        // A model whose window could not be read is not a model with no window.
        val messages = listOf(turn(true, "hi"))

        assertEquals(messages, ContextBudget.window(messages, budgetTokens = 0).turns)
    }

    @Test
    fun `a single oversized message is still sent`() {
        // Dropping it would leave the model answering nothing at all -- the question
        // is the message that does not fit.
        val messages = listOf(turn(true, sized(5000)))

        val window = ContextBudget.window(messages, budgetTokens = 400)

        assertEquals(messages, window.turns)
    }

    @Test
    fun `the estimate grows with the text`() {
        assertTrue(
            ContextBudget.estimateTokens(sized(400)) > ContextBudget.estimateTokens(sized(100)),
        )
        assertEquals(0, ContextBudget.estimateTokens(""))
    }

    @Test
    fun `an estimate is never zero for text that has something in it`() {
        // A message that cost nothing would be kept forever by any budget.
        assertTrue(ContextBudget.estimateTokens("a") >= 1)
    }

    @Test
    fun `the kept turns are in the original order`() {
        // The runtime reads a conversation, not a bag of turns. Reordering them would
        // make the model answer the wrong message.
        val messages = buildList {
            add(turn(true, "first"))
            add(turn(false, "second"))
            repeat(10) { add(turn(it % 2 == 0, sized(300))) }
            add(turn(true, "last"))
        }

        val window = ContextBudget.window(messages, budgetTokens = 700)

        val order = window.turns.map { messages.indexOf(it) }
        assertEquals(order.sorted(), order)
    }
}
