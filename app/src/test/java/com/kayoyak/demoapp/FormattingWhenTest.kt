package com.kayoyak.demoapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `whenAgo` is the only place an instant becomes a distance, and the boundaries
 * are where it goes wrong: an off-by-one at one hour reads as zero hours, and a
 * negative elapsed time reads as a time in the future.
 *
 * The words are not asserted here any more. They live in the string resources
 * with every other word the app shows, and this decides only which of them
 * applies -- which is the part that has boundaries and therefore the part worth
 * testing.
 */
class FormattingWhenTest {

    private val now = 1_760_000_000_000L
    private fun ago(millis: Long) = whenAgo(now - millis, now)

    @Test
    fun `just now`() {
        assertEquals(WhenAgo.JustNow, ago(0))
        assertEquals(WhenAgo.JustNow, ago(59_999))
    }

    @Test
    fun `minutes`() {
        assertEquals(WhenAgo.Minutes(1), ago(60_000))
        assertEquals(WhenAgo.Minutes(59), ago(59 * 60_000L))
    }

    @Test
    fun `hours`() {
        assertEquals(WhenAgo.Hours(1), ago(60 * 60_000L))
        assertEquals(WhenAgo.Hours(23), ago(23 * 3_600_000L + 59 * 60_000L))
    }

    @Test
    fun `days and weeks`() {
        assertEquals(WhenAgo.Days(1), ago(86_400_000L))
        assertEquals(WhenAgo.Days(2), ago(2 * 86_400_000L))
        assertEquals(WhenAgo.Days(6), ago(6 * 86_400_000L))
        assertEquals(WhenAgo.Weeks(1), ago(7 * 86_400_000L))
        assertEquals(WhenAgo.Weeks(4), ago(28 * 86_400_000L))
    }

    @Test
    fun `a clock that moved backwards says nothing rather than something wrong`() {
        // A conversation stamped in the future -- the device clock was corrected,
        // or the file came from another device. "In three minutes" is worse than a
        // blank, because it looks like a fact.
        assertEquals(WhenAgo.Unknown, whenAgo(now + 180_000L, now))
    }

    @Test
    fun `every case the renderer must handle is produced by this function`() {
        // The renderer is an exhaustive `when`, so a case this never returns is a
        // branch nobody has ever seen, and a case it returns that the renderer
        // lacks would not compile. This names the set, so adding one is a decision
        // rather than a discovery.
        val produced = listOf(
            ago(0),
            ago(60_000),
            ago(3_600_000),
            ago(2 * 86_400_000L),
            ago(8 * 86_400_000L),
            whenAgo(now + 1, now),
        )

        val kinds = produced.map { it::class }.toSet()
        assertEquals(
            "Every shape of WhenAgo appears: $produced",
            6,
            kinds.size,
        )
    }
}
