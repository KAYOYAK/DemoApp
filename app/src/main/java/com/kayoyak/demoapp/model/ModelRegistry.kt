package com.kayoyak.demoapp.model

import com.kayoyak.demoapp.R

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * A model the user brought themselves.
 *
 * Recorded separately from the catalogue, which is a list of files this project
 * has checked the licence of. An import is an attestation by the user, not a
 * verification by this project, and the two must not be stored as if they were
 * the same kind of fact.
 */
@Serializable
data class ImportedModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    /**
     * The digest this project computed over the file when it was imported.
     *
     * Not the publisher's digest -- a file the user supplied has no published
     * digest to compare against. It is recorded so that a later corruption is
     * detectable, which is a different guarantee and is described as such
     * wherever it is shown.
     */
    val sha256: String,
    /** The licence the user said this model is under. Must be one of the accepted set. */
    val licenseSpdxId: String,
    /** Where the user checked the licence. */
    val licenseSourceUrl: String,
    val importedAt: Long,
) {
    /**
     * The descriptor the rest of the application works with.
     *
     * [url] is null because there is nowhere to fetch this from: it is already
     * here, and offering a download for it would be a button that can only fail.
     */
    fun descriptor(): ModelDescriptor = ModelDescriptor(
        id = id,
        displayName = displayName,
        publisherRes = R.string.publisher_imported,
        descriptionRes = R.string.imported_model_description,
        url = null,
        fileName = fileName,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        license = ModelLicense(spdxId = licenseSpdxId, sourceUrl = licenseSourceUrl),
        parametersBillions = 0.0,
    )
}

/**
 * The models this app can use: the ones it knows how to fetch, and the ones the
 * user added.
 *
 * Every screen asks this rather than [ModelCatalogue] directly, because "which
 * models are there" stopped being a constant the moment import existed. A caller
 * that read the catalogue would show a user their imported model everywhere
 * except the places that forgot to look.
 */
interface ModelRegistry {
    /** Everything, catalogue first. */
    val models: StateFlow<List<ModelDescriptor>>

    fun byId(id: String): ModelDescriptor?

    /** Records an imported model and returns its descriptor. */
    suspend fun addImported(imported: ImportedModel): ModelDescriptor

    /** Forgets an imported model. Does not touch the file; the store does that. */
    suspend fun forgetImported(id: String)
}

/**
 * The catalogue, plus a file listing what the user imported.
 *
 * The imported list is small and written rarely, so it is kept in memory and
 * rewritten whole. It holds no large data: the model files themselves live in
 * the model directory and are described by [ModelStore].
 */
class FileModelRegistry(
    private val file: File,
    private val catalogue: List<ModelDescriptor> = ModelCatalogue.models,
) : ModelRegistry {

    private val writeLock = Mutex()

    // Declared before the property that reads it: Kotlin initialises in source
    // order, and the first read happens in an initialiser.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val imported = MutableStateFlow(readImportList())

    private val _models = MutableStateFlow(combined(imported.value))
    override val models: StateFlow<List<ModelDescriptor>> = _models.asStateFlow()

    init {
        file.parentFile?.listFiles()
            ?.filter { it.name.endsWith(TEMP_SUFFIX) }
            ?.forEach { it.delete() }
    }

    override fun byId(id: String): ModelDescriptor? = _models.value.firstOrNull { it.id == id }

    override suspend fun addImported(imported: ImportedModel): ModelDescriptor {
        writeLock.withLock {
            // Replaces an entry with the same id rather than adding a second one.
            // Two entries naming one file would make the model list show it twice
            // and make deleting it leave the other behind.
            val next = this.imported.value.filterNot { it.id == imported.id } + imported
            writeNow(next)
            this.imported.value = next
            _models.value = combined(next)
        }
        return imported.descriptor()
    }

    override suspend fun forgetImported(id: String) {
        writeLock.withLock {
            val next = imported.value.filterNot { it.id == id }
            if (next.size == imported.value.size) return@withLock
            writeNow(next)
            imported.value = next
            _models.value = combined(next)
        }
    }

    private fun combined(imported: List<ImportedModel>): List<ModelDescriptor> =
        catalogue + imported.map { it.descriptor() }

    private suspend fun writeNow(imported: List<ImportedModel>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            temp.outputStream().use { out ->
                out.write(json.encodeToString(imported).toByteArray())
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    /**
     * Reads the imported list, or nothing.
     *
     * Never throws. A list that cannot be read costs the user the sight of a model
     * they still have on disk; refusing to start would cost them the app.
     */
    private fun readImportList(): List<ImportedModel> {
        if (!file.isFile) return emptyList()
        return try {
            json.decodeFromString<List<ImportedModel>>(file.readText())
        } catch (t: Throwable) {
            if (t is IOException || t is IllegalArgumentException) emptyList() else throw t
        }
    }

    private companion object {
        const val TEMP_SUFFIX = ".tmp"
    }
}

/** An in-memory registry, for a caller with no file to read. */
class InMemoryModelRegistry(
    private val catalogue: List<ModelDescriptor> = ModelCatalogue.models,
) : ModelRegistry {

    private val _models = MutableStateFlow(catalogue)
    override val models: StateFlow<List<ModelDescriptor>> = _models.asStateFlow()

    override fun byId(id: String): ModelDescriptor? = _models.value.firstOrNull { it.id == id }

    override suspend fun addImported(imported: ImportedModel): ModelDescriptor {
        _models.value = _models.value.filterNot { it.id == imported.id } + imported.descriptor()
        return imported.descriptor()
    }

    override suspend fun forgetImported(id: String) {
        _models.value = _models.value.filterNot { it.id == id }
    }
}
