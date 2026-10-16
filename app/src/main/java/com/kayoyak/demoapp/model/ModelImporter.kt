package com.kayoyak.demoapp.model

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Where an import has got to. */
sealed interface ImportProgress {
    /** Copying the file in. [bytesTotal] is negative when the source will not say. */
    data class Copying(val bytesDone: Long, val bytesTotal: Long) : ImportProgress

    /**
     * Reading the file back to digest it.
     *
     * Its own state because it is a second pass over a file that may be
     * gigabytes, and a screen that showed "copying" through it would look stuck
     * at 100%.
     */
    data class Hashing(val bytesTotal: Long) : ImportProgress
}

/** The result of a finished import. */
data class ImportResult(val sha256: String, val sizeBytes: Long)

/**
 * Copies a model file the user chose into the app's model directory.
 *
 * Streams rather than reading into memory: these files run to gigabytes, and a
 * phone that tries to hold one in a byte array is a phone that is killed by the
 * system partway through.
 *
 * Takes an [InputStream] and a length rather than an Android `Uri`, so the copy
 * and the digest can be tested without a device or a document provider. The
 * caller, which has the resolver, is the one that knows how to open it.
 */
class ModelImporter(private val modelStore: ModelStore) {

    /**
     * Copies [source] to where [descriptor] expects its file to be, then digests
     * what was written.
     *
     * Written to a temporary file and renamed, for the same reason every other
     * write in this app is: an import stopped partway must not leave a file that
     * looks like a model and is not one. The digest is computed over the finished
     * temporary file rather than over the incoming stream, so what is recorded is
     * what is actually on disk.
     *
     * @param expectedBytes what the source says it will provide, or negative when
     *   it will not say. Used for progress and for a free-space check, never as
     *   the final size -- the source can be wrong, and the bytes actually written
     *   are the truth.
     */
    suspend fun import(
        source: InputStream,
        expectedBytes: Long,
        target: File,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult {
        target.parentFile?.mkdirs()

        val available = modelStore.availableBytes()
        if (expectedBytes > 0L && expectedBytes > available) {
            throw IOException("Not enough space: needs ${expectedBytes / 1_048_576} MB, ${available / 1_048_576} MB free")
        }

        val temp = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.part")
        try {
            // The copy is closed before anything is done with the file. Digesting
            // or renaming while the stream is still open fails on Windows, which
            // refuses to move a file another handle holds -- the same shape of
            // mistake as renaming over a file that is still being written.
            val written = source.use { input ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var count = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        count += read

                        val now = System.nanoTime()
                        if (now - lastReport >= PROGRESS_INTERVAL_NANOS) {
                            lastReport = now
                            onProgress(ImportProgress.Copying(count, expectedBytes))
                        }
                    }
                    out.flush()
                    out.fd.sync()
                    onProgress(ImportProgress.Copying(count, expectedBytes))
                    count
                }
            }

            if (written == 0L) throw IOException("That file is empty")

            // A second pass over a file that may be gigabytes, and a state of its
            // own so a screen showing "copying" does not look stuck at 100%.
            onProgress(ImportProgress.Hashing(written))
            val digest = digestOf(temp)

            install(temp, target)
            return ImportResult(sha256 = digest, sizeBytes = written)
        } catch (t: Throwable) {
            // The temporary file is the only thing this method creates, so it is
            // the only thing it has to clean up. A leftover would sit in the model
            // directory looking like a model to every listing that does not know
            // the suffix.
            temp.delete()
            throw t
        }
    }

    private fun install(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun digestOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER_BYTES = 256 * 1024

        /**
         * Roughly five reports a second, which is what a progress bar needs.
         *
         * Nanoseconds, and compared against [System.nanoTime] rather than the wall
         * clock, because this is an interval. `currentTimeMillis` here does not
         * merely drift: moved forward by a time sync it makes every read look
         * overdue and floods the screen with reports at the copying speed, and moved
         * backward it makes the bar stop moving for as long as the jump lasted.
         */
        const val PROGRESS_INTERVAL_NANOS = 200_000_000L
    }
}
