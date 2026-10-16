package com.kayoyak.demoapp.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Telling a user why their model would not load.
 *
 * Every message has to answer three things -- what happened, why, and what to do
 * -- and the classification is what picks which three. The expensive mistake is
 * not a wrong label; it is a label that sends the user to do something that cannot
 * help, like reinstalling a model when the problem is memory.
 */
class LoadFailureTest {

    private fun kindOf(error: Throwable) = LoadFailure.of(error).kind

    @Test
    fun `running out of memory is recognised`() {
        assertEquals(
            LoadFailure.Kind.NOT_ENOUGH_MEMORY,
            kindOf(OutOfMemoryError("Failed to allocate a 4096 byte allocation")),
        )
    }

    @Test
    fun `the platform's spelling of out of space is recognised`() {
        // What the runtime actually says when the device is full, and the spelling
        // the kernel uses rather than the one Java does.
        assertEquals(
            LoadFailure.Kind.NOT_ENOUGH_MEMORY,
            kindOf(IOException("write failed: ENOSPC (No space left on device)")),
        )
    }

    @Test
    fun `a missing file is recognised`() {
        assertEquals(
            LoadFailure.Kind.MISSING_OR_UNREADABLE,
            kindOf(FileNotFoundException("/data/user/0/app/files/models/m.litertlm")),
        )
    }

    @Test
    fun `a file this build cannot run is recognised`() {
        assertEquals(
            LoadFailure.Kind.UNSUPPORTED,
            kindOf(IllegalArgumentException("Invalid flatbuffer: unsupported schema version")),
        )
    }

    @Test
    fun `a runtime that failed to start is recognised`() {
        assertEquals(
            LoadFailure.Kind.ENGINE_FAILED,
            kindOf(IllegalStateException("Failed to create engine for the given model")),
        )
    }

    @Test
    fun `something unrelated is not guessed at`() {
        // A wrong label gives wrong advice, which is worse than admitting the
        // reason is not known.
        assertEquals(
            LoadFailure.Kind.UNKNOWN,
            kindOf(RuntimeException("something nobody has seen before")),
        )
    }

    @Test
    fun `a cause is looked at, not only the exception on top`() {
        // The runtime wraps: a friendly exception on the outside and the real
        // reason inside it. Reading only the outer one would classify everything
        // as unknown.
        val wrapped = RuntimeException("load failed", OutOfMemoryError("cannot allocate"))

        assertEquals(LoadFailure.Kind.NOT_ENOUGH_MEMORY, kindOf(wrapped))
    }

    @Test
    fun `an exception with no message is still classified`() {
        val failure = LoadFailure.of(FileNotFoundException())

        assertEquals(LoadFailure.Kind.MISSING_OR_UNREADABLE, failure.kind)
    }

    @Test
    fun `the runtime's own account is kept`() {
        // It is the only thing that makes a report actionable, and nobody should
        // have to read it to understand the sentence above it.
        val failure = LoadFailure.of(IOException("lowmemorykiller killed the process"))

        assertNotNull(failure.detail)
        assertTrue(failure.detail!!.contains("lowmemorykiller"))
    }

    @Test
    fun `a failure with nothing to say carries no detail`() {
        // Null rather than an empty string, so the screen can decide not to offer
        // a control that would open onto nothing.
        val failure = LoadFailure.of(object : RuntimeException() {})

        assertEquals(LoadFailure.Kind.UNKNOWN, failure.kind)
        assertEquals(null, failure.detail)
    }

    @Test
    fun `every kind has a message of its own`() {
        // Adding a kind and forgetting its copy would otherwise show an empty
        // dialog at exactly the moment the user needs something to read.
        val ids = LoadFailure.Kind.entries.map { it.messageRes() }
        assertEquals("One message per kind", ids.size, ids.distinct().size)
        assertTrue("All are real resources", ids.all { it != 0 })
    }
}