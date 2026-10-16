package com.kayoyak.demoapp.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding whether a copy of the app is one this project built.
 *
 * A repackaged app is a real risk for something people install without a store:
 * someone takes the published APK, adds to it, signs it with their own key, and
 * puts it back online. To the user it looks right.
 *
 * The dangerous mistakes are the two that make the check useless rather than
 * noisy. A check that says "tampered" when it cannot tell teaches people to ignore
 * it; a check that says "fine" when it cannot tell is worse, because it is a
 * reassurance that was never earned.
 */
class AppSignatureTest {

    /**
     * Full-length fingerprints, because [AppSignature.decide] refuses anything that
     * is not one.
     *
     * The three-byte strings these used to be were not a fingerprint any tool
     * produces, and they hid the comparison's real weakness: it was exact, so a real
     * key pasted in the other case or without separators would have been read as
     * "signed by someone else".
     */
    private val ours = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:" +
        "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
    private val theirs = "11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:FF:" +
        "11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:FF"

    @Test
    fun `no recorded key means no opinion, not a pass`() {
        val verdict = AppSignature.decide(expected = emptySet(), actual = setOf(ours))

        assertEquals(AppSignature.Verdict.UNKNOWN, verdict)
    }

    @Test
    fun `a device that will not report is not evidence of tampering`() {
        // Telling someone their app has been meddled with because a platform call
        // failed would be worse than saying nothing.
        val verdict = AppSignature.decide(expected = setOf(ours), actual = emptySet())

        assertEquals(AppSignature.Verdict.UNKNOWN, verdict)
    }

    @Test
    fun `the right key is recognised`() {
        val verdict = AppSignature.decide(expected = setOf(ours), actual = setOf(ours))

        assertEquals(AppSignature.Verdict.PUBLISHED, verdict)
    }

    @Test
    fun `a different key is reported, with what it actually was`() {
        // The fingerprints travel with the verdict so the user can compare them
        // with the published ones. "Something is wrong" is not actionable.
        val verdict = AppSignature.decide(expected = setOf(ours), actual = setOf(theirs))

        assertTrue(verdict is AppSignature.Verdict.OTHER)
        assertEquals(setOf(theirs), (verdict as AppSignature.Verdict.OTHER).actual)
    }

    @Test
    fun `a build signed with more than one key still counts as ours`() {
        // Key rotation, and apps signed for more than one distribution, both
        // produce this. Requiring every fingerprint to match would call a legitimate
        // build tampered with, which is the failure that costs the feature its
        // credibility.
        val verdict = AppSignature.decide(
            expected = setOf(ours),
            actual = setOf(theirs, ours),
        )

        assertEquals(AppSignature.Verdict.PUBLISHED, verdict)
    }

    @Test
    fun `more than one recorded key still matches`() {
        val verdict = AppSignature.decide(
            expected = setOf(ours, theirs),
            actual = setOf(theirs),
        )

        assertEquals(AppSignature.Verdict.PUBLISHED, verdict)
    }

    @Test
    fun `a digest is written the way fingerprints are written`() {
        // Colon-separated uppercase pairs. It is displayed for a human to compare
        // against a published one, so the format is the interface.
        val digest = AppSignature.digest("abc".toByteArray())

        assertEquals(95, digest.length)
        assertTrue(digest.split(":").all { it.length == 2 })
        assertEquals(digest.uppercase(), digest)
    }

    @Test
    fun `a digest is stable`() {
        val bytes = ByteArray(1024) { it.toByte() }

        assertEquals(AppSignature.digest(bytes), AppSignature.digest(bytes))
    }

    @Test
    fun `the same fingerprint written in lower case is still recognised`() {
        // The tools disagree. `keytool`, the Play console and `apksigner` print
        // upper case; some dashboards and most copy-paste paths through a text
        // editor give lower case. Before this was normalised, filling `expected` in
        // from one of those produced "signed by someone else" for the project's own
        // release -- the one answer the feature exists to give, wrong in the
        // alarming direction, and invisible in every test that wrote both sides the
        // same way.
        val verdict = AppSignature.decide(
            expected = setOf(ours.lowercase()),
            actual = setOf(ours),
        )

        assertEquals(AppSignature.Verdict.PUBLISHED, verdict)
    }

    @Test
    fun `a fingerprint pasted without its separators is still recognised`() {
        // A fingerprint copied out of a JSON blob, or out of output where the colons
        // were lost on the way through a shell.
        val verdict = AppSignature.decide(
            expected = setOf(ours.replace(":", "")),
            actual = setOf(ours),
        )

        assertEquals(AppSignature.Verdict.PUBLISHED, verdict)
    }

    @Test
    fun `an entry that is not a fingerprint cannot cause a false alarm`() {
        // `expected` is filled in by hand. A stray line from the tool's output, a
        // comment kept next to the key, a typo -- none of them can match anything,
        // so keeping them would only risk reporting a genuine build as repackaged.
        val verdict = AppSignature.decide(
            expected = setOf("the release key, see RELEASING.md", ours),
            actual = setOf(ours),
        )

        assertEquals(AppSignature.Verdict.PUBLISHED, verdict)
    }

    @Test
    fun `an expected set holding nothing usable means no opinion`() {
        // Every entry unparseable is the same position as an empty set: the project
        // has not recorded a key the app can compare against. Saying so is honest;
        // reporting a repackaged copy would not be.
        val verdict = AppSignature.decide(
            expected = setOf("AA:BB:CC", "not a fingerprint"),
            actual = setOf(ours),
        )

        assertEquals(AppSignature.Verdict.UNKNOWN, verdict)
    }

    @Test
    fun `a debug build is not reported as someone else's copy`() {
        // Every build installed from a development machine is signed with the debug
        // key, which is in no repository and in every developer's home directory.
        // Until this case existed, recording the release fingerprint turned every one
        // of those installs into "this copy was not signed by this project, someone
        // may have repackaged it" -- the same alarming answer as the fingerprint
        // format bug, produced by the ordinary act of running a debug build.
        val verdict = AppSignature.decide(
            expected = setOf(ours),
            actual = setOf(theirs),
            debuggable = true,
        )

        assertEquals(AppSignature.Verdict.DEVELOPMENT, verdict)
    }

    @Test
    fun `a debug build signed with the release key is still ours`() {
        // The debug flag chooses which sentence a mismatch is reported with. It is
        // not evidence on its own: whoever repackaged the app can set it, so a
        // match has to win over it rather than being suppressed by it.
        val verdict = AppSignature.decide(
            expected = setOf(ours),
            actual = setOf(ours),
            debuggable = true,
        )

        assertEquals(AppSignature.Verdict.PUBLISHED, verdict)
    }

    @Test
    fun `a release build signed by another key is not excused by the debug rule`() {
        // The other side of the same decision: on a build that is not debuggable, a
        // mismatch is the finding the feature exists to give.
        val verdict = AppSignature.decide(
            expected = setOf(ours),
            actual = setOf(theirs),
            debuggable = false,
        )

        assertTrue(verdict is AppSignature.Verdict.OTHER)
    }

    @Test
    fun `the recorded release fingerprint is a fingerprint`() {
        // The set is filled in by hand from `keytool` output, and two mistakes are
        // silent. Emptying it turns the check back into a display that always says
        // UNKNOWN -- the feature gone, with every other test still green, because a
        // missing answer looks exactly like a device that will not report. A typo in
        // it is dropped by the normaliser, which has the same effect.
        assertEquals(1, AppSignature.expected.size)

        val recorded = AppSignature.expected.single()
        assertEquals(64, recorded.filter { it != ':' }.length)
        assertTrue(recorded.all { it in "0123456789ABCDEF:" })

        // The same value on both sides has to come back as a match. That is what
        // proves the normaliser keeps it: a fingerprint one digit short, or with a
        // letter outside A-F, is dropped rather than compared, and the check would go
        // quietly back to UNKNOWN for every install while this file stayed green.
        assertEquals(
            AppSignature.Verdict.PUBLISHED,
            AppSignature.decide(
                expected = AppSignature.expected,
                actual = AppSignature.expected,
            ),
        )
    }
}