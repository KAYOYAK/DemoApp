package com.example.demoapp.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report a user sends when something went wrong.
 *
 * It had no test until this one, which is how the defect it now pins survived
 * three rounds of looking at this file: `Facts` declared the runtime's own state
 * with defaults, and the one place that builds a `Facts` omitted four of those
 * fields -- so the report said "model: none", "backend: none" and "installed
 * models: 0" on every device, including one with a model resident and three on
 * disk. Nothing failed, because nothing read the output.
 *
 * The labels are deliberately uniform strings rather than Chinese text. The
 * report is expected to write nothing itself: every word on it is passed in, so a
 * literal here would be a word that ships in the wrong language and is only ever
 * seen after somebody has a problem.
 */
class DiagnosticsReportTest {

    private val labels = DiagnosticsReport.Labels(
        heading = "LBL-heading",
        app = "LBL-app",
        android = "LBL-android",
        device = "LBL-device",
        soc = "LBL-soc",
        memory = "LBL-memory",
        heap = "LBL-heap",
        model = "LBL-model",
        backend = "LBL-backend",
        context = "LBL-context",
        conversations = "LBL-conversations",
        models = "LBL-models",
        lastError = "LBL-lastError",
        lastCrash = "LBL-lastCrash",
        noError = "LBL-noError",
        none = "LBL-none",
        footer = "LBL-footer",
    )

    private fun facts(
        socModel: String? = "SM8650",
        socManufacturer: String? = "Qualcomm",
        modelId: String? = "qwen3-0.6b-int4",
        backend: String? = "CPU",
        contextTokens: Int? = 512,
        contextWindow: Int? = 4096,
        conversationCount: Int = 3,
        installedModelCount: Int = 2,
        lastError: String? = null,
    ) = DiagnosticsReport.Facts(
        appVersion = "1.0",
        buildType = "debug",
        androidRelease = "16",
        sdkInt = 36,
        manufacturer = "Google",
        model = "sdk_gphone64_x86_64",
        abis = "arm64-v8a, x86_64",
        socModel = socModel,
        socManufacturer = socManufacturer,
        availableMemoryMb = 2048,
        totalMemoryMb = 4096,
        usedHeapMb = 64,
        maxHeapMb = 256,
        modelId = modelId,
        backend = backend,
        contextTokens = contextTokens,
        contextWindow = contextWindow,
        conversationCount = conversationCount,
        installedModelCount = installedModelCount,
        lastError = lastError,
    )

    private fun report(
        facts: DiagnosticsReport.Facts = facts(),
        crash: String? = null,
    ) = DiagnosticsReport.build(facts, labels, crash)

    // ------------------------------------------------------- what is in it ---

    @Test
    fun `the report names the model, the backend and the context it was given`() {
        val out = report()

        assertTrue("No model on it:\n$out", out.contains("LBL-model: qwen3-0.6b-int4"))
        assertTrue("No backend on it:\n$out", out.contains("LBL-backend: CPU"))
        assertTrue(
            "The occupancy and the window are two numbers and read as one reading:\n$out",
            out.contains("LBL-context: 512 / 4096"),
        )
    }

    @Test
    fun `the counts and the memory are on the report`() {
        val out = report()

        assertTrue("No conversation count:\n$out", out.contains("LBL-conversations: 3"))
        assertTrue("No installed model count:\n$out", out.contains("LBL-models: 2"))
        assertTrue("No memory:\n$out", out.contains("LBL-memory: 2048 / 4096 MB"))
        assertTrue("No heap:\n$out", out.contains("LBL-heap: 64 / 256 MB"))
    }

    @Test
    fun `a device with no model loaded says so rather than printing nothing`() {
        // The two are different failures and the report has to tell them apart: a
        // loaded model whose fields went missing prints like a device that never
        // loaded one, and that is the report a user sends after "it got slow".
        val out = report(facts(modelId = null, backend = null, contextTokens = null))

        assertTrue("A missing model says nothing at all:\n$out", out.contains("LBL-model: LBL-none"))
        assertTrue("A missing backend says nothing at all:\n$out", out.contains("LBL-backend: LBL-none"))
        assertTrue(
            "Half a context reading is printed as if it were none:\n$out",
            out.contains("LBL-context: LBL-none"),
        )
    }

    @Test
    fun `the platform's unknown-soc sentinel never reaches the report`() {
        // A line reading "soc: unknown" is one somebody spends time wondering about,
        // and this is the string the platform reports for a device that has the
        // field and never set it -- so it is filtered where the report is rendered
        // rather than where the facts are gathered.
        val unknown = report(facts(socModel = android.os.Build.UNKNOWN))
        assertFalse("The sentinel was printed:\n$unknown", unknown.contains("LBL-soc"))

        val absent = report(facts(socModel = null, socManufacturer = null))
        assertFalse("A device with no SoC field printed one:\n$absent", absent.contains("LBL-soc"))

        val named = report()
        assertTrue("A real SoC is missing:\n$named", named.contains("LBL-soc: Qualcomm SM8650"))
    }

    // -------------------------------------------------- what is left out ---

    @Test
    fun `a report with nothing wrong with it says so`() {
        val out = report()

        assertTrue("Nothing says the app has not failed:\n$out", out.contains("LBL-noError"))
        assertFalse("An error section with no error:\n$out", out.contains("LBL-lastError"))
        assertFalse("A crash section with no crash:\n$out", out.contains("LBL-lastCrash"))
    }

    @Test
    fun `the last failure and the last crash are two sections`() {
        val out = report(facts(lastError = "boom"), crash = "trace")

        assertTrue(out.contains("LBL-lastError:"))
        assertTrue("The failure text is missing:\n$out", out.contains("boom"))
        assertTrue(out.contains("LBL-lastCrash:"))
        assertTrue("The crash text is missing:\n$out", out.contains("trace"))
        assertFalse("A report with failures says there were none:\n$out", out.contains("LBL-noError"))
    }

    @Test
    fun `what the report carries is redacted before it is returned`() {
        // The failure text is a stack trace, and a stack trace from this app is full
        // of paths that name the user's package and the models they downloaded.
        val out = report(facts(lastError = "Could not read /data/data/com.example.demoapp/files/models/x"))

        assertFalse("A path from the device survived:\n$out", out.contains("com.example.demoapp"))
        assertTrue("Redaction did not run at all:\n$out", out.contains("<路径已隐去>"))
    }

    // ------------------------------------------------- what words are on it ---

    @Test
    fun `every word on the report is one it was handed`() {
        // Two builds, because four labels only appear when their section does: a
        // report with nothing wrong and one with both a failure and a crash between
        // them use all of them.
        val out = report(facts(modelId = null, lastError = "boom"), crash = "trace")

        listOf(
            labels.heading, labels.app, labels.android, labels.device, labels.soc,
            labels.memory, labels.heap, labels.model, labels.backend, labels.context,
            labels.conversations, labels.models, labels.lastError, labels.lastCrash,
            labels.noError, labels.none, labels.footer,
        ).forEach { label ->
            // `noError` is the one that cannot appear alongside a failure, which is
            // asserted on its own above.
            if (label != labels.noError) {
                assertTrue("The label '$label' is not on the report:\n$out", out.contains(label))
            }
        }
    }

    @Test
    fun `the heading is underlined by its own length`() {
        // The underline is what makes the report readable as a block in an issue
        // tracker, and it was computed from the label rather than written.
        val out = report()
        val lines = out.lines()

        assertTrue("The heading is not the first line:\n$out", lines.first() == labels.heading)
        assertTrue(
            "The second line is not an underline of the heading:\n$out",
            lines[1] == "=".repeat(labels.heading.length),
        )
    }
}
