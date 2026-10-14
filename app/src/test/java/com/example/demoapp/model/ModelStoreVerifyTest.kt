package com.example.demoapp.model

import com.example.demoapp.data.chat.Conversation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/**
 * Re-reading an installed model and comparing it with what was published.
 *
 * Download and import already check what they wrote. This is for afterwards:
 * storage corrupts files long after they were verified, and a model damaged that
 * way loads, answers, and answers worse -- so the only symptom is that the app
 * seems to have got dumber, which is a symptom with no next step.
 */
class ModelStoreVerifyTest {

    @get:Rule val temp = TemporaryFolder()

    private fun store() = ModelStore(temp.root)

    private fun descriptorFor(sizeBytes: Long, sha256: String) = ModelDescriptor(
        id = "test",
        displayName = "Test",
        publisherRes = 0,
        descriptionRes = 0,
        url = "https://example.invalid/m.litertlm",
        fallbackUrl = "https://example.invalid/m.litertlm",
        fileName = "m.litertlm",
        sizeBytes = sizeBytes,
        sha256 = sha256,
        license = ModelLicense("Apache-2.0", "https://example.invalid"),
        languages = setOf(ModelLanguage.CHINESE),
        parametersBillions = 0.6,
    )

    private fun installed(store: ModelStore, bytes: ByteArray): String {
        store.modelFile("m.litertlm").also {
            it.parentFile?.mkdirs()
            it.writeBytes(bytes)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }
    }

    @Test
    fun `a file that matches what was published is intact`(): Unit = runBlocking {
        val store = store()
        val bytes = "a small model".toByteArray()
        val digest = installed(store, bytes)

        val verdict = store.verify(descriptorFor(bytes.size.toLong(), digest), digest)

        assertTrue(verdict is ModelStore.Verification.Intact)
    }

    @Test
    fun `a file that no longer matches is reported as damaged, with both digests`(): Unit = runBlocking {
        // Both, because "it is damaged" without what it should have been is a
        // message the user can only act on by taking the app's word for it.
        val store = store()
        val bytes = "the bytes that are there".toByteArray()
        val realDigest = installed(store, bytes)
        val published = "0".repeat(64)

        val verdict = store.verify(descriptorFor(bytes.size.toLong(), published), published)

        assertTrue(verdict is ModelStore.Verification.Damaged)
        val damaged = verdict as ModelStore.Verification.Damaged
        assertEquals(published, damaged.expected)
        assertEquals(realDigest, damaged.actual)
    }

    @Test
    fun `one changed byte is caught`(): Unit = runBlocking {
        // The whole point: a corruption of a single byte has to be visible, and a
        // hash is the only thing that makes it so.
        val store = store()
        val bytes = ByteArray(64 * 1024) { it.toByte() }
        val digest = installed(store, bytes)
        store.modelFile("m.litertlm").writeBytes(bytes.copyOf().also { it[32_000] = 7 })

        val verdict = store.verify(descriptorFor(bytes.size.toLong(), digest), digest)

        assertTrue(verdict is ModelStore.Verification.Damaged)
    }

    @Test
    fun `a file that is not there says so rather than failing`(): Unit = runBlocking {
        val store = store()

        val verdict = store.verify(descriptorFor(10, "0".repeat(64)), "0".repeat(64))

        assertTrue(verdict is ModelStore.Verification.Missing)
    }

    @Test
    fun `a file larger than one buffer is hashed whole`(): Unit = runBlocking {
        // The buffer is one megabyte. A file that crosses it twice is the case
        // where an off-by-one in the chunk loop would silently hash a prefix and
        // call the result correct.
        val store = store()
        val bytes = ByteArray(2 * 1024 * 1024 + 1234) { (it % 251).toByte() }
        val digest = installed(store, bytes)

        val verdict = store.verify(descriptorFor(bytes.size.toLong(), digest), digest)

        assertTrue(
            "The chunk loop must hash the whole file, not the first buffer",
            verdict is ModelStore.Verification.Intact,
        )
    }

    @Test
    fun `the digest comparison ignores case`(): Unit = runBlocking {
        // Catalogue entries are lowercase; the comparison should not depend on it.
        val store = store()
        val bytes = "x".toByteArray()
        val digest = installed(store, bytes)

        val verdict = store.verify(descriptorFor(1, digest), digest.uppercase())

        assertTrue(verdict is ModelStore.Verification.Intact)
    }
}