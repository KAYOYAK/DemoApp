package com.example.demoapp.ui.history

import java.util.Calendar

/**
 * Which heading a conversation sits under.
 *
 * ## Why the buckets are these
 *
 * Today, yesterday, the last seven days, the last thirty, earlier. It is what the
 * chat interfaces people already use do, and the reason is that it matches how
 * someone looks for a conversation: "the thing I was working on yesterday" rather
 * than "the thing from the 14th".
 *
 * ## Why the boundaries are midnight, not a rolling 24 hours
 *
 * "Yesterday" has to mean the calendar day before this one, not 24 hours ago. With
 * a rolling window, a conversation from 11pm would move out of "yesterday" at 11pm
 * tonight -- in the middle of the evening, while the user is looking at it -- and
 * "yesterday" would contain things from two different days at once.
 *
 * ## Why a conversation moves
 *
 * A conversation from this morning is under Today, and tomorrow it is under
 * Yesterday. Nothing was rewritten; the heading is a function of the current time.
 * That is what makes it need no stored field and no migration -- and it is why this
 * is computed rather than saved.
 */
enum class DateBucket {
    TODAY,
    YESTERDAY,
    LAST_SEVEN_DAYS,
    LAST_THIRTY_DAYS,
    EARLIER,
}

object ConversationDates {

    /** Which heading [then] falls under, seen from [now]. */
    fun bucket(then: Long, now: Long): DateBucket {
        val startOfToday = startOfDay(now)
        val startOfYesterday = startOfToday - ONE_DAY

        return when {
            // Anything from today. Not "within 24 hours": something written at 11pm
            // is still from today at 11:01pm.
            then >= startOfToday -> DateBucket.TODAY

            then >= startOfYesterday -> DateBucket.YESTERDAY

            // Seven days counted from the start of today, so the boundary is a
            // midnight like the two above rather than a rolling moment.
            then >= startOfToday - SEVEN_DAYS -> DateBucket.LAST_SEVEN_DAYS

            then >= startOfToday - THIRTY_DAYS -> DateBucket.LAST_THIRTY_DAYS

            else -> DateBucket.EARLIER
        }
    }

    /**
     * The start of the day [at] falls in, in the device's own zone.
     *
     * Device-local on purpose. A user in UTC+8 who wrote something at 00:30 means
     * today, and grouping by UTC would file it under yesterday.
     */
    fun startOfDay(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * Groups [items] in order, keeping each bucket's own order.
     *
     * The input is assumed to be already sorted the way the list wants it -- pinned
     * first, archived last, newest within each. Sorting here would silently undo
     * that, which is why it does not.
     */
    fun <T> group(
        items: List<T>,
        updatedAt: (T) -> Long,
        now: Long = System.currentTimeMillis(),
    ): List<Pair<DateBucket, List<T>>> =
        DateBucket.entries
            .map { bucket -> bucket to items.filter { bucket(updatedAt(it), now) == bucket } }
            .filter { it.second.isNotEmpty() }

    private const val ONE_DAY = 24L * 60 * 60 * 1000
    private const val SEVEN_DAYS = 7 * ONE_DAY
    private const val THIRTY_DAYS = 30 * ONE_DAY
}
