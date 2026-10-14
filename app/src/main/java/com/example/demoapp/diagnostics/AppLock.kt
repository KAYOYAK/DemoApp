package com.example.demoapp.diagnostics

/**
 * Whether the app should be showing its lock screen.
 *
 * ## Why there is a timeout at all
 *
 * Locking only at launch protects against almost nothing. The attack this defends
 * against is a phone that is picked up while already unlocked, and an app that
 * stays open behind a home press is still open. Android's own guidance for
 * sensitive applications recommends re-authenticating on a timer; the figure it
 * suggests is fifteen minutes, which is what this uses.
 *
 * ## Why the timer restarts on leaving, not on a clock
 *
 * The clock starts when the app goes to the background, not when it was last
 * authenticated. Someone reading an answer for ten minutes and then locking the
 * phone has not left it unattended, and locking them out mid-sentence would make
 * the feature something to turn off.
 *
 * ## The timeout, and what it took to make it real
 *
 * `leftAt` is the parameter that starts that clock. It used to be passed `null` on
 * every launch — no caller ever gave it a value — so `away` was always zero,
 * `away > TIMEOUT_MILLIS` was always false, and **the fifteen minutes never
 * elapsed**. What locked was only the launch case. The app's own settings screen
 * said otherwise, in the app's own words: "每次打开应用（或离开超过 15 分钟再回来）都要先
 * 通过指纹/人脸或锁屏密码". A promise the code did not keep is the worst version of
 * this, because the user arranges their behaviour around it.
 *
 * Wired now: `MainActivity` stamps `leftAt` in `onStop`, refreshes `now` in
 * `onStart`, and both are Compose state — which is what makes the return from the
 * background *evaluate* the timeout rather than waiting for some later redraw.
 * `leftAt` is cleared on a successful unlock, so the fifteen minutes restart
 * instead of counting from a departure the user has already answered for.
 *
 * Both ends read elapsed-since-boot, so the interval is immune to the wall clock
 * being moved. That is not a detail: `currentTimeMillis` can be pushed backwards
 * by a network time sync or by the user, `now - leftAt` then goes negative, the
 * comparison is false, and the lock would never engage again for the rest of the
 * session. A reboot resets that clock, and a reboot also ends the session —
 * `unlockedAt` starts null, which locks — so the failure mode and the reset
 * coincide.
 *
 * ## What this is not
 *
 * It is not encryption. The conversations are on disk in the app's private
 * directory either way; the lock keeps them off the screen, and a device that is
 * rooted or backed up can still read them. Saying so where the user turns it on is
 * the difference between a feature and a false sense of one.
 */
object AppLock {

    /** How long the app may sit in the background before it locks again. */
    const val TIMEOUT_MILLIS = 15 * 60 * 1000L

    /**
     * Whether a lock is required right now.
     *
     * [enabled] is the user's setting. [leftAt] is when the app went to the
     * background, or null when it has not. [authenticatedAt] is when the user last
     * passed the prompt, or null when they have not yet this session.
     *
     * A device that cannot authenticate at all is not locked: a device with no
     * enrolled fingerprint and no screen lock would otherwise be locked out of its
     * own notes permanently, which is a worse outcome than the one being defended
     * against.
     */
    fun isLocked(
        enabled: Boolean,
        leftAt: Long?,
        authenticatedAt: Long?,
        now: Long,
        canAuthenticate: Boolean,
    ): Boolean {
        if (!enabled) return false
        if (!canAuthenticate) return false

        // Nothing has been authenticated yet this session, and there is something
        // to protect. This is the launch case.
        val since = authenticatedAt ?: return true

        // Authenticated, then left for longer than the timeout.
        val away = leftAt?.let { now - it } ?: 0L
        return away > TIMEOUT_MILLIS
    }
}
