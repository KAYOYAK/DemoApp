package com.kayoyak.demoapp.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sampling values the app fixes, held so they cannot drift silently.
 *
 * ## Why these are pinned at all
 *
 * A sampler default is the kind of value that gets "fixed" by whoever is reading
 * it last. `topK` was 40 -- the runtime's own default, never argued for -- until
 * it was checked against the publisher's recommendation for a model in this
 * catalogue and found to be twice what that card says (see [GenerationConfig]).
 *
 * A test is the cheapest way to make the next change deliberate: it does not stop
 * anyone, and it does mean the number cannot move without someone deciding to
 * move it.
 *
 * The assertions are about the values, not about sampling quality. Nothing here
 * has been measured on a device, and a test cannot measure it.
 */
class GenerationConfigTest {

    @Test
    fun `topK stays inside the publisher's recommendation for the small models offered`() {
        // Qwen publishes top_k=20 for Qwen3.5-0.8B, which is in the catalogue. The
        // runtime's default of 40 was never justified; 20 matches the card, and a
        // value above it means the sampler considers more candidates than the
        // model's own publisher asks for.
        assertEquals(20, GenerationConfig().topK)
    }

    @Test
    fun `topP stays inside the range the same card recommends`() {
        val topP = GenerationConfig().topP
        assertTrue("top_p should be at least 0.95, was $topP", topP >= 0.95)
        assertTrue("top_p should not be 1.0, which disables the cut, was $topP", topP <= 1.0)
    }

    @Test
    fun `the default temperature is one the settings screen can represent`() {
        // It is only the value the screen opens on. If it were outside the offered
        // steps, the screen would have to say "custom" before the user had chosen
        // anything -- which is the kind of detail that reads as a bug.
        val tenths = (GenerationConfig().temperature * 10).toInt()
        assertTrue("temperature must be a whole number of tenths, was ${GenerationConfig().temperature}", tenths in 0..15)
    }

    @Test
    fun `maxTokens is the value the settings range allows`() {
        // Read into GenerationConfig from Settings at send time; this is only the
        // fallback, and a fallback outside the editable range would be unreachable
        // by the control that is supposed to own it.
        assertTrue(GenerationConfig().maxTokens in 64..4096)
    }

    @Test
    fun `a profile only changes the values it is about`() {
        val base = GenerationConfig()
        val precise = SamplingProfile.PRECISE.appliedTo(base)
        val varied = SamplingProfile.VARIED.appliedTo(base)

        // The seed is not what any of these is about, so none of them touches it --
        // and a test relying on a fixed seed keeps working whichever profile is in
        // play.
        assertEquals(base.seed, precise.seed)
        assertEquals(base.seed, varied.seed)
        assertEquals(base.maxTokens, precise.maxTokens)

        // Narrowing and widening must not be the same thing, or the choice means
        // nothing. This is the assertion that fails if someone later "simplifies"
        // the enum into one branch.
        assertTrue("precise must cut harder than varied", precise.topK < varied.topK)
        assertTrue("precise must cut harder than varied", precise.topP < varied.topP)
        assertTrue("precise must be cooler than varied", precise.temperature < varied.temperature)
    }

    @Test
    fun `the default profile is the app's own defaults`() {
        // The starting point is the argued one, not a compromise between the other
        // two: a user who never opens this control gets the publisher's figures.
        assertEquals(GenerationConfig(), SamplingProfile.BALANCED.appliedTo())
    }

    @Test
    fun `every profile keeps the sampler inside what the runtime accepts`() {
        SamplingProfile.entries.forEach { profile ->
            val config = profile.appliedTo()
            assertTrue("${profile.name} topK must be positive", config.topK > 0)
            assertTrue("${profile.name} topP must be a probability", config.topP in 0.0..1.0)
            assertTrue(
                "${profile.name} temperature must be inside the runtime's range",
                config.temperature in 0.0..2.0,
            )
        }
    }
}
