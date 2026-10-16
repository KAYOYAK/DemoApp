package com.kayoyak.demoapp.inference

import androidx.test.platform.app.InstrumentationRegistry
import com.kayoyak.demoapp.model.ModelCatalogue
import java.io.File

/**
 * The model an instrumented test can actually exercise on this device, and the
 * places it can be.
 *
 * The weights are not in the repository (see docs/PRINCIPLES.md -- weights are
 * never bundled), so every test that needs one starts by asking this object. The
 * order matters:
 *
 *  0. A file the run names, if [orNull]'s `fileName` is given or the run passes
 *     `-e demoapp.modelFile <name>` to the instrumentation. Looked for in the
 *     app's own `files/models/` first, then in `/data/local/tmp/models`, and it
 *     wins over the catalogue entry. A run names a file to choose which of the
 *     models on the device it measures: on 2026-10-16 the sampler comparison needed
 *     that, because the catalogue's own entry looked silent on this emulator — it
 *     was answering on a channel the engine did not read, which is fixed (see
 *     `LiteRtLlmEngine.channelTexts`), and the comparison now runs on it too. The
 *     log says which file answered.
 *
 *     The argument exists because comparing two models means having both on the
 *     device, and choosing between them must not cost an edit and a rebuild:
 *     `scripts/run-instrumented-test.ps1 -ModelFile <name>` passes it, and it
 *     outranks the parameter, since a caller that names a file on the command
 *     line has said which one it means.
 *
 *  1. `files/models/` inside the app, where the application's own download puts
 *     it. This is the faithful path and the one to use when it is available:
 *
 *         adb push Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm /data/local/tmp/
 *         adb shell "run-as com.kayoyak.demoapp sh -c \
 *           'cat /data/local/tmp/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm \
 *            > files/models/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm'"
 *
 *  2. Any `.litertlm` in `/data/local/tmp/models`, which is for a machine that
 *     has a variant of the model but not that exact file. The engine still
 *     loads real weights and generates real tokens; what is not exercised is the
 *     catalogue's file. The log says which one was used.
 *
 * With neither present the tests report SKIPPED and pass, so a run without a
 * 329 MB download is not broken. Read the log rather than trusting a green
 * result: a skipped run proves nothing.
 *
 * Every caller passes its own [prefix] so that a log line says which test
 * produced it, and so that the two tests sharing this object cannot be confused
 * for each other in a batch's output.
 *
 * Nothing here throws for a stale id. The first version of this lookup did, in
 * the engine test, and turned "nothing to test" into a red build; the reason it
 * cannot now is that a missing model, a stale id and an empty fallback directory
 * are all the same answer to the caller -- skip, and say why.
 */
internal object TestModel {

    /**
     * The catalogue entry the inference tests exercise: the smallest one, so
     * that a device with little memory can still run them.
     */
    private const val DEFAULT_ID = "qwen3-0.6b-int4"

    /** Where the model went the last time somebody set this machine up. */
    private val handDroppedDir = File("/data/local/tmp/models")

    /** The instrumentation argument that names the model file for one run. */
    private const val ARGUMENT_MODEL_FILE = "demoapp.modelFile"

    /** A model on the device, with the name to log it under. */
    internal data class UnderTest(
        val id: String,
        val displayName: String,
        val file: File,
        /**
         * Whether this is the catalogue's own file, so a log line says which of
         * the two locations answered rather than leaving it to be inferred.
         */
        val catalogueFile: Boolean,
    )

    /**
     * The model this run can exercise, or null when there is none.
     *
     * @param prefix the test's own log prefix, printed with the reason for a
     *   skip so the message survives being read out of a batch's console.
     * @param id the catalogue entry to prefer; the app's own download names the
     *   file, so a caller that needs a different model passes it here.
     * @param fileName a model file to use before the catalogue entry, for a test
     *   that needs one known to answer on this device. The `demoapp.modelFile`
     *   instrumentation argument outranks it. Absent or not on the device, the
     *   lookup falls through to @param id and says so.
     */
    fun orNull(prefix: String, id: String = DEFAULT_ID, fileName: String? = null): UnderTest? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as com.kayoyak.demoapp.DemoApplication).container

        // The command line wins over the parameter, and a blank argument is not a
        // name: `-e demoapp.modelFile ""` is what an unset shell variable looks
        // like, and treating it as a file name would report every model missing.
        val named = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_MODEL_FILE)
            ?.takeIf { it.isNotBlank() }
            ?: fileName

        val appModels = File(context.filesDir, "models")
        if (named != null) {
            val file =
                listOf(appModels, handDroppedDir)
                    .map { File(it, named) }
                    .firstOrNull { it.isFile && it.length() > 0L }
            if (file != null) {
                println(
                    "$prefix: NOTE - using the named model $named " +
                        "(${file.length()} bytes, from ${file.parentFile?.absolutePath}); " +
                        "the catalogue entry '$id' is not what this run measures.",
                )
                return UnderTest(named, named, file, catalogueFile = false)
            }
            println(
                "$prefix: NOTE - the named model $named is not on this device; " +
                    "falling through to the catalogue entry '$id'",
            )
        }

        val descriptor = ModelCatalogue.byId(id)
        if (descriptor == null) {
            println("$prefix: SKIPPED - '$id' is not in the catalogue any more")
            return null
        }

        val catalogue = container.modelStore.modelFile(descriptor)
        if (catalogue.isFile && catalogue.length() > 0L) {
            return UnderTest(id, descriptor.displayName, catalogue, catalogueFile = true)
        }

        val handDropped =
            handDroppedDir.listFiles()
                ?.filter { it.isFile && it.length() > 0L && it.name.endsWith(".litertlm") }
                ?.sortedBy { it.length() }
                ?.firstOrNull()
        if (handDropped != null) {
            println(
                "$prefix: NOTE - ${descriptor.fileName} is not installed; " +
                    "falling back to the hand-dropped ${handDropped.name} " +
                    "(${handDropped.length()} bytes). The engine is exercised; " +
                    "the catalogue's own file is not.",
            )
            return UnderTest(handDropped.name, handDropped.name, handDropped, catalogueFile = false)
        }

        println(
            "$prefix: SKIPPED - neither ${descriptor.fileName} in the app's " +
                "files/models, nor any .litertlm in $handDroppedDir",
        )
        return null
    }
}
