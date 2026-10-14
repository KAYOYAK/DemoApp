package com.example.demoapp.model

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Tests the downloader against a scripted server.
 *
 * The cases worth testing here are the ones that corrupt a file without
 * producing an error, because those are the ones a user cannot detect and
 * cannot recover from:
 *
 *  - a server that ignores the Range header and returns the whole file, which
 *    appended to existing bytes produces a file of the wrong contents but the
 *    right length;
 *  - a server that answers 416;
 *  - content that does not match the recorded digest.
 *
 * None of these can be provoked against a real host on demand, which is why
 * MockWebServer is worth the test-only dependency.
 */
class ModelDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var store: ModelStore
    private lateinit var downloader: ModelDownloader

    private val client = OkHttpClient.Builder().build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        root = Files.createTempDirectory("downloader-test").toFile()
        store = ModelStore(root)
        downloader = ModelDownloader(store, client)
    }

    @After
    fun tearDown() {
        server.close()
        root.deleteRecursively()
    }

    // ------------------------------------------------------------- helpers ---

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun descriptorFor(
        id: String,
        path: String,
        content: ByteArray,
        declaredSize: Long = content.size.toLong(),
    ) = ModelDescriptor(
        id = id,
        displayName = "test model",
        publisherRes = 0,
        descriptionRes = 0,
        url = server.url(path).toString(),
        fileName = "$id.litertlm",
        sizeBytes = declaredSize,
        sha256 = sha256(content),
        license = ModelLicense("Apache-2.0", "https://example.invalid"),
        parametersBillions = 0.0,
    )

    private fun mockResponse(
        code: Int,
        body: ByteArray,
        configure: MockResponse.Builder.() -> Unit = {},
    ): MockResponse = MockResponse.Builder()
        .code(code)
        .body(Buffer().write(body))
        .apply(configure)
        .build()

    // --------------------------------------------------------------- tests ---

    @Test
    fun `downloads verifies and moves into place`() = runBlocking {
        val content = Random(1).nextBytes(64 * 1024)
        val model = descriptorFor("full", "/m.litertlm", content)
        server.enqueue(mockResponse(200, content))

        val events = downloader.download(model).toList()

        assertTrue(
            "Expected Completed, got ${events.lastOrNull()}",
            events.lastOrNull() is DownloadEvent.Completed,
        )
        assertTrue("Model should be installed", store.isInstalled(model))
        assertTrue(
            "Content must match byte for byte",
            store.modelFile(model).readBytes().contentEquals(content),
        )
        assertFalse("The partial file must be gone", store.partialFile(model).exists())
    }

    @Test
    fun `downloading over an existing file replaces it in one step`() = runBlocking {
        // The install used to delete the target and then rename the partial into
        // place. A crash between the two calls destroyed a complete, verified
        // download: the model gone, the partial gone, and the only visible outcome a
        // transfer that restarts from zero. The move is atomic now, so the target is
        // replaced rather than removed and re-created -- which is also what this
        // asserts: the file that was there is gone, the new one is complete, and no
        // partial is left behind.
        val old = Random(70).nextBytes(8 * 1024)
        val fresh = Random(71).nextBytes(32 * 1024)
        val model = descriptorFor("replace", "/m.litertlm", fresh)

        // Something already occupying the target name -- an import, or an earlier
        // download this descriptor's digest has since changed for.
        store.modelFile(model).writeBytes(old)
        assertTrue("the fixture needs a target to replace", store.modelFile(model).exists())

        server.enqueue(mockResponse(200, fresh))

        val events = downloader.download(model).toList()

        assertTrue(
            "Expected Completed, got ${events.lastOrNull()}",
            events.lastOrNull() is DownloadEvent.Completed,
        )
        assertTrue(
            "The new bytes must be in place, not the old ones",
            store.modelFile(model).readBytes().contentEquals(fresh),
        )
        assertFalse("The partial file must be gone", store.partialFile(model).exists())
    }

    @Test
    fun `reports progress rather than completing silently`() = runBlocking {
        // A large body so more than one progress tick is emitted.
        val content = Random(2).nextBytes(3 * 1024 * 1024)
        val model = descriptorFor("progress", "/p.litertlm", content)
        server.enqueue(mockResponse(200, content))

        val events = downloader.download(model).toList()
        val progress = events.filterIsInstance<DownloadEvent.Progress>()

        assertTrue("Expected progress events, got ${events.size} event(s)", progress.isNotEmpty())
        assertTrue("Progress must be monotonic", progress.zipWithNext().all { it.first.bytesDone <= it.second.bytesDone })
        assertTrue(
            "A Verifying event must precede completion: hashing 3 GB is not instant",
            events.any { it is DownloadEvent.Verifying },
        )
        assertEquals(
            "Progress must reach the full size",
            model.sizeBytes,
            progress.last().bytesDone,
        )
    }

    @Test
    fun `a local transfer reports a few times rather than once per buffer`() = runBlocking {
        // The throttle is a duration, and a duration is only observable by
        // counting. The downloader reads through a 256 KB buffer, so this body is
        // 64 reads: a throttle that fired every read -- 200 nanoseconds, which is
        // what the constant was in its previous spelling, `PROGRESS_INTERVAL_MS`
        // compared against a `nanosecond` difference -- would report about 64
        // times. Each report rewrites the coordinator's state, rebuilds every
        // model row and recomposes the list, for a percentage that has barely
        // moved. Four reads per report is the bound: well under the 64 an
        // unthrottled loop produces, and well over the handful a 200 ms interval
        // produces on a body this size.
        val content = Random(2).nextBytes(16 * 1024 * 1024)
        val model = descriptorFor("throttle", "/t.litertlm", content)
        server.enqueue(mockResponse(200, content))

        val events = downloader.download(model).toList()
        val progress = events.filterIsInstance<DownloadEvent.Progress>()
        val reads = content.size / (256 * 1024)

        assertTrue(
            "Expected at least the closing report, got ${events.size} event(s)",
            progress.isNotEmpty(),
        )
        assertTrue(
            "$reads reads produced ${progress.size} progress reports; " +
                "the throttle must drop most of them",
            progress.size * 4 <= reads,
        )
    }

    @Test
    fun `a complete model is not downloaded again`() = runBlocking {
        val content = Random(3).nextBytes(4096)
        val model = descriptorFor("cached", "/c.litertlm", content)
        store.modelFile(model).writeBytes(content)

        val events = downloader.download(model).toList()

        assertEquals(listOf(DownloadEvent.Completed), events)
        assertEquals("No request should have been made", 0, server.requestCount)
    }

    @Test
    fun `resumes from a partial file when the server honours the range`() = runBlocking {
        val content = Random(4).nextBytes(64 * 1024)
        val model = descriptorFor("resume", "/r.litertlm", content)

        // Pretend a previous attempt stopped half way.
        val half = content.size / 2
        store.partialFile(model).writeBytes(content.copyOfRange(0, half))

        server.enqueue(
            mockResponse(206, content.copyOfRange(half, content.size)) {
                addHeader("Content-Range", "bytes $half-${content.size - 1}/${content.size}")
            }
        )

        val events = downloader.download(model).toList()

        assertTrue("Expected Completed, got ${events.lastOrNull()}", events.lastOrNull() is DownloadEvent.Completed)
        assertTrue(
            "Resumed download must reconstruct the file exactly",
            store.modelFile(model).readBytes().contentEquals(content),
        )
        val rangeHeader = server.takeRequest().headers["Range"]
        assertEquals("The resume must be requested with a Range header", "bytes=$half-", rangeHeader)
    }

    @Test
    fun `restarts cleanly when the server ignores the range header`() = runBlocking {
        // The dangerous case. A server answering 200 sends the whole file, not
        // a remainder. Appending it to the existing bytes produces a file of
        // exactly the right length containing interleaved garbage -- which the
        // size check would pass and only the digest catches.
        val content = Random(5).nextBytes(64 * 1024)
        val model = descriptorFor("ignore-range", "/i.litertlm", content)

        val half = content.size / 2
        store.partialFile(model).writeBytes(content.copyOfRange(0, half))

        server.enqueue(mockResponse(200, content))

        val events = downloader.download(model).toList()

        assertTrue("Expected Completed, got ${events.lastOrNull()}", events.lastOrNull() is DownloadEvent.Completed)
        assertTrue(
            "A 200 response must replace the partial file, not append to it",
            store.modelFile(model).readBytes().contentEquals(content),
        )
    }

    @Test
    fun `recovers when the server answers 416`() = runBlocking {
        val content = Random(6).nextBytes(32 * 1024)
        val model = descriptorFor("range-416", "/g.litertlm", content)

        // A partial longer than the real file: nothing useful can be resumed.
        store.partialFile(model).writeBytes(ByteArray(content.size * 2))

        server.enqueue(MockResponse.Builder().code(416).build())
        server.enqueue(mockResponse(200, content))

        val events = downloader.download(model).toList()

        assertTrue("Expected Completed, got ${events.lastOrNull()}", events.lastOrNull() is DownloadEvent.Completed)
        assertTrue(store.modelFile(model).readBytes().contentEquals(content))
    }

    @Test
    fun `a checksum mismatch fails and deletes the file`() = runBlocking {
        val content = Random(7).nextBytes(8192)
        val model = descriptorFor("bad-digest", "/b.litertlm", content)
            .copy(sha256 = "f".repeat(64)) // deliberately wrong

        server.enqueue(mockResponse(200, content))

        val events = downloader.download(model).toList()
        val failure = events.filterIsInstance<DownloadEvent.Failed>().singleOrNull()

        assertTrue("A wrong digest must fail the download", failure != null)
        assertTrue(
            "The corrupt file must not be left where it could be loaded",
            !store.isInstalled(model),
        )
        assertFalse("The partial must not be left behind either", store.partialFile(model).exists())
    }

    @Test
    fun `a short body fails on the size check`() = runBlocking {
        val content = Random(8).nextBytes(8192)
        // Catalogue claims more bytes than the server sends.
        val model = descriptorFor("short", "/s.litertlm", content, declaredSize = content.size + 4096L)
        server.enqueue(mockResponse(200, content))

        val events = downloader.download(model).toList()

        assertTrue(
            "A truncated body must fail rather than pass the digest check",
            events.lastOrNull() is DownloadEvent.Failed,
        )
    }

    @Test
    fun `an http error is reported as a failure not an exception`() = runBlocking {
        val content = Random(9).nextBytes(1024)
        val model = descriptorFor("error", "/e.litertlm", content)
        server.enqueue(MockResponse.Builder().code(503).build())

        val events = downloader.download(model).toList()

        val failure = events.filterIsInstance<DownloadEvent.Failed>().singleOrNull()
        assertTrue("Expected a Failed event", failure != null)
        assertTrue(
            "The message should name the status code, got: ${failure!!.error.message}",
            failure.error.message?.contains("503") == true,
        )
    }

    @Test
    fun `insufficient storage is refused before any request is made`() = runBlocking {
        // A declared size beyond any real disk. The point is that the check
        // happens before the download starts rather than after it fails.
        val content = Random(10).nextBytes(128)
        val model = descriptorFor("huge", "/h.litertlm", content, declaredSize = Long.MAX_VALUE / 4)
            .copy(sha256 = "0".repeat(64))

        val events = downloader.download(model).toList()

        val failure = events.filterIsInstance<DownloadEvent.Failed>().singleOrNull()
        assertTrue("Expected a storage failure", failure != null)
        assertTrue(
            "Expected InsufficientStorage, got ${failure!!.error::class.simpleName}",
            failure.error is InsufficientStorage,
        )
        assertEquals("No HTTP request should have been attempted", 0, server.requestCount)
    }

    // ---------------------------------------------------------- redirects ---

    @Test
    fun `follows a redirect and keeps the range header`() = runBlocking {
        // This is what the real service does. Verified against huggingface.co:
        // a Range request is answered with "302 Found" and a Location on
        // cas-bridge.xethub.hf.co, a different host. If the Range header is
        // dropped on that hop, the CDN replies with the whole file, and the
        // "server ignored Range" path restarts the download from zero -- so a
        // resumed download silently becomes a full one.
        val content = Random(11).nextBytes(64 * 1024)
        val cdn = MockWebServer().apply { start() }
        try {
            val model = descriptorFor("redirect", "/r.litertlm", content)
            val half = content.size / 2
            store.partialFile(model).writeBytes(content.copyOfRange(0, half))

            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", cdn.url("/cdn.litertlm").toString())
                    .build()
            )
            cdn.enqueue(
                mockResponse(206, content.copyOfRange(half, content.size)) {
                    addHeader("Content-Range", "bytes $half-${content.size - 1}/${content.size}")
                }
            )

            val events = downloader.download(model).toList()

            assertTrue("Expected Completed, got ${events.lastOrNull()}", events.lastOrNull() is DownloadEvent.Completed)
            assertTrue(
                "The file must be reconstructed across the redirect",
                store.modelFile(model).readBytes().contentEquals(content),
            )

            val forwarded = cdn.takeRequest().headers["Range"]
            assertEquals(
                "The Range header did not survive the redirect; the CDN would have sent the whole file",
                "bytes=$half-",
                forwarded,
            )
        } finally {
            cdn.close()
        }
    }

    // ------------------------------------------------------------- mirror ---

    @Test
    fun `the official host is used when it works, without touching the mirror`() = runBlocking {
        // The privacy property that matters: a device that can reach the
        // official host must never contact a third party.
        val content = Random(12).nextBytes(16 * 1024)
        val mirror = MockWebServer().apply { start() }
        try {
            val model = descriptorFor("official-first", "/o.litertlm", content)
                .copy(fallbackUrl = mirror.url("/o.litertlm").toString())
            server.enqueue(mockResponse(200, content))

            val events = downloader.download(model).toList()

            assertTrue(events.lastOrNull() is DownloadEvent.Completed)
            assertEquals("The mirror must not be contacted at all", 0, mirror.requestCount)
        } finally {
            mirror.close()
        }
    }

    @Test
    fun `falls back to the mirror when the official host refuses`() = runBlocking {
        val content = Random(13).nextBytes(16 * 1024)
        val mirror = MockWebServer().apply { start() }
        try {
            val model = descriptorFor("mirror-fallback", "/m.litertlm", content)
                .copy(fallbackUrl = mirror.url("/m.litertlm").toString())

            server.enqueue(MockResponse.Builder().code(503).build())
            mirror.enqueue(mockResponse(200, content))

            val events = downloader.download(model).toList()

            assertTrue(
                "Expected Completed via the mirror, got ${events.lastOrNull()}",
                events.lastOrNull() is DownloadEvent.Completed,
            )
            assertTrue(
                "The mirror's bytes must be verified exactly like the official host's",
                store.modelFile(model).readBytes().contentEquals(content),
            )
        } finally {
            mirror.close()
        }
    }

    @Test
    fun `a mirror serving the wrong bytes is rejected, not accepted`() = runBlocking {
        // The reason a third-party mirror is acceptable at all. Without this the
        // fallback would be a way to get arbitrary content onto the device.
        val content = Random(14).nextBytes(8192)
        val wrong = Random(99).nextBytes(8192)
        val mirror = MockWebServer().apply { start() }
        try {
            val model = descriptorFor("mirror-tampered", "/t.litertlm", content)
                .copy(fallbackUrl = mirror.url("/t.litertlm").toString())

            server.enqueue(MockResponse.Builder().code(503).build())
            mirror.enqueue(mockResponse(200, wrong))

            val events = downloader.download(model).toList()

            assertTrue(
                "Content that does not match the digest must fail",
                events.lastOrNull() is DownloadEvent.Failed,
            )
            assertFalse(store.isInstalled(model))
        } finally {
            mirror.close()
        }
    }
}
