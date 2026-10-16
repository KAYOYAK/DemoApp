package com.kayoyak.demoapp.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which heading a conversation sits under.
 *
 * The boundaries are the whole feature. A conversation filed under the wrong day is
 * one the user has to scan for, and the mistake is invisible until it happens at
 * 11pm to something they wrote at 11pm.
 */
class ConversationDatesTest {

    /** A fixed local midnight, so the test does not depend on when it runs. */
    private val midnight = ConversationDates.startOfDay(System.currentTimeMillis())
    private val hour = 60L * 60 * 1000
    private val day = 24L * hour

    @Test
    fun `something written an hour ago is today`() {
        assertEquals(
            DateBucket.TODAY,
            ConversationDates.bucket(midnight + hour, midnight + 2 * hour),
        )
    }

    @Test
    fun `something written just before midnight is yesterday, minutes later`() {
        // The boundary that matters. With a rolling 24 hours this would still read
        // "today" at 00:05, while the app's own clock had moved on.
        val lateLastNight = midnight - hour

        assertEquals(
            DateBucket.YESTERDAY,
            ConversationDates.bucket(lateLastNight, midnight + 5 * 60 * 1000),
        )
    }

    @Test
    fun `something written just after midnight is today`() {
        assertEquals(
            DateBucket.TODAY,
            ConversationDates.bucket(midnight + 60 * 1000, midnight + 2 * hour),
        )
    }

    @Test
    fun `three days ago is within the week`() {
        assertEquals(
            DateBucket.LAST_SEVEN_DAYS,
            ConversationDates.bucket(midnight - 3 * day, midnight + hour),
        )
    }

    @Test
    fun `the day before yesterday is the first thing outside yesterday`() {
        // Where "yesterday" ends and "the last seven days" begins.
        assertEquals(
            DateBucket.LAST_SEVEN_DAYS,
            ConversationDates.bucket(midnight - 2 * day, midnight + hour),
        )
    }

    @Test
    fun `ten days ago is within the month`() {
        assertEquals(
            DateBucket.LAST_THIRTY_DAYS,
            ConversationDates.bucket(midnight - 10 * day, midnight + hour),
        )
    }

    @Test
    fun `a year ago is earlier`() {
        assertEquals(
            DateBucket.EARLIER,
            ConversationDates.bucket(midnight - 365 * day, midnight + hour),
        )
    }

    @Test
    fun `a conversation moves as time passes, and nothing is rewritten`() {
        // The point of computing this rather than storing it: the same timestamp,
        // two different readings, and no migration in between.
        val written = midnight + hour

        assertEquals(DateBucket.TODAY, ConversationDates.bucket(written, midnight + 2 * hour))
        assertEquals(DateBucket.YESTERDAY, ConversationDates.bucket(written, midnight + day))
        assertEquals(DateBucket.LAST_SEVEN_DAYS, ConversationDates.bucket(written, midnight + 5 * day))
    }

    @Test
    fun `a timestamp in the future is today, not a category of its own`() {
        // A device whose clock moved backwards, or a conversation opened on one
        // device and read on another. There is no bucket for "tomorrow", and
        // inventing one would put a conversation somewhere the user cannot predict.
        assertEquals(
            DateBucket.TODAY,
            ConversationDates.bucket(midnight + 5 * day, midnight + hour),
        )
    }

    @Test
    fun `groups keep the order they were given`() {
        // The list is already sorted the way it wants to be -- pinned first, archived
        // last. Sorting here would silently undo that.
        val items = listOf(
            "pinned and old" to (midnight - 20 * day),
            "pinned and new" to (midnight + hour),
            "ordinary" to (midnight - 2 * day),
        )

        val grouped = ConversationDates.group(items, { it.second }, now = midnight + 2 * hour)

        assertEquals(
            listOf(DateBucket.TODAY, DateBucket.LAST_SEVEN_DAYS, DateBucket.LAST_THIRTY_DAYS),
            grouped.map { it.first },
        )
        assertEquals("pinned and new", grouped[0].second.single().first)
    }

    @Test
    fun `a group with nothing in it is not emitted`() {
        // An empty heading is a heading that says nothing and takes a line.
        val items = listOf("today" to (midnight + hour))

        val grouped = ConversationDates.group(items, { it.second }, now = midnight + 2 * hour)

        assertEquals(1, grouped.size)
        assertEquals(DateBucket.TODAY, grouped.single().first)
    }

    @Test
    fun `an empty list produces no groups`() {
        assertTrue(ConversationDates.group(emptyList<String>(), { 0L }).isEmpty())
    }

    @Test
    fun `the buckets are in reading order`() {
        // Enum order is the order they are shown in, and a group emitted out of order
        // puts a month-old conversation above one from this morning.
        assertEquals(
            listOf(
                DateBucket.TODAY,
                DateBucket.YESTERDAY,
                DateBucket.LAST_SEVEN_DAYS,
                DateBucket.LAST_THIRTY_DAYS,
                DateBucket.EARLIER,
            ),
            DateBucket.entries.toList(),
        )
    }
}
