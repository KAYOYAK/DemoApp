package com.example.demoapp.data.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

/**
 * Deleting a conversation, and the honest amount that can be done about it.
 *
 * ## What was looked at first
 *
 * The obvious feature here is "overwrite the file before unlinking it", and the
 * apps that have it say so on their feature list. The literature says not to
 * promise that on Android:
 *
 * - Calling `delete()` does not erase anything. Until the blocks are reused, the
 *   file can be carved straight out of a physical image of the device.
 * - Overwriting does not reliably fix it either. Flash is managed by a controller
 *   that remaps writes and keeps spare blocks, so the bytes this writes are not
 *   necessarily the bytes the old file occupied.
 * - Overwriting the file that is still open is the one case that does something:
 *   the blocks are in use, so the write goes to the same place, and the old
 *   contents stop being the current contents of those blocks.
 *
 * The conclusion from that literature is to assume anything written to a device
 * can be recovered, and to say so rather than to ship a guarantee that is not one.
 *
 * ## What this does, then
 *
 * Two things, and both are real:
 *
 * - **Overwrite the file's own contents before deleting it.** It is not a
 *   guarantee, and on some devices it does nothing at all. It is free, it is
 *   immediate, and on the devices where it works it helps.
 * - **A separate, explicit action that writes over the free space.** This is what
 *   the same literature describes as the only method that has a chance, and it
 *   also describes the cost: it wears the flash, slows the whole device while it
 *   runs, and consumes battery. That trade belongs to the user, not to this app,
 *   so it is offered with the cost written on it rather than done silently.
 *
 * Neither is encryption. The app's storage is already encrypted at rest by the
 * platform, keyed to the device lock, which is what actually protects a lost phone
 * -- and it does nothing at all for a device that is unlocked and rooted.
 */
object SecureDelete {

    /** One megabyte: large enough that the write is not the cost, small enough to cancel. */
    private const val CHUNK = 1024 * 1024

    /**
     * Writes over a file, flushes, then deletes it.
     *
     * Returns whether the file is gone afterwards. It reports the outcome rather
     * than assuming it: a delete that silently failed would leave the user
     * believing something was removed that was not.
     *
     * The directory is synced too. Unlinking is a change to the directory, and on
     * a filesystem that journals metadata, a delete that has not been synced is one
     * that may not have happened yet.
     */
    suspend fun delete(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext true
        overwrite(file)
        val gone = file.delete()
        syncDirectory(file.parentFile)
        gone || !file.exists()
    }

    /** Best effort, and deliberately not fatal. A failure here still deletes. */
    private suspend fun overwrite(file: File) {
        try {
            val length = file.length()
            if (length <= 0L) return
            RandomAccessFile(file, "rws").use { handle ->
                val random = SecureRandom()
                val buffer = ByteArray(CHUNK)
                var written = 0L
                while (written < length) {
                    coroutineContext.ensureActive()
                    random.nextBytes(buffer)
                    val take = minOf(buffer.size.toLong(), length - written).toInt()
                    handle.write(buffer, 0, take)
                    written += take
                }
                // "rws" writes through on every call, which is the property this
                // needs: the point is for the blocks to hold the new bytes before
                // the directory entry goes away.
                handle.fd.sync()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Read-only, out of space, gone underneath us. None of it is a reason
            // to leave the file there: the delete that follows is still the right
            // thing to attempt.
        }
    }

    /** Metadata is data too; a delete that was not flushed may not have happened. */
    private fun syncDirectory(directory: File?) {
        runCatching {
            val dir = directory ?: return
            RandomAccessFile(dir, "r").use { it.fd.sync() }
        }
    }

    /**
     * Writes over the free space, once, and says how much it wrote.
     *
     * Offered rather than performed. It is the only method the literature
     * describes as having a chance of catching the blocks that an unlinked file
     * left behind, and it is also the one with a cost the user has to accept:
     * flash wears out, the device is slow while it runs, and it uses battery.
     *
     * [budgetBytes] bounds it. Free space on a phone can be tens of gigabytes, and
     * a pass that runs for twenty minutes is one nobody finishes; the caller
     * chooses how much to spend, and this reports what it actually did.
     *
     * Cancellable between chunks, so a user who changes their mind is not held
     * until it finishes.
     */
    suspend fun wipeFreeSpace(
        directory: File,
        budgetBytes: Long,
    ): Long = withContext(Dispatchers.IO) {
        if (budgetBytes <= 0L) return@withContext 0L
        val random = SecureRandom()
        val buffer = ByteArray(CHUNK)
        var written = 0L
        var index = 0

        try {
            while (written < budgetBytes) {
                coroutineContext.ensureActive()
                val before = written
                val target = File(directory, "wipe-$index.bin")
                try {
                    target.outputStream().use { out ->
                        while (written < budgetBytes && out.let { true }) {
                            coroutineContext.ensureActive()
                            random.nextBytes(buffer)
                            val take = minOf(buffer.size.toLong(), budgetBytes - written).toInt()
                            if (take <= 0) break
                            out.write(buffer, 0, take)
                            written += take
                        }
                        out.flush()
                    }
                } finally {
                    // Removed even if the write failed, so a cancelled pass does
                    // not leave a few hundred megabytes of noise on the device.
                    runCatching { target.delete() }
                }
                // A pass that wrote nothing means the next one cannot either: the
                // volume is full, or the directory stopped accepting files. Without
                // this the loop keeps creating empty files until the budget is
                // somehow reached -- which it never is, because nothing is being
                // written -- and the button sits on "overwriting" while the device
                // is asked to create and delete a file as fast as it can.
                //
                // Out of space is the expected way this stops, and stopping is fine:
                // what was written is what was written, and the caller reports the
                // figure.
                if (written == before) break
                index++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Out of space is the expected way this stops, and it stopping early
            // is fine: what was written is what was written.
        }

        written
    }
}
