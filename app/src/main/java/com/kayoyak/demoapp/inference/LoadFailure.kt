package com.kayoyak.demoapp.inference

/**
 * Why a model would not load, in terms a person can act on.
 *
 * ## The shape every one of these follows
 *
 * A message has three parts: what happened, why, and what to do. That structure is
 * older than this project and it is right — a message with only the first leaves
 * the reader stuck, and one with only the third reads as an instruction with no
 * reason behind it.
 *
 * ## Why the raw reason is kept rather than dropped
 *
 * The runtime's own text is unreadable: a stack of library class names, a number,
 * and a sentence written for whoever wrote the library. Handing that to the user
 * is not an error message, it is a shrug with detail.
 *
 * Dropping it entirely is also wrong. It is the only thing that makes a report
 * actionable — without it, "the model would not load" is a sentence nobody can
 * investigate, including the person who wrote this. So it is carried, shown behind
 * a control, and never required to understand the message on screen.
 */
sealed interface LoadFailure {

    /** What went wrong, in the app's own words. */
    val kind: Kind

    /**
     * The runtime's own account, or null when there is none.
     *
     * Not translated, not summarised, and not shown unless asked for.
     */
    val detail: String?

    enum class Kind {
        /** The file is not where it should be, or is not a model at all. */
        MISSING_OR_UNREADABLE,

        /** The device does not have room to hold it. */
        NOT_ENOUGH_MEMORY,

        /** The file is there but this device or build cannot run it. */
        UNSUPPORTED,

        /** The runtime started and then failed. */
        ENGINE_FAILED,

        /** Nothing else fits. */
        UNKNOWN,
    }

    companion object {
        /**
         * Reads a failure out of whatever the engine threw.
         *
         * Matching on the message is unavoidable here: the runtime reports through
         * a handful of exception types and platform errors, and the distinction
         * between "no room" and "broken file" is not in the type. The patterns are
         * deliberately narrow -- a false positive gives the user the wrong advice,
         * which is worse than the catch-all.
         */
        fun of(error: Throwable): LoadFailure {
            val text = buildString {
                append(error::class.java.simpleName)
                append(' ')
                append(error.message.orEmpty())
                var cause = error.cause
                var depth = 0
                while (cause != null && depth < 4) {
                    append(' ')
                    append(cause::class.java.simpleName)
                    append(' ')
                    append(cause.message.orEmpty())
                    cause = cause.cause
                    depth++
                }
            }
            val lower = text.lowercase()

            val kind = when {
                // Out of memory, in the spellings the platform uses for it.
                "outofmemory" in lower || "out of memory" in lower ||
                    "no space" in lower || "enospc" in lower ||
                    "cannot allocate" in lower || "failed to allocate" in lower -> Kind.NOT_ENOUGH_MEMORY

                // The file itself.
                "filenotfound" in lower || "no such file" in lower ||
                    "enoent" in lower || "is not a file" in lower -> Kind.MISSING_OR_UNREADABLE

                // Present, but not runnable here.
                "unsupported" in lower || "not supported" in lower ||
                    "invalid model" in lower || "invalid flatbuffer" in lower ||
                    "no such backend" in lower || "backend not found" in lower ||
                    "unknown model" in lower -> Kind.UNSUPPORTED

                // The runtime came up and then something went wrong inside it.
                "illegalstate" in lower || "runtimeerror" in lower ||
                    "failed to create" in lower || "failed to initialize" in lower ||
                    "failed to initialize" in lower -> Kind.ENGINE_FAILED

                else -> Kind.UNKNOWN
            }

            return Simple(kind, text.trim().takeIf { it.isNotBlank() })
        }

        /** The only implementation; the interface exists so callers cannot invent one. */
        private data class Simple(
            override val kind: Kind,
            override val detail: String?,
        ) : LoadFailure
    }
}

/**
 * The three-part message for a failure, as a resource.
 *
 * At file scope rather than in the sealed type: the reasons live in the inference
 * package, which knows nothing about resources, and this project keeps every word
 * a person reads in one place. The mapping is the one place the two meet.
 */
@androidx.annotation.StringRes
fun LoadFailure.Kind.messageRes(): Int = when (this) {
    LoadFailure.Kind.MISSING_OR_UNREADABLE -> com.kayoyak.demoapp.R.string.load_fail_missing
    LoadFailure.Kind.NOT_ENOUGH_MEMORY -> com.kayoyak.demoapp.R.string.load_fail_memory
    LoadFailure.Kind.UNSUPPORTED -> com.kayoyak.demoapp.R.string.load_fail_unsupported
    LoadFailure.Kind.ENGINE_FAILED -> com.kayoyak.demoapp.R.string.load_fail_engine
    LoadFailure.Kind.UNKNOWN -> com.kayoyak.demoapp.R.string.load_fail_unknown
}