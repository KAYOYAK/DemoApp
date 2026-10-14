package com.example.demoapp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory check, and the two ways it can be wrong.
 *
 * Too strict and it refuses models that would have run, taking away a choice the
 * user can reasonably make. Too loose and the app dies with nothing on screen to
 * explain it -- which is the failure this exists to prevent, and the one that
 * cannot be caught afterwards.
 */
class ModelMemoryTest {

    private val GB = 1024L * 1024 * 1024

    /** The catalogue's own sizes, so the test is about this app's models. */
    private val smallest = 344_671_744L // qwen3-0.6b-int4
    private val largest = 2_056_729_520L // qwen3-1.7b

    @Test
    fun `a model needs half again its file size`() {
        assertEquals(517_007_616L, ModelMemory.estimateBytes(smallest))
    }

    @Test
    fun `a device gives a model sixty percent of its ram`() {
        assertEquals((8 * GB * 0.60).toLong(), ModelMemory.budgetBytes(8 * GB))
    }

    @Test
    fun `the smallest model fits anywhere`() {
        // 329 MB × 1.5 ≈ 493 MB, against a 4 GB device's 2.4 GB budget.
        assertTrue(ModelMemory.judge(smallest, 4 * GB) is ModelMemory.Verdict.Fits)
    }

    @Test
    fun `every model in the catalogue fits on an eight gigabyte device`() {
        // Worth pinning: this app's models are small by design, so the honest
        // answer on a normal phone is "all of them". A check that started refusing
        // them would be a check that is wrong, not one that is being careful.
        val verdict = ModelMemory.judge(largest, 8 * GB)
        assertTrue(
            "2.0 GB x 1.5 = 2.9 GB, against a 4.8 GB budget",
            verdict is ModelMemory.Verdict.Fits,
        )
    }

    @Test
    fun `the largest model is tight on four gigabytes`() {
        // 2.9 GB against a 2.4 GB budget: too big, and the screen has to say so
        // rather than letting the process die.
        val verdict = ModelMemory.judge(largest, 4 * GB)
        assertTrue(verdict is ModelMemory.Verdict.TooBig)
        val tooBig = verdict as ModelMemory.Verdict.TooBig
        assertTrue("It says how much over", tooBig.overByBytes > 0)
        assertEquals(2_942L, tooBig.requiredBytes / (1024 * 1024))
        assertEquals(2_457L, tooBig.budgetBytes / (1024 * 1024))
    }

    @Test
    fun `a device that reports nothing does not refuse everything`() {
        // A runtime that cannot read the device total is not a device with no
        // memory. Treating it as zero would make every model look impossible.
        assertTrue(ModelMemory.judge(largest, 0L) is ModelMemory.Verdict.Fits)
    }

    @Test
    fun `the warning band starts at half the budget`() {
        // A 4 GB device: budget 2.4 GB, warning from 1.2 GB of model memory.
        // minicpm5-2b-int4 is 1.48 GB x 1.5 = 2.2 GB -- past the warning, under
        // the limit. It should load, with a word.
        val verdict = ModelMemory.judge(1_553_670_064L, 4 * GB)
        assertTrue(verdict is ModelMemory.Verdict.Tight)
        val tight = verdict as ModelMemory.Verdict.Tight
        assertTrue("It reports how much of the budget", tight.shareOfBudget > 0.5)
        assertTrue(tight.shareOfBudget <= 1.0)
    }

    @Test
    fun `the verdicts are ordered by size`() {
        // The property that matters: a bigger model is never judged more kindly
        // than a smaller one on the same device. A check that got this wrong would
        // let the largest model through and refuse a small one.
        val sizes = listOf(
            344_671_744L,
            614_230_168L,
            792_619_648L,
            964_861_904L,
            1_553_670_064L,
            1_833_451_520L,
            2_056_729_520L,
        )
        val severities = sizes.map { size ->
            when (ModelMemory.judge(size, 4 * GB)) {
                is ModelMemory.Verdict.Fits -> 0
                is ModelMemory.Verdict.Tight -> 1
                is ModelMemory.Verdict.TooBig -> 2
            }
        }

        assertEquals(
            "Severity must never drop as the model grows",
            severities.sorted(),
            severities,
        )
    }

    @Test
    fun `a smaller device is never more permissive than a larger one`() {
        val sizes = listOf(344_671_744L, 2_056_729_520L)

        sizes.forEach { size ->
            val onFour = ModelMemory.judge(size, 4 * GB)
            val onEight = ModelMemory.judge(size, 8 * GB)
            if (onFour is ModelMemory.Verdict.Fits) {
                assertTrue(
                    "8 GB must accept what 4 GB accepts",
                    onEight is ModelMemory.Verdict.Fits,
                )
            }
        }
    }
}
