package com.example.demoapp.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import java.security.MessageDigest

/**
 * What this copy of the app was signed with.
 *
 * ## Why this exists
 *
 * A repackaged copy is a real risk for an app people install from somewhere other
 * than a store. Someone takes the published APK, adds something to it, signs it
 * with their own key, and puts it back online under the same name. To the user it
 * looks right: same icon, same screens, same behaviour, plus whatever was added.
 *
 * ## What this can and cannot do
 *
 * It detects, it does not prevent. Whoever repackaged it can delete this check --
 * they have the source. What it changes is that a copy that was not signed by the
 * project's own key says so, on screen, in the app's own words, rather than
 * behaving identically to the real one.
 *
 * The fingerprint is also shown where the user can read it. That is what makes
 * checking possible at all: a published fingerprint and a displayed one can be
 * compared by someone who has both. Without it, "check the signature" is advice
 * with no way to act on it.
 *
 * ## Why the expected set is empty by default
 *
 * The project has no release signing key yet, so there is nothing to compare
 * against, and a check that guesses would either pass everything or fail
 * everything. Empty means "no opinion" and draws no warning. It is filled in when
 * the key exists, which is the only moment the answer is knowable.
 */
object AppSignature {

    /**
     * The SHA-256 fingerprints the published build is signed with.
     *
     * Empty until there is a release key. Anything here is a claim that builds
     * signed with these are the project's, so it belongs next to the release
     * instructions rather than being invented now.
     */
    val expected: Set<String> = emptySet()

    /** What this installation is actually signed with, using [fingerprintOf]'s form. */
    fun fingerprints(context: Context): Set<String> {
        val info = packageInfo(context) ?: return emptySet()
        return runCatching {
            val signatures =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.signingInfo?.apkContentsSigners
                } else {
                    @Suppress("DEPRECATION")
                    info.signatures
                }
            signatures.orEmpty().map { digest(it.toByteArray()) }.toSet()
        }.getOrElse { emptySet() }
    }

    /**
     * Whether this copy is one the project built, or null when that cannot be
     * told.
     *
     * Null and "no" are deliberately different. A device that will not report its
     * signatures is not evidence of tampering, and telling the user their app has
     * been tampered with because a platform call failed would be worse than
     * saying nothing.
     *
     * Any match counts. A build signed with more than one key is normal -- a
     * rotation, or an app signed for more than one distribution -- and requiring
     * every fingerprint to match would call those tampered.
     */
    fun verdict(context: Context): Verdict =
        decide(expected = expected, actual = fingerprints(context))

    /**
     * The comparison, apart from the platform call that feeds it.
     *
     * Separated so it can be tested. Everything here is a decision about two sets
     * of strings, and a decision that can only be exercised on a real device is a
     * decision that will be wrong the first time it matters.
     */
    fun decide(expected: Set<String>, actual: Set<String>): Verdict = compare(
        expected = expected.mapNotNull { normaliseFingerprint(it) }.toSet(),
        actual = actual.mapNotNull { normaliseFingerprint(it) }.toSet(),
    )

    /**
     * The comparison once both sides are in one form.
     *
     * ## Why the normalisation is not optional
     *
     * `expected` is filled in by hand from whatever tool the release key is
     * checked with, and those tools disagree about how to write a fingerprint.
     * `keytool`, the Play console and `apksigner` print colon-separated uppercase;
     * some dashboards and most copy-paste paths through a text editor give
     * lowercase, and a fingerprint pasted from a JSON blob often has no separators
     * at all. A plain set membership test is exact, so any of those variants
     * produces `OTHER` — "signed by someone else" — for a build signed with the
     * project's own key.
     *
     * That failure is worse than it sounds. It is the one answer the feature exists
     * to give, it is wrong in the alarming direction, and it is invisible in every
     * test that writes both sides the same way. Somebody would fill in the key,
     * ship, and be told their own release is a repackaged copy.
     *
     * Anything that is not a fingerprint at all is dropped rather than compared: a
     * comment, a stray line from the output, a typo. An unparseable entry cannot
     * match anything, so keeping it would only risk a false `OTHER`.
     */
    private fun compare(expected: Set<String>, actual: Set<String>): Verdict = when {
        // Nothing to compare against. Not a pass and not a failure: the project
        // has not recorded its key, so the answer is not knowable here.
        expected.isEmpty() -> Verdict.UNKNOWN

        // The device would not say. Also not evidence of tampering -- telling
        // someone their app has been meddled with because a platform call failed
        // would be worse than saying nothing.
        actual.isEmpty() -> Verdict.UNKNOWN

        // Any match counts. A build signed with more than one key is normal -- a
        // key rotation, or an app signed for more than one distribution -- and
        // requiring every fingerprint to match would call those tampered.
        actual.any { it in expected } -> Verdict.PUBLISHED

        else -> Verdict.OTHER(actual)
    }

    /**
     * [fingerprint] in the one form [decide] compares, or null when it is not one.
     *
     * SHA-256 written as 32 hexadecimal pairs, uppercase, separated by colons.
     * Case and separators are the only things this forgives: a fingerprint that is
     * not 64 hexadecimal digits is not a fingerprint, and guessing at one would put
     * a value into the comparison that no tool produced.
     */
    private fun normaliseFingerprint(fingerprint: String): String? {
        val digits = fingerprint.filter { !it.isWhitespace() && it != ':' }.uppercase()
        if (digits.length != 64 || !digits.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return digits.chunked(2).joinToString(":")
    }

    /**
     * A sealed interface rather than an enum: one case carries the fingerprints it
     * found, and an enum cannot have a constant with a different argument list
     * from the others.
     */
    sealed interface Verdict {
        /** Signed by this project, as far as the configuration can tell. */
        data object PUBLISHED : Verdict

        /**
         * Signed by something else.
         *
         * The fingerprints travel with it so the user can compare them with the
         * published ones, rather than being told only that something is wrong.
         */
        data class OTHER(val actual: Set<String>) : Verdict

        /**
         * Not knowable here: either the project has not recorded its key, or the
         * device would not report what this copy is signed with.
         */
        data object UNKNOWN : Verdict
    }

    private fun packageInfo(context: Context): PackageInfo? = runCatching {
        val manager = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            // `GET_SIGNING_CERTIFICATES` is real API 28; the branch above is the 33+
            // path and this one runs on 26 and 27, where the constant is inlined by
            // the compiler but the flag is not recognised. That was already true and
            // already handled: this method is wrapped in `runCatching { }.getOrNull()`,
            // so a device that rejects the flag gets `null` from here, an empty
            // fingerprint set above, and the verdict UNKNOWN -- never a wrong answer.
            //
            // Suppressed rather than restructured because there is no better call on
            // 26 and 27. `PackageManager.GET_SIGNATURES` is the API 26 answer, is
            // what this replaced, and returns the same signatures on the versions
            // where both flags are defined; coming back to it would be a step
            // backwards. `lint` reads "requires API 28" from the constant and cannot
            // see the runtime check around it.
            @Suppress("DEPRECATION", "InlinedApi")
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }.getOrNull()

    /** Colon-separated uppercase pairs, which is how fingerprints are written. */
    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = ":") { "%02X".format(it) }
}
