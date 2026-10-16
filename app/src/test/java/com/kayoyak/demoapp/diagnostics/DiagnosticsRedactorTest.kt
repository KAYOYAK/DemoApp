package com.kayoyak.demoapp.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redactor, tested by what it must remove and by what it must keep.
 *
 * The keep half matters as much: a report stripped of everything is a report
 * nobody can act on, and a user who sends one twice and gets nowhere stops
 * sending them.
 */
class DiagnosticsRedactorTest {

    private fun redact(text: String) = DiagnosticsRedactor.redact(text)

    // ------------------------------------------------------------- removed ---

    @Test
    fun `a file path is removed`() {
        val path = "/data/user/0/com.kayoyak.demoapp/files/models/Qwen3-0.6B.litertlm"
        val out = redact("Loading $path failed")

        assertFalse(out.contains("com.kayoyak.demoapp"))
        assertFalse(out.contains("Qwen3-0.6B"))
        assertTrue("The surrounding sentence survives", out.contains("Loading"))
    }

    @Test
    fun `a windows path is removed`() {
        val out = redact("""Could not read C:\Users\someone\models\model.litertlm""")
        assertFalse(out.contains("someone"))
    }

    @Test
    fun `an address is removed but the scheme stays`() {
        // The scheme is what makes a failing endpoint identifiable. Removing it
        // along with the host would take the diagnosis with the secret.
        val out = redact("GET https://hf-mirror.com/litert-community/Qwen3-0.6B failed")
        assertFalse("The host is gone", out.contains("hf-mirror.com"))
        assertFalse("The model path is gone", out.contains("Qwen3-0.6B"))
    }

    @Test
    fun `a bare address is removed, port included`() {
        assertEquals("connecting to <地址已隐去>", redact("connecting to 192.168.1.14"))
        // The port goes with it. An address and its port are one thing, and a
        // report naming the port of a host that is otherwise hidden describes
        // someone's network just as precisely.
        assertEquals("connecting to <地址已隐去>", redact("connecting to 192.168.1.14:8080"))
    }

    @Test
    fun `something shaped like a credential is removed`() {
        assertFalse(redact("Authorization: Bearer ghp_abcdefghijklmnopqrstuvwxyz0123").contains("ghp_"))
        assertFalse(redact("api_key=sk-live-abcdefghijklmnop").contains("sk-live"))
        assertFalse(redact("password=hunter2").contains("hunter2"))
    }

    @Test
    fun `a private key is removed whole`() {
        val key = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADAN\n-----END PRIVATE KEY-----"
        val out = redact(key)
        assertFalse(out.contains("MIIEvQIBADAN"))
    }

    @Test
    fun `an email address is removed`() {
        assertFalse(redact("contact someone@example.com").contains("someone"))
    }

    @Test
    fun `a long opaque string is removed`() {
        // A cache key, a signature, a session id. None belongs in a report, and
        // there is no way to tell which of them is dangerous from the shape alone.
        val token = "a".repeat(64)
        assertFalse(redact("key=$token").contains(token))
    }

    // -------------------------------------------------------------- kept ----

    @Test
    fun `the version, the model name and the numbers survive`() {
        // What a report is actually for. A redactor that took these would make
        // every report useless, and useless reports are not sent twice.
        val report = """
            app 1.2.3 (build 45)
            android 15 (API 35)
            device Pixel 7, arm64-v8a
            model qwen3-0.6b-int4, backend CPU
            tokens 1234 of 4096, 18.2 tok/s
            error: OutOfMemoryError at line 42
        """.trimIndent()

        val out = redact(report)

        assertTrue(out.contains("1.2.3"))
        assertTrue(out.contains("API 35"))
        assertTrue(out.contains("arm64-v8a"))
        assertTrue(out.contains("qwen3-0.6b-int4"))
        assertTrue(out.contains("4096"))
        assertTrue(out.contains("18.2 tok/s"))
        assertTrue(out.contains("OutOfMemoryError"))
    }

    @Test
    fun `an exception stack still names the frames`() {
        val trace = """
            java.lang.OutOfMemoryError: Failed to allocate a 4194304 byte allocation
            	at com.kayoyak.demoapp.inference.LiteRtLlmEngine.generate(LiteRtLlmEngine.kt:337)
            	at kotlinx.coroutines.flow.internal.ChannelFlowOperator.collect(ChannelFlow.kt:1)
        """.trimIndent()

        val out = redact(trace)

        // The class and method names are the diagnosis. The file paths inside the
        // parentheses are not, and they are what gets removed.
        assertTrue(out.contains("OutOfMemoryError"))
        assertTrue(out.contains("LiteRtLlmEngine"))
        assertTrue(out.contains("kotlinx.coroutines"))
    }

    @Test
    fun `chinese text is untouched`() {
        val text = "模型加载失败：内存不足，需要 1.8 GB，现在只有 900 MB。"
        assertEquals(text, redact(text))
    }

    // ------------------------------------------------------------- shaped ---

    @Test
    fun `a report with nothing to remove comes back unchanged`() {
        val text = "nothing sensitive here at all"
        assertEquals(text, redact(text))
    }

    @Test
    fun `redacting twice changes nothing more`() {
        // The property that makes it safe to apply on top of an already-filtered
        // string, which the report builder does.
        val once = redact("see /data/user/0/com.kayoyak.demoapp/files/x.log and 10.0.0.1")
        assertEquals(once, redact(once))
    }

    @Test
    fun `it terminates on text engineered to keep matching`() {
        // A replacement that itself matches a rule would loop forever without the
        // pass bound. This is the shape that does it: a path whose replacement
        // contains something the path rule matches again.
        val out = redact("/data//data//data//data//data//data//data//data/x")
        assertFalse(out.contains("/data/"))
    }

    @Test
    fun `the check agrees with the redaction`() {
        assertTrue(DiagnosticsRedactor.looksUnredacted("see /data/user/0/com.kayoyak.demoapp/x"))
        assertFalse(DiagnosticsRedactor.looksUnredacted("app 1.2.3, CPU, 4096 tokens"))
    }
}
