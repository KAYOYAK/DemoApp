package com.example.demoapp.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.demoapp.R

/**
 * What the device can actually check.
 *
 * Asked before offering the setting, and asked again before showing the prompt.
 * A device with neither an enrolled fingerprint nor a screen lock cannot
 * authenticate anyone, and turning the lock on there would refuse to open the app
 * with no way to get in and turn it off.
 */
object DeviceAuth {

    /**
     * What to allow.
     *
     * Biometrics **or** the screen lock. Android's own guidance for sensitive
     * applications is to keep the screen lock out of it, because a PIN can be
     * watched over a shoulder -- and that guidance is about a banking app, where
     * the thing being defended is money.
     *
     * Here it is a set of private notes, and the trade runs the other way: a
     * fingerprint-only lock shuts out everyone who has not enrolled one, and
     * refusing to open someone's own offline notes is a worse outcome than the one
     * being defended against. The setting says which is used.
     */
    private const val ALLOWED =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Whether this device has anything that can stand in for a password. */
    fun canAuthenticate(context: android.content.Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) ==
            BiometricManager.BIOMETRIC_SUCCESS
}

/**
 * Shown instead of the app while it is locked.
 *
 * It asks as soon as it appears rather than waiting for a tap. The user has
 * already done the thing that means "open the app" -- tapping the icon, coming
 * back to it -- and a second tap that only means "yes, I meant that" is a step
 * that exists for the app's benefit rather than theirs.
 *
 * There is a button as well, because the first prompt is dismissed by a back
 * gesture, by a mistake, or by a finger that was not read, and there has to be a
 * way to ask again.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val failure = stringResource(R.string.lock_failed)

    // Resolved during composition, because `PromptInfo` is built by an ordinary
    // function and a `stringResource` call cannot go inside it. Resolving them here
    // rather than with `context.getString` in `prompt` is what makes them follow a
    // language change: `stringResource` records a read of the configuration, so a
    // change recomposes this composable and redefines `prompt` -- a local function
    // captures its surroundings again on each composition -- while a `getString`
    // call records nothing and would keep showing the old wording.
    val title = stringResource(R.string.lock_title)
    val body = stringResource(R.string.lock_body)

    fun prompt() {
        val activity = context as? FragmentActivity ?: return
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(body)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()

        // Constructed per attempt rather than held: a prompt that outlives the
        // activity it was built from is a leak, and this screen is short-lived.
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    onUnlocked()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    // Deliberately nothing. Every error here -- cancelled, no
                    // hardware, locked out -- leaves the screen as it was, and the
                    // button is how the user asks again. A dialog on top of a lock
                    // screen is one more thing between them and their notes.
                }

                override fun onAuthenticationFailed() {
                    // One unread finger. The prompt stays up and tries again; there
                    // is nothing to tell the user that the prompt is not already
                    // telling them.
                    if (failure.isNotEmpty()) Unit
                }
            },
        ).authenticate(info)
    }

    LaunchedEffect(Unit) { prompt() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.lock_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = { prompt() }, modifier = Modifier.padding(top = 24.dp)) {
                Text(stringResource(R.string.lock_action))
            }
        }
    }
}
