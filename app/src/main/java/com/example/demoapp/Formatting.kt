package com.example.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.demoapp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How long ago something happened, as a value rather than a sentence.
 *
 * Used by the history list, where the exact time of a chat matters far less than
 * how long ago it was. Deliberately coarse: three days is what someone scanning a
 * list wants, and a precise timestamp on every row is noise.
 *
 * A value, not a string, so the words live in the string resources with the rest
 * of them. This function used to return "3 days ago", which meant the one place
 * that decides how time is described was also the one place whose copy could not
 * be found by searching the resources.
 */
sealed interface WhenAgo {
    /** The clock moved backwards, or the file was written in the future. */
    data object Unknown : WhenAgo

    data object JustNow : WhenAgo

    /** Minutes are never 1, `JustNow` covers that. */
    data class Minutes(val count: Long) : WhenAgo
    data class Hours(val count: Long) : WhenAgo
    data class Days(val count: Long) : WhenAgo
    data class Weeks(val count: Long) : WhenAgo
}

/**
 * @param then the instant to describe, in milliseconds since the epoch.
 * @param now the reference instant. A parameter so the result is testable.
 */
fun whenAgo(then: Long, now: Long = System.currentTimeMillis()): WhenAgo {
    val elapsed = now - then
    return when {
        // Better to say nothing than to say "in -3 minutes".
        elapsed < 0L -> WhenAgo.Unknown
        elapsed < 60_000L -> WhenAgo.JustNow
        elapsed < 3_600_000L -> WhenAgo.Minutes(elapsed / 60_000L)
        elapsed < 86_400_000L -> WhenAgo.Hours(elapsed / 3_600_000L)
        elapsed < 7L * 86_400_000L -> WhenAgo.Days(elapsed / 86_400_000L)
        else -> WhenAgo.Weeks(elapsed / (7L * 86_400_000L))
    }
}

/**
 * Formatting for sizes, speeds and durations.
 *
 * Sizes use binary units (1 GB = 1024 MB) because that is what Android's own
 * storage screens report, and a user comparing this app against Settings would
 * otherwise see two different numbers for the same file.
 *
 * Locale-independent on purpose: these strings are shown next to progress bars
 * and must not reflow or change separator when the device language changes.
 */
private const val KB = 1024.0
private const val MB = KB * 1024
private const val GB = MB * 1024

fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024L -> "$bytes B"
    bytes < MB.toLong() -> String.format(Locale.US, "%.0f KB", bytes / KB)
    bytes < GB.toLong() -> String.format(Locale.US, "%.0f MB", bytes / MB)
    else -> String.format(Locale.US, "%.1f GB", bytes / GB)
}

/** Transfer rate, e.g. "2.4 MB/s". Returns an empty string when unknown. */
fun formatRate(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0L) "" else "${formatBytes(bytesPerSecond)}/s"

/**
 * A duration, in the terms a person would say out loud.
 *
 * A value rather than a string, so the words come from the string resources and
 * the arithmetic stays testable without a Context. [Unknown] rather than a zero:
 * "0 min" is a claim about the future that a throughput estimate cannot make.
 */
sealed interface Duration {
    /** Not known yet. Rendered as nothing at all. */
    data object Unknown : Duration

    data object UnderAMinute : Duration

    data class Minutes(val minutes: Long) : Duration

    data class Hours(val hours: Long, val minutes: Long) : Duration
}

/**
 * Estimates a duration, coarsely.
 *
 * Deliberately coarse: a precise "00:04:37" implies a confidence a throughput
 * estimate does not have, and "约 5 分钟" is both easier to read and more honest.
 */
fun durationOf(seconds: Long): Duration = when {
    seconds < 0L -> Duration.Unknown
    seconds < 60L -> Duration.UnderAMinute
    seconds < 3600L -> Duration.Minutes(seconds / 60)
    else -> Duration.Hours(seconds / 3600, (seconds % 3600) / 60)
}

/** The words for a [Duration], in the app's language. */
@Composable
fun Duration.describe(): String? = when (this) {
    Duration.Unknown -> null
    Duration.UnderAMinute -> stringResource(R.string.time_less_than_a_minute)
    is Duration.Minutes -> stringResource(R.string.time_about_minutes, minutes)
    is Duration.Hours ->
        if (minutes == 0L) {
            stringResource(R.string.time_about_hours, hours)
        } else {
            stringResource(R.string.time_about_hours_minutes, hours, minutes)
        }
}

/** A percentage, e.g. "42%". */
fun formatPercent(fraction: Float): String =
    String.format(Locale.US, "%.0f%%", (fraction * 100f).coerceIn(0f, 100f))

/**
 * The time of day a message was sent, e.g. "14:07".
 *
 * No date: a message's date is its conversation's, and repeating it on every row
 * is noise. Not a string resource either -- a clock is digits in every language
 * this app could be in, and a translatable one would invite a translation that
 * loses the 24-hour form the rest of the screen reads in.
 *
 * The formatter is built per call rather than held. `SimpleDateFormat` is not
 * thread-safe, and a shared instance used from a composable is a data race that
 * shows up as one row with the wrong time and never reproduces.
 */
/**
 * Tokens per second, or null when there is nothing honest to divide.
 *
 * The number people actually compare between models. Elapsed time alone says how
 * long they waited; the rate says what they are getting for it, and two models on
 * the same prompt differ by a factor of three on a phone.
 *
 * Null rather than zero when the runtime did not report a count or the reply
 * finished inside a millisecond: "0 tok/s" is a claim, and it would be a wrong one.
 *
 * The number only, without the unit. "tok/s" was written here, in `ChatScreen`, and
 * again when a conversation is serialised -- three places for one word, and the one
 * a person reads was the one no translation layer could reach. The unit belongs to
 * the sentence around it and lives in `chat_rate` with the rest of the copy.
 */
fun tokensPerSecond(tokenCount: Int?, elapsedMillis: Long?): String? {
    if (tokenCount == null || tokenCount <= 0) return null
    if (elapsedMillis == null || elapsedMillis <= 0L) return null
    val rate = tokenCount * 1000.0 / elapsedMillis
    // Locale-independent on purpose: this is a number, and the decimal separator is
    // a formatting decision rather than a translation one. Chinese uses the same
    // full stop, and a locale that used a comma would put one inside a figure that
    // is then read next to temperatures and percentages.
    return String.format(Locale.US, "%.1f", rate)
}

fun clockTime(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMillis))

/**
 * How long ago, in words.
 *
 * Here rather than in the history screen because it is the other half of
 * [whenAgo]: the two must agree about what the cases are, and a `when` in a
 * screen would silently stop covering one if another were added.
 *
 * A `@Composable` because the words are resources. That is the price of putting
 * the copy where the rest of the copy is, and it is worth paying once.
 */
@Composable
fun WhenAgo.describe(): String = when (this) {
    WhenAgo.Unknown -> ""
    WhenAgo.JustNow -> stringResource(R.string.history_when_just_now)
    is WhenAgo.Minutes -> stringResource(R.string.history_when_minutes, count.toInt())
    is WhenAgo.Hours -> stringResource(R.string.history_when_hours, count.toInt())
    is WhenAgo.Days -> stringResource(R.string.history_when_days, count.toInt())
    is WhenAgo.Weeks -> stringResource(R.string.history_when_days, (count * 7).toInt())
}