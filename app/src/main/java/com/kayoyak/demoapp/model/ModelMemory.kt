package com.kayoyak.demoapp.model

/**
 * Whether a model can run on this device, and what to say when it cannot.
 *
 * ## Where the numbers come from
 *
 * Two independent mobile implementations agree, which is why they are used here
 * rather than guessed:
 *
 * - **A model needs about 1.5x its file size in RAM.** The weight file is only
 *   about two thirds of that; the rest is the KV cache for the context window,
 *   the activations during inference, and the runtime's own buffers. Measured
 *   breakdown from a shipping app: weights ~67%, KV cache ~20%, activations ~8%,
 *   buffers ~5%.
 * - **A device may give a model 60% of its total RAM.** The remainder is the
 *   system, the launcher, whatever the user has open, and this app's own heap.
 *
 * ## Why this is checked before loading rather than caught after
 *
 * A load that runs out of memory does not always fail politely. On the platform
 * this app runs on, a native allocation failure can terminate the process
 * outright -- no exception, no dialog, nothing on screen to explain it. Catching
 * that is not possible from here; refusing the load beforehand is.
 *
 * ## Why it warns rather than only blocking
 *
 * A model in the warning band runs, and usually runs fine on a device that is not
 * busy. Blocking it would take away a choice the user can reasonably make, and
 * refusing to do what someone asked on the grounds that it might be slow is a
 * worse failure than being slow. Below the limit it loads; past it, the app says
 * what it would need and how to get there.
 */
object ModelMemory {

    /**
     * What fraction of the device's RAM a model may be given.
     *
     * Not a tuning value chosen here: it is the figure the mobile apps that ship
     * this check use, and it is conservative on purpose. A model that fits at 70%
     * on a quiet phone is one that gets the app killed on a phone with a browser
     * open.
     */
    const val BUDGET_FRACTION = 0.60

    /**
     * Model file to memory, as a multiple.
     *
     * Applies to text models. A model with a separate projector or a different
     * runtime would need its own figure rather than this one stretched.
     */
    const val OVERHEAD_MULTIPLIER = 1.5

    /** Above this share of the budget, loading is worth a word of caution. */
    const val WARNING_FRACTION = 0.50 / BUDGET_FRACTION

    /** The RAM a model this size is expected to occupy. */
    fun estimateBytes(fileSizeBytes: Long): Long =
        (fileSizeBytes * OVERHEAD_MULTIPLIER).toLong()

    /** The most of its RAM this device should give to one model. */
    fun budgetBytes(totalDeviceRamBytes: Long): Long =
        (totalDeviceRamBytes * BUDGET_FRACTION).toLong()

    /**
     * What to do about a model on a device.
     *
     * A value rather than a boolean, because the three outcomes lead to three
     * different things on screen: nothing, a line of caution, or a refusal with a
     * reason and a way forward.
     */
    sealed interface Verdict {
        /** Enough room. */
        data class Fits(val requiredBytes: Long, val budgetBytes: Long) : Verdict

        /** Enough room, but not much of it. */
        data class Tight(
            val requiredBytes: Long,
            val budgetBytes: Long,
            /** How much of the budget it uses, 0..1. */
            val shareOfBudget: Double,
        ) : Verdict

        /** Not enough. The numbers are carried so the screen can say what to do. */
        data class TooBig(
            val requiredBytes: Long,
            val budgetBytes: Long,
            /** How much bigger than the budget it is. */
            val overByBytes: Long,
        ) : Verdict
    }

    /**
     * The memory any verdict is about.
     *
     * Every outcome carries it, so a caller that only wants to display the number
     * does not need to switch over three cases to find it.
     */
    fun requireBytes(verdict: Verdict): Long = when (verdict) {
        is Verdict.Fits -> verdict.requiredBytes
        is Verdict.Tight -> verdict.requiredBytes
        is Verdict.TooBig -> verdict.requiredBytes
    }

    /**
     * Judges a model against a device.
     *
     * The device's *total* RAM, not its free RAM. Free RAM moves by hundreds of
     * megabytes between one moment and the next, and a check against it would
     * approve a load and then refuse the same one a second later -- which reads as
     * the app being unreliable rather than as the device being busy. What the
     * device has installed is a fixed fact about it.
     */
    fun judge(
        fileSizeBytes: Long,
        totalDeviceRamBytes: Long,
    ): Verdict {
        val required = estimateBytes(fileSizeBytes)
        val budget = budgetBytes(totalDeviceRamBytes)

        // A device that reports nothing is not a device with no memory. Without
        // this an unknown total would make every model look too big.
        if (totalDeviceRamBytes <= 0L) return Verdict.Fits(required, Long.MAX_VALUE)

        return when {
            required > budget -> Verdict.TooBig(required, budget, required - budget)
            required >= (budget * WARNING_FRACTION).toLong() ->
                Verdict.Tight(required, budget, required.toDouble() / budget)
            else -> Verdict.Fits(required, budget)
        }
    }
}
