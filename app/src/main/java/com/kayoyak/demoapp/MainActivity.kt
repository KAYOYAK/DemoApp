package com.kayoyak.demoapp

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kayoyak.demoapp.diagnostics.AppLock
import com.kayoyak.demoapp.ui.lock.DeviceAuth
import com.kayoyak.demoapp.ui.lock.LockScreen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kayoyak.demoapp.theme.DemoAppTheme

class MainActivity : ComponentActivity() {

  /**
   * When this activity last went to the background, or null while it is in front.
   *
   * Read inside `setContent`, so it has to be a Compose state object rather than a
   * plain field: writing a plain field in `onStop` would not schedule the
   * recomposition that puts the lock on screen.
   *
   * Held on the activity rather than in the composable because onStop fires outside
   * composition -- the composable's `remember` is the wrong lifetime for a value
   * that has to be caught at the moment the app leaves.
   *
   * A configuration change keeps the activity instance, so this survives one, which
   * is right: turning the phone is not leaving it. A process death does not, and
   * neither does `unlockedAt`, so the launch case locks anyway.
   */
  private var leftAt by mutableStateOf<Long?>(null)

  /**
   * The clock the lock decision reads, and the reason the decision happens on
   * resume rather than whenever the screen next wants to redraw.
   *
   * `onStart` runs outside composition, so writing a value the composition does not
   * observe would let the app come back from an hour away without anything being
   * recomputed -- and the timeout would be enforced at some later redraw, or not at
   * all. Writing *this* schedules the recomposition that evaluates `AppLock` with
   * the time the user actually returned.
   */
  private var now by mutableLongStateOf(SystemClock.elapsedRealtime())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    val container = (application as DemoApplication).container
    val settings = container.settingsStore.settings

    // Which of the entry points this launch is, if any.
    acceptNewChat(intent, container)
    acceptSharedText(intent, container)

    setContent {
      // Read here rather than inside the navigation graph, because these are
      // about the whole window. A theme applied below the status bar and not to
      // it would look like a bug on every device.
      val current by settings.collectAsStateWithLifecycle()

      DemoAppTheme(theme = current.chosenTheme, fontScale = current.fontScale) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          // The lock, over everything.
          //
          // Above the navigation graph rather than inside a route: a route can be
          // navigated past, and a lock that a deep link or a back gesture can walk
          // around is a lock in name only.
          //
          // Held here rather than in a view model because it is about the window,
          // not about any screen -- the same reason the theme is.
          val context = LocalContext.current
          val canAuthenticate = remember { DeviceAuth.canAuthenticate(context) }
          var unlockedAt by remember { mutableStateOf<Long?>(null) }

          if (AppLock.isLocked(
                  enabled = current.lockEnabled,
                  leftAt = leftAt,
                  authenticatedAt = unlockedAt,
                  // Elapsed since boot, not the wall clock. This is the one clock in
                  // the app where the difference is a security property: the
                  // timeout is an interval, and `currentTimeMillis` can be moved by
                  // the user, by a network time sync or by a timezone-adjacent
                  // correction. Moved backwards past the timeout, `now - leftAt`
                  // goes negative, the comparison is false, and the lock never
                  // engages again for the rest of the session.
                  //
                  // Read from the activity's state rather than sampled here, so the
                  // value is one `onStart` wrote and this condition depends on it --
                  // which is what makes a resume re-evaluate the lock. Sampling the
                  // clock inside a composition would make the answer depend on how
                  // often the screen happened to redraw.
                  //
                  // A reboot resets this clock, which would make the arithmetic
                  // nonsense -- except that a reboot ends the session, and
                  // `unlockedAt` starts null, which locks. The failure mode and the
                  // reset coincide.
                  now = now,
                  canAuthenticate = canAuthenticate,
              )
          ) {
            LockScreen(
                onUnlocked = {
                  unlockedAt = SystemClock.elapsedRealtime()
                  // Cleared with the unlock, so a return from the background
                  // starts the fifteen minutes again rather than counting from a
                  // departure the user has already answered for. Without this the
                  // prompt appears every time the app is resumed once the timeout
                  // has passed once.
                  leftAt = null
                },
            )
          } else {
            MainNavigation()
          }
        }
      }
    }
  }

  /**
   * The app went away. Start the clock the timeout is measured from.
   *
   * Only when the lock is on: with it off, nothing reads this, and a user who turns
   * the lock on later should not be greeted by an elapsed figure from before they
   * asked for one.
   */
  override fun onStop() {
    super.onStop()
    if ((application as DemoApplication).container.settingsStore.settings.value.lockEnabled) {
      leftAt = SystemClock.elapsedRealtime()
    }
  }

  /**
   * The app is in front again.
   *
   * `now` is rewritten here, which is what makes the timeout take effect on resume:
   * a composable condition is only re-evaluated when something it read has changed,
   * and this is the value `AppLock` compares against. `leftAt` is deliberately left
   * alone -- it is the other half of that comparison, and clearing it would make the
   * answer always "not away long enough".
   */
  override fun onStart() {
    super.onStart()
    now = SystemClock.elapsedRealtime()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    // The task may already exist. Sharing a second paragraph while this app is in
    // the background delivers an intent to the running activity rather than
    // creating a new one, and reading only in onCreate would drop it silently.
    val container = (application as DemoApplication).container
    acceptNewChat(intent, container)
    acceptSharedText(intent, container)
  }

  /**
   * Puts shared text where the composer will pick it up.
   *
   * `EXTRA_TEXT` only, and only for a share that declares itself text. An app may
   * put anything in an intent and this one is entered from outside, so reading a
   * field the sender did not promise would mean acting on whatever happened to be
   * there.
   */
  /**
   * The long-press shortcut, which asks for an empty conversation.
   *
   * A bare action string this app defines for itself. Nothing else can send it, so
   * there is no input to validate -- unlike a share, which arrives from outside.
   */
  private fun acceptNewChat(intent: Intent?, container: AppContainer) {
    if (intent?.action != ACTION_NEW_CHAT) return
    container.newChatRequested.value = true
  }

  private fun acceptSharedText(intent: Intent?, container: AppContainer) {
    if (intent?.action != Intent.ACTION_SEND) return
    if (intent.type?.startsWith("text/") != true) return
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
    if (text.isNullOrEmpty()) return
    container.sharedText.value = text
  }

  companion object {
    /**
     * Defined here and nowhere else.
     *
     * A private action string rather than a system one, because this request comes
     * from the app's own shortcut and nothing else should be able to send it. A
     * public action would let any app on the device clear the conversation.
     */
    const val ACTION_NEW_CHAT = "com.kayoyak.demoapp.NEW_CHAT"
  }
}
