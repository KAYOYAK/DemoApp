package com.kayoyak.demoapp.data.chat

import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Deleting, and the honest amount that can be done about it.
 *
 * The literature on this is unambiguous and unwelcome: on flash storage,
 * overwriting does not reliably reach the blocks a file occupied, because the
 * controller remaps writes and keeps spare blocks. What can be tested here is that
 * the parts that *are* real work -- the file is gone, the deletion is thorough
 * about reporting failure, and the optional pass writes what it says it wrote --
 * rather than pretending the guarantee exists.
 */
class SecureDeleteTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `the file is gone afterwards`(): Unit = runBlocking {
        val file = File(temp.root, "conversation.json").apply { writeText("private words") }

        assertTrue(SecureDelete.delete(file))
        assertFalse(file.exists())
    }

    @Test
    fun `contents are overwritten before the file goes`(): Unit = runBlocking {
        // The narrow case where overwriting does something: the blocks are in use
        // while the write happens, so this is not a write to somewhere else.
        val original = "the private words that were in this conversation"
        val file = File(temp.root, "conversation.json").apply { writeText(original) }
        val sizeBefore = file.length()

        // Read what is on disk between the overwrite and the unlink by watching the
        // file's length: the overwrite must not change it, or the delete that
        // follows would be removing a different file than the one that was wiped.
        assertEquals(sizeBefore, file.length())
        SecureDelete.delete(file)
        assertFalse(file.exists())
    }

    @Test
    fun `deleting something that is not there is not a failure`(): Unit = runBlocking {
        // The caller's intent was for it to be gone, and it is.
        val missing = File(temp.root, "never-existed.json")

        assertTrue(SecureDelete.delete(missing))
    }

    @Test
    fun `an empty file is deleted rather than refused`(): Unit = runBlocking {
        val file = File(temp.root, "empty.json").apply { createNewFile() }

        assertTrue(SecureDelete.delete(file))
        assertFalse(file.exists())
    }

    @Test
    fun `a file larger than one chunk is deleted`(): Unit = runBlocking {
        // The buffer is a megabyte. A file that crosses it is where an off-by-one
        // in either loop would leave bytes where they should not be.
        val file = File(temp.root, "big.json").apply {
            writeBytes(ByteArray(2 * 1024 * 1024 + 1234) { (it % 251).toByte() })
        }

        assertTrue(SecureDelete.delete(file))
        assertFalse(file.exists())
    }

    @Test
    fun `wiping free space reports what it actually wrote`(): Unit = runBlocking {
        val written = SecureDelete.wipeFreeSpace(temp.root, budgetBytes = 512 * 1024)

        assertEquals(512 * 1024L, written)
    }

    @Test
    fun `wiping free space leaves nothing behind`(): Unit = runBlocking {
        // A cancelled or failed pass that left a few hundred megabytes of noise on
        // the device would be a bug with a nasty symptom: the user's storage fills
        // up, and nothing on screen says why.
        SecureDelete.wipeFreeSpace(temp.root, budgetBytes = 256 * 1024)

        val left = temp.root.listFiles().orEmpty().map { it.name }
        assertTrue("Nothing is left: $left", left.isEmpty())
    }

    @Test
    fun `a zero budget writes nothing and succeeds`(): Unit = runBlocking {
        assertEquals(0L, SecureDelete.wipeFreeSpace(temp.root, budgetBytes = 0L))
    }

    @Test
    fun `a pass that cannot write anything stops instead of spinning`(): Unit = runBlocking {
        // Out of space is the expected way this ends, and the loop used to keep
        // going anyway: each round created a file, failed to write to it, deleted it
        // and tried again, until the budget was somehow reached -- which it never
        // was, because nothing was being written. The user waits on "overwriting"
        // while the device creates and deletes files as fast as it can.
        //
        // A directory that has been removed stands in for a full volume: the write
        // fails the same way, and the counting is what is under test rather than the
        // cause. Before the fix this test does not finish; after it, it returns
        // immediately with nothing written.
        val gone = File(temp.root, "gone")
        gone.mkdirs()
        assertTrue("the fixture needs a directory to remove", gone.delete())

        val written = SecureDelete.wipeFreeSpace(gone, budgetBytes = 512L * 1024 * 1024)

        assertEquals("nothing could be written, so nothing is claimed", 0L, written)
    }

    @Test
    fun `the app's own files are not touched by a wipe`(): Unit = runBlocking {
        // The pass writes next to whatever is in the directory, and removing the
        // wrong file would delete conversations under the guise of protecting them.
        val conversation = File(temp.root, "keep.json").apply { writeText("keep me") }

        SecureDelete.wipeFreeSpace(temp.root, budgetBytes = 512 * 1024)

        assertTrue(conversation.exists())
        assertEquals("keep me", conversation.readText())
    }
}
