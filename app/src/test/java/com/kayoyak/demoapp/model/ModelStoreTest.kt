package com.kayoyak.demoapp.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class ModelStoreTest {

    private lateinit var root: File
    private lateinit var store: ModelStore

    /** A descriptor whose file name is unique per test, so tests cannot collide. */
    private fun descriptor(id: String, size: Long): ModelDescriptor {
        val base = ModelCatalogue.models.first()
        return base.copy(
            id = id,
            fileName = "$id.litertlm",
            sizeBytes = size,
            sha256 = "0".repeat(64),
        )
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("model-store-test").toFile()
        store = ModelStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(file: File, bytes: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(Random(42).nextBytes(bytes))
    }

    /**
     * A file of exactly [size] bytes without writing them.
     *
     * Catalogue models are hundreds of megabytes; `installed` is decided by
     * length, so a sparse file of the right length is the whole requirement.
     */
    private fun sparse(file: File, size: Long) {
        file.parentFile?.mkdirs()
        java.io.RandomAccessFile(file, "rw").use { it.setLength(size) }
    }

    @Test
    fun `a model is not installed when nothing exists`() {
        val model = descriptor("absent", 1024)
        assertFalse(store.isInstalled(model))
        assertEquals(0L, store.bytesOnDisk(model))
    }

    @Test
    fun `a model is not installed when the file is the wrong size`() {
        // This is the case that matters: a download that stopped short leaves a
        // real file behind, and treating it as installed makes the failure look
        // like a corrupt model rather than an unfinished download.
        val model = descriptor("truncated", 1024)
        write(store.modelFile(model), 512)
        assertFalse("A short file must not count as installed", store.isInstalled(model))
    }

    @Test
    fun `a model is installed once the size matches`() {
        val model = descriptor("complete", 4096)
        write(store.modelFile(model), 4096)
        assertTrue(store.isInstalled(model))
        assertEquals(4096L, store.bytesOnDisk(model))
    }

    @Test
    fun `partial downloads are reported as bytes on disk`() {
        val model = descriptor("partial", 8192)
        write(store.partialFile(model), 2048)
        assertFalse(store.isInstalled(model))
        assertEquals(
            "Resume depends on knowing how much is already there",
            2048L,
            store.bytesOnDisk(model),
        )
    }

    @Test
    fun `delete removes both the finished file and any partial`() {
        val model = descriptor("both", 1024)
        write(store.modelFile(model), 1024)
        write(store.partialFile(model), 100)

        assertTrue(store.delete(model))

        assertFalse(store.modelFile(model).exists())
        assertFalse(
            "A leftover partial would silently hold gigabytes",
            store.partialFile(model).exists(),
        )
    }

    @Test
    fun `installedModels returns only complete models`() {
        // Real catalogue entries, not synthetic ones. `installedModels` filters
        // the catalogue, so a made-up id can never appear in the result and an
        // assertion about one is true whatever the code does -- an earlier
        // version of this test asserted exactly that and could not fail.
        val complete = ModelCatalogue.models[0]
        val unfinished = ModelCatalogue.models[1]
        sparse(store.modelFile(complete), complete.sizeBytes)
        sparse(store.partialFile(unfinished), unfinished.sizeBytes / 2)

        val installed = store.installedModels(ModelCatalogue.models).map { it.id }

        assertEquals(
            "Exactly the finished model is installed, in catalogue order",
            listOf(complete.id),
            installed,
        )
    }

    @Test
    fun `totalBytesUsed counts complete and partial files`() {
        write(store.modelFile(descriptor("a", 100)), 100)
        write(store.partialFile(descriptor("b", 100)), 60)
        assertEquals(160L, store.totalBytesUsed())
    }

    @Test
    fun `availableBytes reflects the real filesystem`() = runBlocking {
        assertTrue("The store must be able to report free space", store.availableBytes() > 0)
    }

    @Test
    fun `availableBytes asks the platform when it can`() = runBlocking {
        // The plain filesystem figure is what is free right now. Android can
        // reclaim other applications' cached files for a large allocation, so the
        // real answer is larger -- and reporting the smaller one refuses a
        // download the device would have taken. A phone with 3 GB free and a
        // 3.6 GB model was refused by exactly this arithmetic.
        val asked = ModelStore(root, allocatableBytes = { 12_345_678_901L })

        assertEquals(12_345_678_901L, asked.availableBytes())
    }

    @Test
    fun `a platform that will not answer falls back rather than refusing`() = runBlocking {
        // "Could not measure" is not "no space". A refusal here would block a
        // download the user asked for and that can complete, which is the worse
        // of the two mistakes.
        val silent = ModelStore(root, allocatableBytes = { error("no StorageManager here") })

        assertTrue("Falling back to the filesystem figure", silent.availableBytes() > 0)
    }

    @Test
    fun `deleteAll empties the directory`() {
        write(store.modelFile(descriptor("x", 10)), 10)
        write(store.partialFile(descriptor("y", 10)), 10)
        store.deleteAll()
        assertEquals(0L, store.totalBytesUsed())
    }

    @Test
    fun `an unsafe file name is rejected`() {
        val base = ModelCatalogue.models.first()
        val escaping = base.copy(fileName = "../../outside.litertlm")
        val error = runCatching { store.modelFile(escaping) }.exceptionOrNull()
        assertTrue(
            "A name that escapes the model directory must be refused, got: $error",
            error is IllegalArgumentException,
        )
    }
}
