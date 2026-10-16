package com.kayoyak.demoapp.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app asks for the lock again.
 *
 * Both directions of the mistake are expensive. Locking too eagerly makes the
 * feature something to turn off, and a lock that has been turned off protects
 * nothing. Locking too rarely leaves the app open behind a home press, which is
 * the case this exists for.
 */
class AppLockTest {

    private val now = 10_000_000L

    @Test
    fun `a lock the user turned off is never shown`() {
        val locked = AppLock.isLocked(
            enabled = false,
            leftAt = now - AppLock.TIMEOUT_MILLIS * 10,
            authenticatedAt = null,
            now = now,
            canAuthenticate = true,
        )

        assertFalse(locked)
    }

    @Test
    fun `launching with nothing authenticated asks`() {
        // The case the feature is for: someone else picks up the phone.
        val locked = AppLock.isLocked(
            enabled = true,
            leftAt = null,
            authenticatedAt = null,
            now = now,
            canAuthenticate = true,
        )

        assertTrue(locked)
    }

    @Test
    fun `staying in the app does not re-ask`() {
        val locked = AppLock.isLocked(
            enabled = true,
            leftAt = null,
            authenticatedAt = now - 60_000,
            now = now,
            canAuthenticate = true,
        )

        assertFalse(locked)
    }

    @Test
    fun `a short absence does not re-ask`() {
        // Someone answering a message and coming back has not left the phone
        // unattended, and asking again would make the feature annoying enough to
        // turn off.
        val locked = AppLock.isLocked(
            enabled = true,
            leftAt = now - 60_000,
            authenticatedAt = now - 120_000,
            now = now,
            canAuthenticate = true,
        )

        assertFalse(locked)
    }

    @Test
    fun `a long absence asks again`() {
        val locked = AppLock.isLocked(
            enabled = true,
            leftAt = now - AppLock.TIMEOUT_MILLIS - 1,
            authenticatedAt = now - 3_600_000,
            now = now,
            canAuthenticate = true,
        )

        assertTrue(locked)
    }

    @Test
    fun `exactly at the timeout does not yet ask`() {
        // The boundary is stated rather than left to whichever comparison an
        // implementer reached for first. A minute either way would not matter to a
        // person; an unstested boundary is where an off-by-one lives.
        val locked = AppLock.isLocked(
            enabled = true,
            leftAt = now - AppLock.TIMEOUT_MILLIS,
            authenticatedAt = now - 3_600_000,
            now = now,
            canAuthenticate = true,
        )

        assertFalse(locked)
    }

    @Test
    fun `a device that cannot authenticate is not locked out of its own notes`() {
        // No enrolled fingerprint and no screen lock. Refusing to open would be a
        // worse outcome than the one being defended against, and the user could
        // not turn it off from inside an app they cannot open.
        val locked = AppLock.isLocked(
            enabled = true,
            leftAt = null,
            authenticatedAt = null,
            now = now,
            canAuthenticate = false,
        )

        assertFalse(locked)
    }

    @Test
    fun `the timeout is the fifteen minutes the platform guidance suggests`() {
        // Pinned so that changing it is a decision rather than an edit.
        assertTrue(AppLock.TIMEOUT_MILLIS == 15 * 60 * 1000L)
    }
}
