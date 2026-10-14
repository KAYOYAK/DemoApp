package com.example.demoapp.diagnostics

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a crash left behind, and where.
 *
 * A crash handler that only writes to logcat is a handler that captures nothing:
 * this app has no telemetry and the system log is not something a user can hand
 * over. The file here is what makes a report possible at all.
 *
 * One file, overwritten. A crash loop would otherwise fill the device with the
 * same stack trace, and the report a user wants to send is the most recent one
 * anyway.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    /**
     * Writes [text], replacing whatever was there.
     *
     * Never throws. It runs inside `Thread.setDefaultUncaughtExceptionHandler`,
     * where an exception would replace the user's crash with this one and lose
     * both.
     */
    fun record(directory: File, text: String) {
        runCatching {
            directory.mkdirs()
            File(directory, FILE_NAME).writeText(text.take(MAX_CHARS))
        }
    }

    /** The last crash, or null when there has not been one. */
    fun read(directory: File): String? =
        runCatching {
            File(directory, FILE_NAME).takeIf { it.isFile }?.readText()?.ifBlank { null }
        }.getOrNull()

    /**
     * Deletes the stored crash.
     *
     * ## Not called, and that is the decision rather than an omission
     *
     * The doc here used to read "called once the crash has been included in a
     * report", which described a plan nothing ever carried out — a report does not
     * consume what it was given.
     *
     * Sharing a report is a thing the *user* asked for, and an answer to it should
     * not destroy data. Clearing the file would mean that a user who sends one
     * report and is asked for another has to reproduce the crash to provide it,
     * which for an intermittent fault may not be possible at all.
     *
     * Nothing is ambiguous without it. The report labels this section "最近一次崩溃"
     * — the *last* crash, not the current one — and [describe] stamps the text with
     * when it happened, so a reader can see that a trace is days old. Re-sending a
     * stale crash is visible as stale rather than misleading.
     *
     * Kept as a function because the eraser needs it conceptually and a future
     * "forget the crash" control would need it literally; the reason it is unused is
     * recorded here rather than left to be re-derived from a search with no hits.
     */
    fun clear(directory: File) {
        runCatching { File(directory, FILE_NAME).delete() }
    }

    /**
     * A stack trace plus the little context the handler can gather.
     *
     * The device facts are here rather than in the report because a crash happens
     * before anything is loaded: asking the engine which model was running at that
     * moment is asking a process that is already dying.
     */
    fun describe(error: Throwable, at: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))
        val trace = error.stackTraceToString()
        return buildString {
            appendLine("时间 $stamp")
            appendLine("异常 ${error::class.java.name}")
            appendLine()
            appendLine(trace)
        }
    }

    /** Enough to see the shape of a stack trace, not enough to fill the disk. */
    private const val MAX_CHARS = 64 * 1024
}
