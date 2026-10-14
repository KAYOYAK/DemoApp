package com.example.demoapp.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading a file that may or may not be ciphertext.
 *
 * The encryption itself needs a device: the key lives in the platform keystore and
 * a JVM unit test has no keystore to reach. What is testable here is the part that
 * decides **whether** to decrypt, and that part is where the expensive mistakes
 * live -- a conversation written before any of this existed has no marker, and
 * treating it as ciphertext would lose it.
 *
 * The cost of that gap is stated rather than hidden: the cipher's round trip is
 * verified on a device, and this file does not claim otherwise.
 */
class FileCipherTest {

    private val plaintext = """{"id":"c1","title":"T","messages":[]}""".toByteArray()

    @Test
    fun `a file with no marker is returned as it stands`() {
        // Every conversation written before this feature existed is in this shape.
        // Returning null here would lose all of them.
        val read = FileCipher.decrypt(plaintext)

        assertArrayEquals(plaintext, read)
    }

    @Test
    fun `an empty file is not mistaken for ciphertext`() {
        assertArrayEquals(ByteArray(0), FileCipher.decrypt(ByteArray(0)))
    }

    @Test
    fun `a short file is not mistaken for ciphertext`() {
        // A file smaller than the marker cannot carry it, and an index out of bounds
        // here would be a crash on a truncated write rather than a read that fails.
        val tiny = byteArrayOf(0x4E)

        assertArrayEquals(tiny, FileCipher.decrypt(tiny))
    }

    @Test
    fun `the marker alone is not readable data`() {
        // Something that starts like ciphertext and has nothing after it. Null: the
        // alternative is returning a run of bytes that the parser then chokes on,
        // which reports as a corrupt conversation rather than an unreadable one.
        val marked = byteArrayOf(0x4E, 0x55, 0x4C, 0x4C, 0x01, 0, 0, 0)

        val read = FileCipher.decrypt(marked) {}

        assertNull(read)
    }

    @Test
    fun `something that begins like the marker but is not is left alone`() {
        // A JSON file whose first bytes happen to be NULL. Five bytes of match is
        // not a coincidence worth acting on -- the marker is longer than that.
        val looksSimilar = "NULLISH DATA".toByteArray()

        assertArrayEquals(looksSimilar, FileCipher.decrypt(looksSimilar))
    }

    @Test
    fun `an unreadable file reports rather than returning the ciphertext`() {
        // The keystore was cleared, or the file was restored onto another device.
        // Returning the raw bytes would put a wall of base64 where a conversation
        // should be; returning an empty conversation would look like data loss.
        val marked = byteArrayOf(0x4E, 0x55, 0x4C, 0x4C, 0x01, 16) + ByteArray(32)
        var reported = false

        val read = FileCipher.decrypt(marked) { reported = true }

        assertNull(read)
        assertEquals("The caller is told", true, reported)
    }

    @Test
    fun `reading a file that is not there is not a crash`() {
        val missing = java.io.File("/definitely/not/here/conversation.json")

        assertNull(FileCipher.read(missing))
    }
}
