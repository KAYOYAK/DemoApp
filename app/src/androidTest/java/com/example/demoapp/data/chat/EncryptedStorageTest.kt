package com.example.demoapp.data.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The one claim in this app that only a device can check: what a conversation looks
 * like where it sits.
 *
 * `FileChatStore`'s unit tests run on a plain JVM, where `AndroidKeyStore` does not
 * exist and [com.example.demoapp.data.FileCipher] returns its input unchanged -- the
 * documented fallback for a device whose keystore is unavailable. Every one of those
 * tests therefore passes whether or not the bytes on disk are readable: they test the
 * store's logic and they cannot test the encryption. This is the test that reads the
 * file the app wrote, on a device with a keystore, and looks for the words inside it.
 *
 * A failure here on a particular device is a fact about that device rather than a
 * broken test -- the app stores in the clear rather than losing the writing, and says
 * on the About screen what protects the files. What would be a defect is a device
 * *with* a working keystore writing plaintext, and that is what this asks.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedStorageTest {

    /** Somewhere under the app's own directory, so the key and the path are the real ones. */
    private val root = File(
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        "encrypted-storage-test",
    )

    /** A phrase no encoding turns into something else, and no header would contain. */
    private val phrase = "the quick brown fox jumps over the lazy dog"

    private fun conversation(id: String) = Conversation(
        id = id,
        title = phrase,
        modelId = "qwen3-0.6b-int4",
        createdAt = 1_000L,
        updatedAt = 2_000L,
        messages = listOf(
            ChatMessage(id = "$id-m1", role = MessageRole.USER, text = phrase, createdAt = 1_000L),
        ),
    )

    private fun store(): FileChatStore {
        root.deleteRecursively()
        assertTrue("the test can make its own directory", root.mkdirs())
        return FileChatStore(root)
    }

    private fun storedFile(id: String): File =
        root.listFiles()!!.first { it.name == "$id.json" }

    @Test
    fun whatLandsOnDiskIsNotTheConversation() = runBlocking {
        val store = store()
        store.save(conversation("c1"))

        val raw = storedFile("c1").readBytes()
        assertTrue("something was written", raw.isNotEmpty())
        assertFalse(
            "the words are on disk in the clear",
            String(raw, Charsets.ISO_8859_1).contains(phrase),
        )
    }

    @Test
    fun aFreshIvPerWriteMakesTheSameConversationDifferent() = runBlocking {
        val store = store()
        store.save(conversation("c1"))
        val first = storedFile("c1").readBytes()

        // The same conversation, the same key, the same file: only the IV can differ.
        store.save(conversation("c1"))
        val second = storedFile("c1").readBytes()

        assertFalse(
            "the same plaintext produced the same bytes, so the IV is not fresh",
            first.contentEquals(second),
        )
    }

    @Test
    fun aSecondStoreReadsWhatTheFirstWrote() = runBlocking {
        val writing = store()
        writing.save(conversation("c1"))

        // The key lives in the platform keystore, not in this object: a store that has
        // never seen the write has to be able to read it, which is what makes the
        // files survive a restart.
        val loaded = FileChatStore(root).load("c1")

        assertEquals("the title came back", phrase, loaded?.title)
        assertEquals("the message came back", phrase, loaded?.messages?.single()?.text)
    }
}
