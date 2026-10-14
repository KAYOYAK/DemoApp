package com.example.demoapp.diagnostics

/**
 * Cutting a reply into pieces the speech engine will accept.
 *
 * ## Why this is not `chunked(4000)`
 *
 * The platform refuses more than 4000 characters in one `speak()` call --
 * `TextToSpeech.getMaxSpeechInputLength()` is a constant, not a suggestion. A long
 * answer has to be spoken in several.
 *
 * Where the cut falls is the whole design. Splitting at four thousand characters
 * lands wherever that is: mid-word, mid-number, mid-sentence. The engine is given
 * each piece independently, so it puts a full stop's worth of pause at every
 * boundary, and a number split across two pieces is read as two numbers.
 *
 * So the cut goes at a sentence ending, then a clause, then a space, and only then
 * anywhere at all. A reply whose sentences are all longer than the limit still gets
 * spoken; it just gets the ugly cut that has nowhere better to go.
 *
 * ## Why the limit is not read from the platform here
 *
 * The caller passes it. `getMaxSpeechInputLength()` needs no context but does need
 * the platform class, and this file is the part worth testing -- a chunk boundary
 * that lands one character late is a bug that only shows up as a word repeated
 * across a pause.
 */
object SpeechChunker {

    /** The platform's own figure. Passed in rather than imported, so this stays testable. */
    const val PLATFORM_LIMIT = 4000

    /** Where a piece would rather end, best first. */
    private val BREAKS = listOf(
        // A sentence, in both the scripts this app sees.
        Regex("(?<=[.!?。！？…])"),
        // A clause.
        Regex("(?<=[;；,，、:：])"),
        // Anywhere with a space.
        Regex("(?<=\\s)"),
    )

    /**
     * Splits [text] into pieces no longer than [limit].
     *
     * Returns an empty list for text that is empty or has nothing but whitespace: a
     * caller that queues a piece with no words in it has asked the engine to speak
     * silence, and some engines answer that with an error.
     */
    fun split(text: String, limit: Int = PLATFORM_LIMIT): List<String> {
        if (limit <= 0) return emptyList()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= limit) return listOf(trimmed)

        val pieces = mutableListOf<String>()
        var rest = trimmed

        while (rest.length > limit) {
            val window = rest.substring(0, limit)
            val cut = bestBreak(window, limit)
            // Always progress. A break returning zero would loop forever on a wall
            // of text with no punctuation in it.
            val take = if (cut <= 0) limit else cut
            pieces += rest.substring(0, take).trim()
            rest = rest.substring(take).trimStart()
        }
        if (rest.isNotEmpty()) pieces += rest

        return pieces.filter { it.isNotBlank() }
    }

    /**
     * Where to cut inside [window], or the limit when nowhere is better.
     *
     * Searched from the end backwards, and never before halfway: a piece that ended
     * at the first sentence would be a fraction of what the engine can take, and a
     * paragraph of short sentences would become forty utterances with a pause
     * between each.
     */
    private fun bestBreak(window: String, limit: Int): Int {
        val earliest = limit / 2
        for (pattern in BREAKS) {
            val at = pattern.findAll(window)
                .map { it.range.last + 1 }
                .filter { it in earliest..limit }
                .maxOrNull()
            if (at != null) return at
        }
        return limit
    }
}
