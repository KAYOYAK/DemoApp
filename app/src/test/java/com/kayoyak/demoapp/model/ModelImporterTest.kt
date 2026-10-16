package com.kayoyak.demoapp.model

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The import path copies a file the user chose into the app's own storage, so it
 * is the one place where a name and a size that came from outside are turned into
 * something on disk.
 */
class ModelImporterTest {

    private lateinit var root: File
    private lateinit var store: ModelStore
    private lateinit var importer: ModelImporter

    @Before
    fun setUp() {
        root = Files.createTempDirectory("import-test").toFile()
        store = ModelStore(File(root, "models"))
        importer = ModelImporter(store)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun descriptor(fileName: String, sizeBytes: Long = 0L) = ModelDescriptor(
        id = "imported-test",
        displayName = "Test",
        publisherRes = 0,
        descriptionRes = 0,
        url = null,
        fileName = fileName,
        sizeBytes = sizeBytes,
        sha256 = "",
        license = ModelLicense("Apache-2.0", "https://example.com"),
        parametersBillions = 0.0,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a copied file lands with the right contents and the right digest`() = runBlocking {
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        val target = store.modelFile(descriptor("model.litertlm"))

        val result = importer.import(ByteArrayInputStream(payload), payload.size.toLong(), target)

        assertTrue("The file is where the app looks for models", target.isFile)
        assertArrayEquals(payload, target.readBytes())
        assertEquals("The digest is over what was written", sha256(payload), result.sha256)
        assertEquals(payload.size.toLong(), result.sizeBytes)
    }

    @Test
    fun `progress is reported and ends at the full size`() = runBlocking {
        val payload = ByteArray(500_000) { it.toByte() }
        val target = store.modelFile(descriptor("model.litertlm"))
        val seen = mutableListOf<ImportProgress>()

        importer.import(ByteArrayInputStream(payload), payload.size.toLong(), target) { seen += it }

        assertTrue("The copy is reported", seen.any { it is ImportProgress.Copying })
        assertTrue("So is the digest pass", seen.any { it is ImportProgress.Hashing })
        val last = seen.last { it is ImportProgress.Copying } as ImportProgress.Copying
        assertEquals("A progress bar that stops short of the end reads as a stall",
            payload.size.toLong(), last.bytesDone)
    }

    @Test
    fun `an empty file is refused rather than installed`() = runBlocking {
        val target = store.modelFile(descriptor("model.litertlm"))

        val failure = runCatching {
            importer.import(ByteArrayInputStream(ByteArray(0)), 0L, target)
        }.exceptionOrNull()

        assertTrue("An empty file is not a model", failure is IOException)
        assertFalse("And nothing is left in its place", target.exists())
    }

    @Test
    fun `a failure leaves no partial file behind`() = runBlocking {
        val target = store.modelFile(descriptor("model.litertlm"))
        val exploding = object : java.io.InputStream() {
            private var served = 0
            override fun read(): Int = throw IOException("the source went away")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served > 0) throw IOException("the source went away")
                served += len
                b.fill(7, off, off + len)
                return len
            }
        }

        val failure = runCatching { importer.import(exploding, 1_000_000L, target) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse("The target is not created", target.exists())
        assertFalse(
            "And no temporary file is left where a later import would find it",
            target.parentFile?.listFiles()?.any { it.name.startsWith(target.name) } ?: false,
        )
    }

    @Test
    fun `a file larger than the free space is refused before anything is copied`() = runBlocking {
        val target = store.modelFile(descriptor("model.litertlm"))
        val absurd = Long.MAX_VALUE / 2

        val failure = runCatching {
            importer.import(ByteArrayInputStream(ByteArray(16)), absurd, target)
        }.exceptionOrNull()

        assertTrue("Refused on the size it was told", failure is IOException)
        assertFalse("And nothing was written", target.exists())
    }

    @Test
    fun `importing over an existing file replaces it`() = runBlocking {
        val target = store.modelFile(descriptor("model.litertlm"))
        importer.import(ByteArrayInputStream(ByteArray(50_000) { 1 }), 50_000, target)

        val replacement = ByteArray(70_000) { 2 }
        importer.import(ByteArrayInputStream(replacement), replacement.size.toLong(), target)

        assertEquals("The old file is gone, not appended to", 70_000L, target.length())
        assertEquals(sha256(replacement), sha256(target.readBytes()))
    }

    @Test
    fun `a source that will not say its size still imports`() = runBlocking {
        // Some document providers answer -1. Treating that as an empty file, or
        // as a size of zero, would refuse a file that is perfectly fine.
        val payload = ByteArray(120_000) { 3 }
        val target = store.modelFile(descriptor("model.litertlm"))

        val result = importer.import(ByteArrayInputStream(payload), -1L, target)

        assertEquals(payload.size.toLong(), result.sizeBytes)
        assertEquals(sha256(payload), result.sha256)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals("Length", expected.size, actual.size)
        assertTrue("Contents", expected.contentEquals(actual))
    }
}
