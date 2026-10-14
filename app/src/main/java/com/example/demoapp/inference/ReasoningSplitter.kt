package com.example.demoapp.inference

/**
 * Separates a reasoning trace from the answer, across a stream.
 *
 * Reasoning models -- DeepSeek-R1, QwQ, Qwen3, and others -- emit their working in
 * a marked block before the answer. The runtime usually routes that into a named
 * channel and it never reaches this class. When it does not, the block arrives as
 * ordinary text and the user sees the model thinking out loud with the answer
 * buried inside it.
 *
 * ## Why this is a state machine and not a replace
 *
 * The text arrives in fragments, and a marker arrives in pieces. Given `<think>`
 * split as `<thi` + `nk>`, a per-fragment replace finds nothing in either half and
 * both are shown. The same split across `</think>` loses the end of the block and
 * the rest of the reply is swallowed.
 *
 * So this holds back the longest tail that could still turn out to be the start of
 * a marker, and emits the rest. The cost is that the visible text can lag by up to
 * one marker's length minus one character, which is under ten characters and not
 * noticeable against a token stream.
 *
 * ## The cases it has to get right
 *
 * - A marker split across fragments, in either the opening or the closing half.
 * - A block that never closes. The text inside is held and never emitted as the
 *   answer: an unterminated block means the model was still reasoning when it
 *   stopped, and the fragment in hand is not the answer.
 * - A near miss. `<thinking out loud>` begins with `<think` and is not the marker;
 *   a fragment that only matches a prefix must be released once the next character
 *   rules it out.
 * - Fragments that are empty or whitespace, which reasoning models produce a lot of.
 *
 * Not thread-safe, and not meant to be: one instance belongs to one reply.
 */
internal class ReasoningSplitter(private val markers: List<Marker> = DEFAULT_MARKERS) {

    /** An opening and closing tag pair. */
    data class Marker(val open: String, val close: String)

    private val answer = StringBuilder()
    private val reasoning = StringBuilder()

    /** Text that may be the start of a marker; not yet known to be one. */
    private var held = ""

    /** The closing tag of the block currently open, or null when outside one. */
    private var openUntil: String? = null

    /** The answer so far, without the reasoning. */
    val visible: String get() = answer.toString()

    /** The reasoning so far, or empty when the model did not emit any. */
    val trace: String get() = reasoning.toString()

    /** True when the model reasoned but has not answered yet. */
    val reasoningWithoutAnswer: Boolean get() = reasoning.isNotEmpty() && answer.isBlank()

    /**
     * Feeds one fragment.
     *
     * The return value is what the caller may append to the answer it is already
     * showing. It is usually the fragment, sometimes empty, and sometimes shorter.
     */
    fun accept(fragment: String): String {
        if (fragment.isEmpty()) return ""
        val before = answer.length
        // Taken, not read. Leaving it in place re-scans it on the next fragment and
        // every held character is emitted twice -- which is what the tests this was
        // built against caught on the first run.
        val carry = held
        held = ""
        scan(carry + fragment)
        return answer.substring(before)
    }

    /**
     * Releases anything held back, and reports whether a block was left open.
     *
     * Called once the stream ends. Without it the tail of a reply that finished
     * mid-marker-prefix would never appear.
     */
    fun finish(): String {
        val tail = held
        held = ""
        // Whatever is left is not a marker -- if it were, scan would have consumed
        // it. Released to whichever side the stream was on.
        if (openUntil != null) {
            reasoning.append(tail)
        } else {
            answer.append(tail)
        }
        return tail
    }

    private fun scan(text: String) {
        var index = 0
        while (index < text.length) {
            val until = openUntil
            if (until == null) {
                val opening = markers.firstOrNull { text.startsWith(it.open, index) }
                if (opening != null) {
                    openUntil = opening.close
                    index += opening.open.length
                    continue
                }
                // Could this position still become a marker with more characters?
                val partial = markers.any { it.open.startsWith(text.substring(index)) }
                if (partial) {
                    // Held, not emitted. finish() releases it if no marker follows.
                    held = text.substring(index)
                    return
                }
                answer.append(text[index])
                index++
            } else {
                if (text.startsWith(until, index)) {
                    openUntil = null
                    index += until.length
                    continue
                }
                // The same test as the opening: could what is left become the
                // closing tag with more characters, or has it already diverged?
                // `<thinking` diverges from `</think>` immediately and is released;
                // `</thi` has not yet diverged and is held.
                if (until.startsWith(text.substring(index))) {
                    held = text.substring(index)
                    return
                }
                reasoning.append(text[index])
                index++
            }
        }
    }

    companion object {
        /**
         * The tags reasoning models actually emit.
         *
         * `<think>` covers DeepSeek-R1, QwQ and the Qwen3 family. The channel pair
         * is Gemma's, taken from the same set Ollama's own model metadata uses.
         *
         * Order matters: a longer tag that begins with a shorter one has to be
         * tried first, or the short one matches and the rest is treated as content.
         */
        val DEFAULT_MARKERS = listOf(
            Marker("<|channel>", "<channel|>"),
            Marker("<think>", "</think>"),
            Marker("<thinking>", "</thinking>"),
            Marker("<reasoning>", "</reasoning>"),
        )
    }
}
