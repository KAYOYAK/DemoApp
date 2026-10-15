package com.example.demoapp

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.runtime.getValue

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.demoapp.ui.chat.ChatScreen
import com.example.demoapp.ui.about.AboutViewModel
import com.example.demoapp.ui.about.AboutScreen
import com.example.demoapp.ui.chat.ChatViewModel
import com.example.demoapp.ui.chat.newChat
import com.example.demoapp.ui.chat.openConversation
import com.example.demoapp.ui.chat.refresh
import com.example.demoapp.ui.chat.reportSpeechUnavailable
import com.example.demoapp.ui.chat.setSpeaking
import com.example.demoapp.ui.history.HistoryScreen
import com.example.demoapp.ui.settings.SettingsViewModel
import com.example.demoapp.ui.settings.SettingsScreen
import com.example.demoapp.ui.history.HistoryViewModel
import com.example.demoapp.ui.models.ModelManagerScreen
import com.example.demoapp.ui.models.ModelManagerViewModel

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Chat)

  // The engine and the download coordinator belong to the Application, not to
  // this graph: a loaded model occupies hundreds of megabytes to several
  // gigabytes, and reaching either must not depend on a screen staying composed.
  val container = (LocalContext.current.applicationContext as DemoApplication).container

  NavDisplay(
    backStack = backStack,
    onBack = {
      // Bounded, for the same reason as the history screen's open callback: the
      // library refuses an empty stack, and at one entry back means leaving the
      // app, which the library's own handler already does by finishing the
      // activity.
      if (backStack.size > 1) backStack.removeLastOrNull()
    },
    entryProvider =
      entryProvider {
        entry<Chat> {
          val context = LocalContext.current
          val shareMessageSubject = stringResource(R.string.chat_share_subject)
          // Text shared in from another app. Read here rather than in the activity,
          // because it arrives while this screen may not exist yet and the value
          // has to survive the trip through the graph.
          val sharedText by container.sharedText.collectAsStateWithLifecycle()
          val newChatRequested by container.newChatRequested.collectAsStateWithLifecycle()
          val viewModel =
            viewModel {
              ChatViewModel(
                chatStore = container.chatStore,
                engine = container.llmEngine,
                modelStore = container.modelStore,
                settingsStore = container.settingsStore,
                registry = container.modelRegistry,
              )
            }

          // A model can be downloaded or deleted while the model list is open.
          // Re-reading on the way in and on the way back is what makes that
          // visible here, rather than requiring a restart.
          DisposableEffect(Unit) {
            viewModel.refresh()
            onDispose { viewModel.refresh() }
          }

          // Kept in step with whatever this screen has open, so the history list
          // can mark it. Sent from here rather than written by the ViewModel,
          // which has no reason to know the container exists.
          LaunchedEffect(Unit) {
            viewModel.state.collect { container.openConversationId.value = it.conversationId }
          }

          // The history screen cannot call into this ViewModel: it is a separate
          // destination with its own lifetime, and it may not even exist by the
          // time the choice is made. It publishes the chosen id instead, and this
          // screen picks it up.
          LaunchedEffect(Unit) {
            container.chosenConversation.collect { id ->
              if (id != null) {
                viewModel.openConversation(id)
                container.chosenConversation.value = null
              }
            }
          }

          // The shortcut asked for an empty conversation. Done here, after the
          // screen exists, because the request arrives before there is anything to
          // apply it to.
          LaunchedEffect(newChatRequested) {
            if (newChatRequested) {
              viewModel.newChat()
              container.newChatRequested.value = false
            }
          }

          ChatScreen(
            viewModel = viewModel,
            onOpenModels = { if (backStack.lastOrNull() != Models) backStack.add(Models) },
            onOpenHistory = { if (backStack.lastOrNull() != History) backStack.add(History) },
            onOpenSettings = { if (backStack.lastOrNull() != Settings) backStack.add(Settings) },
            // Read from the container rather than passed down from the activity:
            // the text arrives while this screen may not exist yet, and a value
            // held by the activity would be lost on the way through the graph.
            sharedText = sharedText,
            onSharedTextTaken = { container.sharedText.value = null },
            // Reading a reply out.
            //
            // Driven from here rather than from the view model because the speech
            // engine needs a Context and binds a service; the view model is told
            // which reply is talking, so the screen can mark it and the answer
            // survives a scroll.
            //
            // Pressing it on the reply already being read stops, rather than
            // starting over. A press that restarts is one a user cannot use to stop.
            // One reply out, rather than the conversation it is in.
            //
            // Assembled here because this is the layer with a Context: the screen is
            // a composable and a preview should not be able to open an activity.
            // Nothing is written and nothing is sent -- the text goes into a chooser
            // the user then has to pick from, which is the only way an app that keeps
            // everything private can hand anything over.
            onShareMessage = { text ->
              val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, shareMessageSubject)
                putExtra(android.content.Intent.EXTRA_TEXT, text)
              }
              context.startActivity(
                android.content.Intent.createChooser(share, shareMessageSubject)
                  .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
              )
            },
            onSpeak = { id, text ->
              val speech = container.speech
              if (viewModel.state.value.speakingId == id) {
                speech.stop()
                viewModel.setSpeaking(null)
              } else {
                speech.start { available ->
                  if (!available) {
                    viewModel.reportSpeechUnavailable()
                  } else {
                    viewModel.setSpeaking(id)
                    speech.speak(text) { viewModel.setSpeaking(null) }
                  }
                }
              }
            },
            modifier = Modifier.safeDrawingPadding(),
          )
        }

        entry<Settings> {
          val settingsViewModel: SettingsViewModel =
            viewModel {
              SettingsViewModel(
                settingsStore = container.settingsStore,
                engine = container.llmEngine,
              )
            }

          SettingsScreen(
            viewModel = settingsViewModel,
            modifier = Modifier.safeDrawingPadding(),
          )
        }

        entry<History> {
          val history: HistoryViewModel =
            viewModel {
              HistoryViewModel(
                chatStore = container.chatStore,
                openConversation = container.openConversationId,
              )
            }

          DisposableEffect(Unit) {
            onDispose { history.reload() }
          }

          HistoryScreen(
            viewModel = history,
            onOpen = { id ->
              container.chosenConversation.value = id
              // Back to the chat rather than a second chat screen on the stack:
              // the open conversation is a property of that one screen.
              //
              // Bounded, because this callback can run while the chat screen is
              // the only entry on the stack -- a tap delivered twice, a stray one
              // below the list, or a restored stack. Popping the last entry
              // throws "NavDisplay backstack cannot be empty" and takes the
              // process down, which is a crash where doing nothing was correct.
              if (backStack.size > 1) backStack.removeLastOrNull()
            },
            // Both notices are dismissed straight on the view model. They are
            // screen-local state rather than navigation or sharing, so there is
            // nothing for this layer to do but hand the call over -- and leaving
            // them to the defaults would give a Dismiss button that does nothing,
            // which is worse than not offering one.
            onDismissRestored = history::dismissRestored,
            onDismissUnreadable = history::dismissUnreadable,
            modifier = Modifier.safeDrawingPadding(),
          )
        }

        entry<Models> {
          val manager: ModelManagerViewModel =
            viewModel {
              ModelManagerViewModel(
                store = container.modelStore,
                coordinator = container.downloadCoordinator,
                settingsStore = container.settingsStore,
                engine = container.llmEngine,
                registry = container.modelRegistry,
                openPicked = container.openPickedFile,
                importer = container.modelImporter,
              )
            }

          // Re-read the device on the way out, so coming back -- including after
          // the app has been in the background -- shows what is actually there.
          // The screen only watches the download coordinator, which knows nothing
          // about a file that changed for any other reason.
          DisposableEffect(Unit) {
            onDispose { manager.refresh() }
          }

          ModelManagerScreen(
            viewModel = manager,
            onOpenAbout = { if (backStack.lastOrNull() != About) backStack.add(About) },
            modifier = Modifier.safeDrawingPadding().padding(16.dp),
          )
        }

        entry<About> {
          // The app's own directory, for the parts no store owns: the crash log and
          // the runtime's scratch space. Erasing has to reach them, and this is the
          // only layer with a Context.
          val appFilesDir = LocalContext.current.filesDir
          val about: AboutViewModel =
            viewModel {
              AboutViewModel(
                chatStore = container.chatStore,
                modelStore = container.modelStore,
                coordinator = container.downloadCoordinator,
                settingsStore = container.settingsStore,
                registry = container.modelRegistry,
                engine = container.llmEngine,
                appFilesDir = appFilesDir,
              )
            }

          // Re-read on the way out, so coming back after a download or a chat
          // shows what is actually held rather than what was held last time.
          DisposableEffect(Unit) {
            onDispose { about.refresh() }
          }

          AboutScreen(
            viewModel = about,
            modifier = Modifier.safeDrawingPadding(),
          )
        }
      },
  )
}