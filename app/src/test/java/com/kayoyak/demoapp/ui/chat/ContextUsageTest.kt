package com.kayoyak.demoapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The context meter, and the ways a meter is worse than nothing.
 *
 * A number on screen is believed. Every case here is a way this one could be
 * wrong without looking wrong.
 */
class ContextUsageTest {

    private fun usage(tokens: Int?, window: Int?) =
        ChatUiState(contextTokens = tokens, contextWindow = window).contextUsage

    @Test
    fun `both numbers are reported together`() {
        assertEquals(1200 to 4096, usage(tokens = 1200, window = 4096))
    }

    @Test
    fun `nothing is shown when the runtime did not report a window`() {
        // A model whose metadata this build cannot read still runs. Showing
        // "1200 / ?" is worse than showing nothing: it is a number with no meaning
        // attached, and the user has no way to tell it is unmeasurable rather than
        // infinite.
        assertNull(usage(tokens = 1200, window = null))
    }

    @Test
    fun `nothing is shown when the runtime did not report a count`() {
        assertNull(usage(tokens = null, window = 4096))
    }

    @Test
    fun `a zero window is treated as no window`() {
        // A model file that reports zero has not told us anything, and dividing by
        // it is the shape of bug that produces NaN on a screen.
        assertNull(usage(tokens = 10, window = 0))
    }

    @Test
    fun `the count is clamped to the window`() {
        // Reported against a real tool: after the runtime discarded the oldest
        // messages, the count was read from before it did, and the panel showed
        // 355.8k of 262.1k. A gauge past its own maximum destroys trust in the
        // whole number, and there is no way to interpret it.
        assertEquals(4096 to 4096, usage(tokens = 5_000, window = 4096))
    }

    @Test
    fun `an empty conversation reads as empty`() {
        assertEquals(0 to 4096, usage(tokens = 0, window = 4096))
    }

    @Test
    fun `a negative count from the runtime shows nothing at all`() {
        // The runtime should not report one. If it does, its measurement is broken,
        // and neither option is a number to put on screen: "-3 / 4096" is obviously
        // wrong, and "0 / 4096" claims an empty conversation that is not empty.
        assertNull(usage(tokens = -3, window = 4096))
    }
}
