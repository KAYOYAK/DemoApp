package com.kayoyak.demoapp.diagnostics

/**
 * The text a user sends when something went wrong.
 *
 * Assembled from facts the app already has, and passed through
 * [DiagnosticsRedactor] before it reaches a screen. This object holds no
 * `Context` and writes nothing: it takes what it is given, so a test can pin the
 * format down without a device.
 *
 * ## What is in it, and what deliberately is not
 *
 * In: the version, the device, the memory, which model is loaded and on which
 * processor, how full the conversation is, and the last failure with its stack.
 * Every one of those is something a bug can be caused by, and every one is safe.
 *
 * Out: **the conversation**. The app's promise is that what a person types stays
 * with them, and a report is about the app failing rather than about what was
 * being discussed when it did. The transcript is not read, not counted, not
 * summarised -- there is no code path from here to it.
 */
object DiagnosticsReport {

    /**
     * Everything worth sending, as the user will see it.
     *
     * The labels are passed in rather than written here for the same reason the
     * export labels are: this is a data-layer object and the app's language lives
     * in one place. A literal here would be text that ships in the wrong language
     * and is only ever seen after someone has a problem.
     */
    fun build(facts: Facts, labels: Labels, crash: String? = null): String {
        val raw = buildString {
            appendLine(labels.heading)
            appendLine("=".repeat(labels.heading.length))
            appendLine()

            appendLine("${labels.app}: ${facts.appVersion} (${facts.buildType})")
            appendLine("${labels.android}: ${facts.androidRelease} (API ${facts.sdkInt})")
            appendLine("${labels.device}: ${facts.manufacturer} ${facts.model}, ${facts.abis}")
            // A line reading "soc: unknown" is one somebody spends time wondering
            // about, and the platform's fallback for a device that never set the
            // property is exactly that string rather than an absent one.
            facts.socModel?.takeIf { it != android.os.Build.UNKNOWN }?.let { soc ->
                appendLine("${labels.soc}: ${facts.socManufacturer.orEmpty()} $soc".trim())
            }
            appendLine(
                "${labels.memory}: ${facts.availableMemoryMb} / ${facts.totalMemoryMb} MB",
            )
            appendLine(
                "${labels.heap}: ${facts.usedHeapMb} / ${facts.maxHeapMb} MB",
            )
            appendLine()

            appendLine("${labels.model}: ${facts.modelId ?: labels.none}")
            appendLine("${labels.backend}: ${facts.backend ?: labels.none}")
            appendLine(
                "${labels.context}: " +
                    if (facts.contextTokens != null && facts.contextWindow != null) {
                        "${facts.contextTokens} / ${facts.contextWindow}"
                    } else {
                        labels.none
                    },
            )
            appendLine(
                "${labels.conversations}: ${facts.conversationCount}, " +
                    "${labels.models}: ${facts.installedModelCount}",
            )
            appendLine()

            facts.lastError?.let {
                appendLine("${labels.lastError}:")
                appendLine(it)
                appendLine()
            }

            crash?.let {
                appendLine("${labels.lastCrash}:")
                appendLine(it)
                appendLine()
            }

            if (facts.lastError == null && crash == null) {
                appendLine(labels.noError)
                appendLine()
            }

            appendLine("---")
            appendLine(labels.footer)
        }
        return DiagnosticsRedactor.redact(raw)
    }

    /**
     * The facts, gathered by the caller.
     *
     * A plain record rather than live objects: the report is a snapshot of a
     * moment, and reading from the engine while building it would mean the numbers
     * described a different moment from the failure.
     */
    data class Facts(
        val appVersion: String,
        val buildType: String,
        val androidRelease: String,
        val sdkInt: Int,
        val manufacturer: String,
        val model: String,
        val abis: String,
        /**
         * The SoC, as the platform reports it.
         *
         * The equivalent of the kernel variant a llama.cpp client shows. Which
         * quantised kernels a device actually selected decides whether an answer is
         * three times slower than the same model on another phone, and the SoC is the
         * thing that decides it. Without it, "slow" is a report nobody can act on.
         *
         * Null means the platform has no field to read at all, which is every release
         * before API 31. A device that has the field but never set the property
         * reports [android.os.Build.UNKNOWN] instead, and that is filtered where this
         * is rendered rather than where it is gathered: the sentinel is the
         * platform's, and this record stores what the platform said.
         */
        val socModel: String?,
        val socManufacturer: String?,
        val availableMemoryMb: Long,
        val totalMemoryMb: Long,
        val usedHeapMb: Long,
        val maxHeapMb: Long,
        /**
         * The runtime's own state, and every one of these used to have a default.
         *
         * No default here is a decision rather than the absence of one. Null for the
         * model or the backend renders as "none", and zero for a count renders as
         * zero, so a caller that forgot one produced a report that looked complete
         * and was wrong -- and looked identical to the report from a device with no
         * model loaded. That is the one failure a diagnostics report cannot have,
         * because it is the only channel this app has. Required, so leaving one out
         * is a compiler error instead of a sentence about a device that does not
         * exist.
         */
        val modelId: String?,
        val backend: String?,
        val contextTokens: Int?,
        val contextWindow: Int?,
        val conversationCount: Int,
        val installedModelCount: Int,
        val lastError: String?,
    )

    data class Labels(
        val heading: String,
        val app: String,
        val android: String,
        val device: String,
        /** The SoC, which decides which quantised kernels the device actually runs. */
        val soc: String,
        val memory: String,
        val heap: String,
        val model: String,
        val backend: String,
        val context: String,
        val conversations: String,
        val models: String,
        val lastError: String,
        val lastCrash: String,
        val noError: String,
        val none: String,
        val footer: String,
    )
}
