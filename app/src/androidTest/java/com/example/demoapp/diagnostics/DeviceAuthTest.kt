package com.example.demoapp.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.demoapp.ui.lock.DeviceAuth
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lock, checked against the device it is running on.
 *
 * `AppLock.isLocked` is a pure function and its own test covers every combination
 * of its inputs -- including `canAuthenticate`, which there is a parameter, so
 * every case in that file is a case someone wrote. The parameter's real source is
 * not: `DeviceAuth` asks the platform, and nothing ran it on a device before this
 * file. A constant in its place would pass every one of those tests: `true` locks
 * a device with no fingerprint and no screen lock out of its own notes
 * permanently, and `false` means the lock screen never appears while its own test
 * suite stays green.
 *
 * So this asserts the join rather than the logic: the value the screen acts on has
 * to agree with what the platform answers here.
 */
@RunWith(AndroidJUnit4::class)
class DeviceAuthTest {

    @Test
    fun theLockFollowsTheDevicesOwnAnswer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val canAuthenticate = DeviceAuth.canAuthenticate(context)
        println("DEVICE-AUTH: canAuthenticate=$canAuthenticate")

        val locked = AppLock.isLocked(
            enabled = true,
            leftAt = null,
            authenticatedAt = null,
            now = 0L,
            canAuthenticate = canAuthenticate,
        )

        assertEquals(
            "With the lock switched on, a launch locks exactly when this device has something to ask for",
            canAuthenticate,
            locked,
        )
    }
}
