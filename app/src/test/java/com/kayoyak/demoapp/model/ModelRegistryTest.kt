package com.kayoyak.demoapp.model

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The registry is what every screen asks when it wants to know which models
 * exist, so an import that is recorded but not listed is a model the user paid
 * to copy in and then cannot see.
 */
class ModelRegistryTest {

    private lateinit var root: File
    private lateinit var file: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("registry-test").toFile()
        file = File(root, "imported-models.json")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun imported(id: String, name: String = "My model", fileName: String = "$id.litertlm") =
        ImportedModel(
            id = id,
            displayName = name,
            fileName = fileName,
            sizeBytes = 1_234L,
            sha256 = "a".repeat(64),
            licenseSpdxId = "Apache-2.0",
            licenseSourceUrl = "https://example.com/licence",
            importedAt = 1_000L,
        )

    @Test
    fun `the catalogue is there before anything is imported`() {
        val registry = FileModelRegistry(file)
        assertEquals(ModelCatalogue.models.size, registry.models.value.size)
    }

    @Test
    fun `an imported model appears alongside the catalogue`() = runBlocking {
        val registry = FileModelRegistry(file)

        val descriptor = registry.addImported(imported("mine", name = "My model"))

        assertEquals(ModelCatalogue.models.size + 1, registry.models.value.size)
        assertTrue("It is in the list", registry.models.value.any { it.id == "mine" })
        assertEquals("And it can be found by id", "My model", registry.byId("mine")?.displayName)
        assertNull(
            "An imported model has nowhere to be fetched from",
            descriptor.url,
        )
        assertTrue(
            "So it offers no download, rather than a button that cannot work",
            descriptor.downloadUrls.isEmpty(),
        )
    }

    @Test
    fun `an imported model survives a restart`() = runBlocking {
        FileModelRegistry(file).addImported(imported("mine", name = "Kept"))

        // A second registry over the same file is what a relaunch looks like.
        val reopened = FileModelRegistry(file)
        assertEquals("Kept", reopened.byId("mine")?.displayName)
    }

    @Test
    fun `importing the same id twice replaces rather than duplicates`() = runBlocking {
        // Two entries naming one file would show the model twice and leave one
        // behind when the other is deleted.
        val registry = FileModelRegistry(file)
        registry.addImported(imported("mine", name = "First"))
        registry.addImported(imported("mine", name = "Second"))

        assertEquals(ModelCatalogue.models.size + 1, registry.models.value.size)
        assertEquals("Second", registry.byId("mine")?.displayName)
    }

    @Test
    fun `forgetting an import removes it everywhere`() = runBlocking {
        val registry = FileModelRegistry(file)
        registry.addImported(imported("mine"))

        registry.forgetImported("mine")

        assertNull(registry.byId("mine"))
        assertEquals(ModelCatalogue.models.size, registry.models.value.size)
        assertNull(
            "And it is gone from the file, not just from memory",
            FileModelRegistry(file).byId("mine"),
        )
    }

    @Test
    fun `forgetting something that was never there changes nothing`() = runBlocking {
        val registry = FileModelRegistry(file)
        registry.addImported(imported("mine"))

        registry.forgetImported("not-here")

        assertNotNull(registry.byId("mine"))
    }

    @Test
    fun `a corrupt list costs the imported models and nothing else`() {
        file.parentFile?.mkdirs()
        file.writeText("{ not a list")

        // The models the user imported are still on disk; losing sight of them is
        // recoverable by importing again. Refusing to start is not.
        assertEquals(ModelCatalogue.models.size, FileModelRegistry(file).models.value.size)
    }

    @Test
    fun `a temporary file left by a crash is cleared on the next start`() {
        file.parentFile?.mkdirs()
        File(root, "imported-models.json.abc.tmp").writeText("half a write")

        FileModelRegistry(file)

        assertTrue(
            "A leftover must not survive into the next run",
            root.listFiles()?.none { it.name.endsWith(".tmp") } ?: true,
        )
    }
}
