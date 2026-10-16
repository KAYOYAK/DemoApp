package com.kayoyak.demoapp.inference

/**
 * What to keep when a conversation does not fit in the window.
 *
 * ## Which of the four strategies this is, and why
 *
 * The literature on context management names four — a sliding window, priority
 * truncation, summary compression, and vector memory — and the sliding window is
 * the cheapest and the least clever. It suits conversations with little dependence
 * on what was said early, which is what a chat with a small on-device model is.
 *
 * The other three are not wrong; they cost something this app cannot pay. Summary
 * compression spends a generation on every trim, on a device where a generation is
 * ten seconds. Vector memory is a second model and a store, for a phone.
 * Priority truncation needs an importance mark on every message, and no small model
 * produces one worth trusting.
 *
 * ## What it must do that a naive window does not
 *
 * **Trim by tokens, not by turns.** One pasted log line fills a window whatever a
 * turn count says, and the turn-count version of this takes exactly the messages
 * that fit and then silently drops the biggest one.
 *
 * **Reserve room for what is not in the list.** The system prompt and the question
 * about to be asked are not messages yet when this runs. A window that fills with
 * history and then cannot fit the question is one that answers the wrong thing.
 *
 * **Keep the first exchange.** A sliding window over "what is a monad?" eventually
 * loses the question, and the model then continues a conversation about a subject
 * nobody named. The opening pair is kept out of the budget and always included.
 *
 * **Say what was dropped.** The failure this prevents — the model quietly getting
 * worse — has no symptom on screen unless the app reports it.
 */
object ContextBudget {

    /** Roughly characters to tokens. Chinese is denser per character than English. */
    private const val CHARS_PER_TOKEN = 2.0

    /**
     * Roughly how many tokens [text] costs the runtime.
     *
     * An estimate, deliberately. The exact count needs the model's own tokeniser
     * and a pass over every message, on every send; this is within a factor of two
     * and costs nothing. Reserving generously covers the difference, and the meter
     * on screen still shows the runtime's real figure once a reply has been made.
     */
    fun estimateTokens(text: String): Int =
        if (text.isEmpty()) 0 else (text.length / CHARS_PER_TOKEN).toInt() + 1

    /** The turns kept, and how many were left out. */
    data class Window(
        val turns: List<EngineTurn>,
        /** How many turns were dropped from the middle. Zero when everything fitted. */
        val dropped: Int,
    )

    /**
     * Chooses what to send.
     *
     * [messages] is the whole conversation in order, oldest first. [budgetTokens] is
     * how much of the model's window this call may use — the caller's business, not
     * this function's: `ChatViewModel` passes half the window, holding the rest back
     * for the system prompt and the question, and the reason for that figure is
     * recorded there.
     *
     * The newest turns are kept first, then the opening exchange. What goes is the
     * middle: the most recent context is what the next reply follows on from, and
     * the opening is what the conversation is about. The middle is what a sliding
     * window is for.
     *
     * ## There used to be a second parameter here
     *
     * `reservedTokens`, subtracted from [budgetTokens] before anything was kept. No
     * production caller ever gave it a value — the one caller passes zero and does
     * its reserving by asking for a smaller budget — so it subtracted nothing while
     * its own documentation called it load-bearing, and ten tests pinned behaviour
     * that only the tests produced.
     *
     * Removed rather than kept-and-explained. An earlier note here argued for
     * keeping it because removing it meant rewriting those tests; that was the wrong
     * way round. The tests were rewritten anyway, to express the same cases through
     * [budgetTokens] — which is what the reserve always was, arithmetic the caller
     * can do — and what went with the parameter was a second way to say one thing.
     *
     * The keep-the-newest-first loop below is the part that was ever hard, and its
     * own comment records the bug it replaced.
     */
    fun window(
        messages: List<EngineTurn>,
        budgetTokens: Int,
    ): Window {
        if (messages.isEmpty()) return Window(emptyList(), 0)

        val available = budgetTokens
        // A window that cannot hold anything, or is unknown. Sending nothing
        // would be worse than sending too much: the model would answer with no idea
        // what was asked.
        if (available <= 0) return Window(messages, 0)

        val cost = messages.map { estimateTokens(it.text) }
        if (cost.sum() <= available) return Window(messages, 0)

        val kept = mutableListOf<Int>()
        var spent = 0

        // Backwards from the newest, which is what the next reply follows on from.
        //
        // The newest turn is taken whether or not it fits on its own. An earlier
        // version stopped as soon as one did not, which for a conversation ending in a
        // long paste kept the old turns and dropped the new one -- exactly backwards,
        // and silent.
        for (index in messages.indices.reversed()) {
            val next = spent + cost[index]
            if (kept.isNotEmpty() && next > available) break
            kept += index
            spent = next
            if (spent > available) break
        }

        // Then the opening exchange, if what is left can hold it. This is what the
        // conversation is about: a window over "what is a monad?" that loses the
        // question leaves the model continuing a conversation about a subject nobody
        // named.
        val openingSize = minOf(2, messages.size)
        val openingCost = cost.take(openingSize).sum()
        if (openingSize !in kept && openingCost <= available - spent) {
            kept += (0 until openingSize)
        } else if (0 !in kept && cost[0] <= available - spent) {
            // Only the question fits, not its answer. A question alone is still the
            // subject; an answer alone is a reply to nothing.
            kept += 0
        }

        val ordered = kept.sorted()
        val turns = ordered.map { messages[it] }

        // Everything that was neither the opening nor the newest run. Counted rather
        // than listed: the screen says how many, and the message ids are not what a
        // reader needs.
        val dropped = messages.size - ordered.size
        return Window(turns, dropped)
    }
}
