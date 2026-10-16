package com.kayoyak.demoapp.inference

/**
 * Assembles the runtime's stream of message fragments into a reply.
 *
 * The contract with the inference runtime, verified against it on a device: each
 * emission from the streaming API is a FRAGMENT of the reply, and concatenating
 * every fragment reproduces the whole text. This class is the single place that
 * contract is implemented, so it is also the single place a unit test has to pin
 * it down.
 *
 * An earlier version tried to accept cumulative emissions as well, by checking
 * whether an emission began with the text accumulated so far and, if so, taking
 * only the extension. That check misfires on fragments. After "a", a fragment
 * "abc" begins with "a" and is longer, so it was treated as cumulative and only
 * "bc" was emitted -- "a" was dropped from the reply the user saw.
 *
 * [LiteRtLlmEngineTest] asserts that concatenating the emitted tokens equals the
 * text the runtime reports at the end. A runtime that changed to cumulative
 * emissions would fail that assertion loudly instead of silently duplicating
 * text here.
 */
internal class ReplyAssembler {

    private val builder = StringBuilder()

    /**
     * Splits a reasoning trace out of the fragments before they are shown.
     *
     * Here rather than downstream because this is where the fragment contract is
     * implemented, and because both things built from the fragments -- the streaming
     * display and the finished reply -- have to see the same text. Filtering at
     * either end only would leave the user watching a thought arrive and then seeing
     * it vanish, or reading it a second time in the finished message.
     */
    private val splitter = ReasoningSplitter()

    /** Everything emitted so far, with any reasoning trace removed. */
    val text: String get() = builder.toString()

    /**
     * The model's reasoning, or empty when it did not produce any.
     *
     * Kept rather than discarded. A user who asked a hard question and got a wrong
     * answer has no way to tell a model that misfired from one that never understood
     * the question, and this is the only evidence that distinguishes them.
     */
    val reasoning: String get() = splitter.trace

    /** The model reasoned and never reached an answer. */
    val reasoningWithoutAnswer: Boolean get() = splitter.reasoningWithoutAnswer

    val isEmpty: Boolean get() = builder.isEmpty()

    /** Number of fragments received, for reporting. */
    var fragmentCount: Int = 0
        private set

    /**
     * Records [fragment] and returns what may be shown.
     *
     * Returning the fragment rather than computing a difference is the whole
     * point: fragments are the unit the runtime reports, and any attempt to
     * infer a larger structure from them can only lose text.
     *
     * What comes back is usually the fragment, sometimes empty, and sometimes
     * shorter: a fragment ending mid-tag is held until the next one says whether it
     * was a tag at all.
     */
    fun add(fragment: String): String {
        if (fragment.isEmpty()) return fragment
        fragmentCount++
        val visible = splitter.accept(fragment)
        builder.append(visible)
        return visible
    }

    /**
     * Records text the runtime routed into a named channel.
     *
     * Not the answer, and never appended to it: a channel is the runtime saying "this is
     * not the reply". The app has one place for text that is not the answer -- the
     * reasoning trace, which the screen shows above the answer and keeps closed by
     * default -- so that is where this goes, whatever the channel is called.
     *
     * Measured rather than assumed. Qwen3's template declares a `thought` channel, and
     * on this project's emulator the catalogue's own 4-bit Qwen3 produced 128 tokens per
     * sample that were all channel text: `Message.contents` came back empty, the app read
     * only that, and six samples in a row looked like a model that generates nothing.
     * See [ThinkingChannelTest].
     */
    fun addChannelText(text: String) {
        if (text.isEmpty()) return
        fragmentCount++
        splitter.record(text)
    }

    /**
     * Releases whatever was held back when the stream ends.
     *
     * A reply that finishes on a character which could have been the start of a tag
     * would otherwise lose its last characters with no explanation.
     */
    fun finish(): String {
        val released = splitter.finish()
        builder.append(released)
        return released
    }
}
