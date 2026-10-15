package com.example.demoapp.data.chat

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two limits a transfer goes through.
 *
 * Both are arithmetic about the platform rather than preferences, so the tests
 * check the arithmetic: a share has to fit a Binder transaction, and an import must
 * not copy an arbitrary file into memory on the way to the parser.
 */
class BackupTransferTest {

    @Test
    fun `a small file is read whole`() {
        val text = "{\"format\":\"demoapp-backup\",\"conversations\":[]} 中文"
        val read = BackupTransfer.readText(ByteArrayInputStream(text.toByteArray()))
        assertEquals(text, read)
    }

    @Test
    fun `a library sized backup is read without touching the limit`() {
        // Four megabytes is a large library and nowhere near the limit: the limit is
        // for the file picked by mistake, not for a real one.
        val text = "x".repeat(4 * 1024 * 1024)
        assertEquals(text, BackupTransfer.readText(ByteArrayInputStream(text.toByteArray())))
    }

    @Test
    fun `a file past the limit is refused instead of read to the end`() {
        // The stream fails the test if the reader goes on asking for bytes. That is
        // the point of the limit: a picked file is not copied into memory whole and
        // then judged.
        val input =
            object : InputStream() {
                private var served = 0L

                override fun read(): Int = throw AssertionError("read() should not be used")

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (served >= 4096L) throw AssertionError("read past the limit")
                    served += len
                    return len
                }
            }
        assertNull(BackupTransfer.readText(input, limitBytes = 1024L))
    }

    @Test
    fun `the share limit counts characters and stops one past it`() {
        assertTrue(BackupTransfer.fitsInShare("x".repeat(BackupTransfer.MAX_SHARE_CHARS)))
        assertFalse(BackupTransfer.fitsInShare("x".repeat(BackupTransfer.MAX_SHARE_CHARS + 1)))
    }

    @Test
    fun `the share limit stays inside a binder transaction`() {
        // A transaction carries about a megabyte, and a String in an intent extra
        // costs two bytes per character. Half the budget is the limit, which leaves
        // the chooser and the other extras their room.
        assertTrue(BackupTransfer.MAX_SHARE_CHARS * 2 < 1_000_000)
        assertTrue(BackupTransfer.fitsInShare(""))
    }

    @Test
    fun `the import limit is far above what this app exports`() {
        // The export of a whole library is a few megabytes at the very most, and the
        // limit has to be above that or a real backup would be refused.
        assertTrue(BackupTransfer.MAX_IMPORT_BYTES > 4L * 1024 * 1024)
    }
}
