package com.kayoyak.demoapp.data.chat

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The two sizes a conversation transfer has to fit through.
 *
 * Both are limits the platform imposes and neither is visible from the screen that
 * hits them: an `Intent` extra travels inside a Binder transaction, and a file the
 * user picks can be any size at all. They are written down here, with the
 * arithmetic, because both failures are silent until they are not -- a share that
 * throws `TransactionTooLargeException` on the main thread takes the app with it,
 * and a read with no limit holds the whole file in memory on the way to the parser.
 */
object BackupTransfer {

    /**
     * The most text this app will hand to a share.
     *
     * A Binder transaction carries about a megabyte, and a `String` costs two bytes
     * per character inside one, so this is roughly 600 KB -- half the budget, which
     * leaves the chooser and the intent's other extras their room. Past it
     * `startActivity` throws rather than reporting anything, and the throw is on the
     * main thread.
     */
    const val MAX_SHARE_CHARS = 300_000

    /**
     * The most of a picked file this app will read.
     *
     * A whole library exported by this app is a few megabytes, so this is far above
     * any file it wrote and far below the size at which holding the bytes in memory
     * would matter. It exists for the other kind of file: the one picked by mistake,
     * which would otherwise be copied into memory, whole, before anything could look
     * at it. The bound is the peak rather than the bytes alone -- the text is decoded
     * to a `String` while the bytes are still held, so 32 MB here is on the order of
     * 96 MB resident on a phone that may not have much more than that to give.
     */
    const val MAX_IMPORT_BYTES = 32L * 1024 * 1024

    /** Whether [text] can travel in an intent extra. */
    fun fitsInShare(text: String): Boolean = text.length <= MAX_SHARE_CHARS

    /**
     * Reads [input] as UTF-8 text, or returns null when it holds more than
     * [limitBytes].
     *
     * Null rather than an exception for the size case: it is a fact about the file
     * rather than a failure of the read, and the caller says something different for
     * each. A read that fails throws, as reads do.
     */
    fun readText(input: InputStream, limitBytes: Long = MAX_IMPORT_BYTES): String? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            // Checked as it arrives rather than after: the point of the limit is not
            // to hold the file and then decide.
            if (total > limitBytes) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray().decodeToString()
    }
}
