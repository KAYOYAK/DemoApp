package com.kayoyak.demoapp.ui.chat

import com.kayoyak.demoapp.clockTime
import com.kayoyak.demoapp.tokensPerSecond

import com.kayoyak.demoapp.ui.UiMessage
import com.kayoyak.demoapp.ui.UiMessageText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Warning
import com.kayoyak.demoapp.formatBytes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.kayoyak.demoapp.R
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kayoyak.demoapp.data.chat.ChatMessage
import com.kayoyak.demoapp.data.chat.MessageRole
import com.kayoyak.demoapp.model.ModelCatalogue
import com.kayoyak.demoapp.theme.DemoAppTheme

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenModels: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    // `lint` asks for the modifier to be the first optional parameter, and this is
    // now where that is: everything above it is required, everything below has a
    // default. Where a caller writes it in the argument list is its own choice --
    // every call site here uses named arguments.
    modifier: Modifier = Modifier,
    /**
     * Text another app shared into this one, or null.
     *
     * Put in the composer and then cleared, rather than sent. Sharing a paragraph
     * here is a request to ask about it, and the asking is still the user's --
     * sending on their behalf would spend their battery and their model's
     * attention on a question they had not finished writing.
     */
    sharedText: String? = null,
    onSharedTextTaken: () -> Unit = {},
    /**
     * Hands one reply to whatever the user picks in the share sheet.
     *
     * Passed in rather than done here because sharing needs an Activity and this
     * is a composable, and because a preview should not be able to open one.
     */
    onShareMessage: (String) -> Unit = {},
    /** Reads a reply out, or stops the one being read. */
    onSpeak: (String, String) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatContent(
        state = state,
        sharedText = sharedText,
        onSharedTextTaken = onSharedTextTaken,
        onSearchQueryChanged = viewModel::setSearchQuery,
        onSend = viewModel::send,
        onStop = viewModel::stop,
        onNewChat = viewModel::newChat,
        onOpenModels = onOpenModels,
        onOpenHistory = onOpenHistory,
        onOpenSettings = onOpenSettings,
        onRegenerate = viewModel::regenerate,
        onEditMessage = viewModel::editAndResend,
        onDeleteMessage = viewModel::deleteMessage,
        onDismissError = viewModel::dismissError,
        onSelectModel = viewModel::selectModel,
        onSpeak = onSpeak,
        onSelectVersion = viewModel::selectVersion,
        onConfirmRamWarning = viewModel::confirmRamWarning,
        onDismissRamWarning = viewModel::dismissRamWarning,
        onToggleKeeping = viewModel::toggleKeeping,
        onShareMessage = onShareMessage,
        modifier = modifier,
    )
}

/** Stateless, so every state can be previewed without a model or an engine. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatContent(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onRegenerate: () -> Unit,
    onEditMessage: (String, String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onDismissError: () -> Unit,
    onSelectModel: (com.kayoyak.demoapp.model.ModelDescriptor) -> Unit,
    modifier: Modifier = Modifier,
    /** Shares one reply, rather than the conversation it is in. */
    onShareMessage: (String) -> Unit = {},
    /** Reads a reply out, or stops the one being read. */
    onSpeak: (String, String) -> Unit = { _, _ -> },
    onSelectVersion: (String, Int) -> Unit = { _, _ -> },
    onConfirmRamWarning: () -> Unit = {},
    onDismissRamWarning: () -> Unit = {},
    onToggleKeeping: (String) -> Unit = {},
    onSearchQueryChanged: (String) -> Unit = {},
    sharedText: String? = null,
    onSharedTextTaken: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Whether the in-conversation search is showing. Screen state, not the
    // ViewModel's: it says whether a field is on screen, not what the app knows.
    var searchOpen by remember { mutableStateOf(false) }

    // Asked before a load this device may not have room for.
    //
    // A question rather than a refusal. The figure is an estimate and the device's
    // free memory is not read at all, so someone who has just closed everything
    // else knows more about this moment than the app does. The platform's own
    // Android reference client asks here too, and the alternative -- letting the
    // process be killed with nothing on screen to explain it -- is the outcome
    // worth avoiding.
    state.ramWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = onDismissRamWarning,
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.models_ram_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.models_ram_body,
                        formatBytes(warning.requiredBytes),
                        formatBytes(warning.budgetBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmRamWarning) {
                    Text(stringResource(R.string.models_ram_proceed))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRamWarning) {
                    Text(stringResource(R.string.models_ram_cancel))
                }
            },
        )
    }

    // Keep the screen on while a reply is arriving.
    //
    // A phone on a one-minute display timeout ends the reply on a black screen, and
    // the user comes back to a transcript they did not watch being written. Held
    // only while generating: this is the app asking for the screen, and it has no
    // business asking for it while someone is reading.
    //
    // A window flag, not a permission. `WAKE_LOCK` would need one and would keep the
    // device awake rather than the display, which is more than this needs.
    val view = LocalView.current
    DisposableEffect(state.generating) {
        view.keepScreenOn = state.generating
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { ModelTitle(state, onSelectModel) },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.chat_new))
                    }
                    // History as well as a new chat: conversations are written to
                    // disk as they happen, and leaving them reachable only by
                    // continuing the one in front of you hides work already saved.
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.chat_history))
                    }

                    // An overflow rather than a fourth button. The bar already
                    // had three, and the next thing to add would have pushed the
                    // title off a narrow screen.
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chat_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_models)) },
                                onClick = {
                                    menuOpen = false
                                    onOpenModels()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_settings)) },
                                onClick = {
                                    menuOpen = false
                                    onOpenSettings()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (searchOpen) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = onSearchQueryChanged,
                        singleLine = true,
                        label = { Text(stringResource(R.string.chat_search_hint)) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            searchOpen = false
                            onSearchQueryChanged("")
                        },
                    ) { Text(stringResource(R.string.chat_search_close)) }
                }
                // The count, so a shortened transcript never reads as lost messages.
                Text(
                    when {
                        state.searchFoundNothing -> stringResource(R.string.chat_search_none)
                        state.searching ->
                            stringResource(
                                R.string.chat_search_count,
                                state.visibleMessages.size,
                                state.messages.size,
                            )
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            state.error?.let { ErrorBanner(it, onDismissError) }

            Box(Modifier.weight(1f)) {
                when {
                    // Checked before the empty state: the initial state has no
                    // models in it either, and showing "no model on this device"
                    // before the device has been read is a claim the app has not
                    // earned yet.
                    state.scanning -> LoadingModel(null)
                    // The longer of the two empty states, and the one that was
                    // written for this slot. It says what the app is for as well as
                    // what to do about the missing model, and it is the only place
                    // that explains the app runs the model on the device and reaches
                    // the network only to fetch it.
                    state.needsAModel -> NoModelYet(onOpenModels)
                    state.loadingModel -> LoadingModel(state.modelDisplayName)
                    state.isEmpty -> EmptyChat()
                    else -> Transcript(state, onRegenerate, onEditMessage, onDeleteMessage, onSelectVersion, onToggleKeeping, onShareMessage, onSpeak, state.showTimestamps)
                }
            }

            // How full the conversation is, above the composer.
            //
            // The alternative is what a local chat app does without it: the model
            // quietly stops reading the beginning of a long chat and the user
            // concludes the model got worse. The count comes from the runtime
            // rather than from an estimate over the text, and it is clamped to the
            // window -- a gauge reading past its own maximum is a bug reported
            // against tools that do exactly this.
            // Said out loud, because the alternative is silent: the model gets
            // worse, the user concludes the model is bad, and nothing anywhere
            // says the conversation outgrew the window.
            if (state.droppedTurns > 0) {
                Text(
                    stringResource(R.string.chat_context_trimmed, state.droppedTurns),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            state.contextUsage?.let { (used, total) ->
                val nearlyFull = used * 10 >= total * 8
                Text(
                    if (nearlyFull) {
                        stringResource(R.string.chat_context_full)
                    } else {
                        stringResource(R.string.chat_context, used, total)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (nearlyFull) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            Composer(state, onSend, onStop, sharedText, onSharedTextTaken)
        }
    }
}

@Composable
private fun ModelTitle(
    state: ChatUiState,
    onSelectModel: (com.kayoyak.demoapp.model.ModelDescriptor) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = state.modelDisplayName ?: stringResource(R.string.chat_needs_model_title)

    Box {
        TextButton(onClick = { open = true }, enabled = state.installedModels.size > 1) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
        // Only offered when there is a real choice to make: a menu with one item
        // is a control that cannot do anything.
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.installedModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.displayName) },
                    onClick = {
                        open = false
                        onSelectModel(model)
                    },
                )
            }
        }
    }
}


@Composable
private fun LoadingModel(name: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            // The name when there is one. "Loading a model" is the least useful
            // thing to say to someone who has three of them installed.
            stringResource(
                if (name.isNullOrBlank()) R.string.chat_loading_model
                else R.string.chat_loading_model_named,
                name.orEmpty(),
            ),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        // Reading a multi-gigabyte file takes seconds. Saying so is better than
        // a spinner with no explanation.
        Text(
            stringResource(R.string.chat_loading_model_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyChat() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.chat_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.chat_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The first thing a new user sees, and the only screen that has to answer "why
 * can I not type anything".
 *
 * The empty state used to be this one, with a sentence about asking a question and
 * no mention of a model. A user who has just installed the app has no model, so
 * that sentence was an instruction they could not follow, with nothing on screen
 * saying why -- and the app looked broken rather than unconfigured.
 *
 * It says what the app is for as well as what to do. Nothing else in the app
 * explains that the model runs on the device and the network is only for getting it.
 */
@Composable
private fun NoModelYet(onOpenModels: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.chat_no_model_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.chat_no_model_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenModels) {
            Text(stringResource(R.string.chat_no_model_action))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.chat_no_model_hint),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorBanner(message: UiMessage, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UiMessageText(message = message, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dismiss)) }
    }
}

@Composable
private fun Transcript(
    state: ChatUiState,
    onRegenerate: () -> Unit,
    onEditMessage: (String, String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onSelectVersion: (String, Int) -> Unit,
    onToggleKeeping: (String) -> Unit,
    onShareMessage: (String) -> Unit,
    /** Reads a reply out, or stops the one being read. */
    onSpeak: (String, String) -> Unit,
    /** Whether every message carries the time it was written. */
    showTimestamps: Boolean,
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    // Which question is being edited, and what the user has typed into it. Held
    // here rather than in the ViewModel because it is a dialog's own state: it
    // exists between opening the dialog and confirming it, and nothing outside
    // this screen has an opinion about it.
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.chat_copied)
    /**
     * Turns the resource the bubble reported into the words to show.
     *
     * A bubble reports which copy happened -- the whole reply, or one paragraph --
     * so it does not have to know how either is phrased. Resolving it here rather
     * than with `context.getString` in the callback is not a style preference:
     * `stringResource` records a read of the configuration, so a locale change
     * recomposes this line and the closure below carries the new wording. A
     * `getString` call inside a lambda records nothing, and the first message after
     * a language change would arrive in the old one.
     */
    val onCopiedMessage: (String) -> Unit = { words ->
        scope.launch { snackbar.showSnackbar(words) }
    }

    // Follow the end only while the user is already at the end.
    //
    // The previous version scrolled on every change with no such check, so
    // scrolling up to read an earlier message during a reply pulled the list back
    // to the bottom on the next token: history could not be read while anything
    // was being generated. It also re-ran on every token, restarting the
    // animation each time and leaving the list permanently behind the stream.
    val atEnd by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = listState.layoutInfo.totalItemsCount
            total == 0 || lastVisible >= total - 1
        }
    }

    LaunchedEffect(state.messages.size, state.streaming.length, state.generating) {
        val last = state.messages.size + if (state.streaming.isNotEmpty() || state.generating) 1 else 0
        if (last <= 0) return@LaunchedEffect
        // Snapping while tokens arrive rather than animating: an animation
        // restarted on every token never finishes, which reads as stutter.
        if (state.generating) {
            if (atEnd) listState.scrollToItem(last - 1)
        } else if (atEnd) {
            listState.animateScrollToItem(last - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(state.visibleMessages, key = { _, message -> message.id }) { index, message ->
            MessageBubble(
                message = message,
                // Asking again only makes sense for the reply that is currently
                // last; an older one has been answered over.
                isLastReply = message.role == MessageRole.ASSISTANT && index == state.visibleMessages.lastIndex,
                isLastQuestion = message.role == MessageRole.USER &&
                    index == state.visibleMessages.indexOfLast { it.role == MessageRole.USER },
                enabled = !state.generating,
                onRegenerate = onRegenerate,
                onEditRequested = { editing = it },
                onCopied = onCopiedMessage,
                onSelectVersion = onSelectVersion,
                onToggleKeeping = onToggleKeeping,
                onShareMessage = onShareMessage,
                speakingId = state.speakingId,
                onSpeak = onSpeak,
                showTimestamps = state.showTimestamps,
        onCopyCode = { code ->
                    // The clipboard lives at this level, not in the message bubble:
                    // a renderer that reaches for global platform state is one that
                    // cannot be previewed or tested without a device.
                    clipboard.setText(AnnotatedString(code))
                    scope.launch { snackbar.showSnackbar(copiedMessage) }
                },
                onDelete = { onDeleteMessage(message.id) },
            )
        }
        if (state.generating && state.streaming.isNotEmpty()) {
            item(key = "streaming") { StreamingBubble(state.streaming) }
        } else if (state.generating) {
            item(key = "thinking") { ThinkingBubble(state.modelDisplayName) }
        }
    }

    editing?.let { message ->
        EditQuestionDialog(
            original = message.text,
            onDismiss = { editing = null },
            onConfirm = { text ->
                editing = null
                onEditMessage(message.id, text)
            },
        )
    }

    SnackbarHost(
        hostState = snackbar,
        modifier = Modifier.padding(bottom = 72.dp),
    )
}

/**
 * The dialog for changing a question and asking it again.
 *
 * The original text is the starting point rather than a blank field: this is
 * almost always a correction, and retyping the part that was already right is
 * work the app can do instead.
 */
@Composable
private fun EditQuestionDialog(
    original: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(original) { mutableStateOf(original) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_edit_title)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.chat_edit_hint)) },
                minLines = 2,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draft) },
                enabled = draft.isNotBlank() && draft != original,
            ) { Text(stringResource(R.string.chat_edit_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_edit_cancel)) }
        },
    )
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    /** True for the last reply, which is the only one asking again makes sense for. */
    isLastReply: Boolean,
    /** True for the last question, which is the only one that can be edited. */
    isLastQuestion: Boolean,
    enabled: Boolean,
    onRegenerate: () -> Unit,
    onEditRequested: (ChatMessage) -> Unit,
    onCopied: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    onSelectVersion: (String, Int) -> Unit,
    onToggleKeeping: (String) -> Unit,
    onShareMessage: (String) -> Unit,
    onDelete: () -> Unit,
    /** The reply being read aloud, if any. */
    speakingId: String?,
    onSpeak: (String, String) -> Unit,
    /** Whether every message carries the time it was written. */
    showTimestamps: Boolean,
) {
    val fromUser = message.role == MessageRole.USER
    var menuOpen by remember { mutableStateOf(false) }
    // Whether the in-conversation search is showing. Screen state, not the
    // ViewModel's: it says whether a field is on screen, not what the app knows.
    var searchOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    // Resolved here rather than inside the two click handlers, which are not
    // composable. Both phrases, not just the one this bubble will use, because
    // which copy ran is decided by which handler fired.
    val wholeReplyCopied = stringResource(R.string.chat_copied)
    val paragraphCopied = stringResource(R.string.chat_copied_paragraph)
    // Both copy paths write to the clipboard here and report through the same
    // callback: the message-level copy always did, and the paragraph-level one
    // reached the clipboard without saying so, which is indistinguishable from a
    // long press that missed.
    //

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            colors = if (fromUser) {
                androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            } else {
                androidx.compose.material3.CardDefaults.cardColors()
            },
        ) {
            Column(Modifier.padding(12.dp)) {
                // Selectable, so a reply can be picked out and copied. Not a
                // copy button: what someone wants is usually a sentence or a
                // code block, not the whole message, and the platform's own
                // selection UI already does that well.
                // The reasoning, above the answer and closed by default.
                //
                // Closed because it is not what was asked for, and a small model's
                // working can be several times the length of its answer. Kept
                // reachable because when the answer is wrong it is the only thing
                // that says whether the model misunderstood or merely misfired --
                // and those call for different next moves.
                message.reasoning?.let { trace ->
                    var reasoningOpen by remember(message.id) { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        TextButton(
                            onClick = { reasoningOpen = !reasoningOpen },
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (reasoningOpen) R.string.chat_reasoned_hide
                                    else R.string.chat_reasoned,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (reasoningOpen) {
                            Text(
                                trace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                SelectionContainer {
                    MarkdownText(
                    message.text,
                    onCopyCode = onCopyCode,
                    onCopyParagraph = { paragraph ->
                        clipboard.setText(AnnotatedString(paragraph))
                        onCopied(paragraphCopied)
                    },
                )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // When it was said, on every message rather than only on the
                    // replies. A transcript with times on one side and none on the
                    // other cannot be read for order, and the question a person
                    // asks of an old conversation is almost always "when was this".
                    Text(
                        clockTime(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    message.elapsedMillis?.let { millis ->
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.chat_seconds, "${millis / 1000.0}"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Two numbers, because they are two complaints.
                    //
                    // A chat that has become slow to *start* is one where the
                    // transcript got long -- the wait grows with what the model has
                    // to read. A chat where the text arrives slowly is the model.
                    // One combined rate hides which of the two the user has, and the
                    // fixes are opposite.
                    message.timeToFirstTokenMillis?.takeIf { it > 0 }?.let { millis ->
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.chat_first_token, "${millis / 1000.0}"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The number that makes two models comparable. Elapsed time says
                    // how long the wait was; the rate says what it bought.
                    //
                    // The unit is not appended here. It belongs to the sentence around
                    // the number and lives with it in `chat_rate`, which is what makes
                    // it translatable -- "tok/s" written in Kotlin was copy no
                    // translation layer could reach, and it was written in three files.
                    (
                        message.decodeTokensPerSecond?.takeIf { it > 0 }?.let {
                            String.format(java.util.Locale.US, "%.1f", it)
                        } ?: tokensPerSecond(message.tokenCount, message.elapsedMillis)
                    )?.let { rate ->
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.chat_rate, rate),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Version history, when there is any.
                    //
                    // Regenerating used to delete the previous answer outright.
                    // Offering them side by side is what makes asking again a safe
                    // thing to do -- a wrong answer is not always recognisable as
                    // wrong straight away, and the one before it is often the one
                    // that was right.
                    if (message.versions.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        TextButton(
                            onClick = { onSelectVersion(message.id, message.versionIndex - 1) },
                            enabled = message.versionIndex > 0,
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            Text("‹", style = MaterialTheme.typography.labelMedium)
                        }
                        Text(
                            stringResource(
                                R.string.chat_version,
                                message.versionIndex + 1,
                                message.versions.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { onSelectVersion(message.id, message.versionIndex + 1) },
                            enabled = message.versionIndex < message.versions.lastIndex,
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            Text("›", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    // A kept answer says so, so that scrolling back to it is
                    // not the only way to know it was kept.
                    // The time, when the setting asks for it.
                    //
                    // Under the metadata rather than at the end of the text: a time
                    // appended to a paragraph reads as part of the paragraph.
                    if (showTimestamps) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            clockTime(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (message.keptAt != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.chat_kept_marker),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (message.interrupted) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.chat_stopped),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // An explicit control rather than a long press. The text
                    // inside is selectable, and a long press on selectable text
                    // belongs to the platform's selection, not to a menu.
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            enabled = enabled,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.chat_message_actions),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            // Copy the whole message. Selection already exists for
                            // part of it, and this is for the other case -- a reply
                            // worth keeping in one piece, where dragging handles
                            // over a screen of text is work nobody wants.
                            // Keeping this answer, and sharing just this one.
                            //
                            // Sharing one reply rather than the conversation: an
                            // answer worth sending someone is rarely worth sending
                            // them the four questions before it.
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (message.keptAt == null) R.string.chat_keep
                                            else R.string.chat_unkeep,
                                        ),
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onToggleKeeping(message.id)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_share_message)) },
                                onClick = {
                                    menuOpen = false
                                    onShareMessage(message.text)
                                },
                            )
                            // Reading it out. Only replies: the user knows what they
                            // typed, and offering to read their own question back is
                            // an offer nobody takes.
                            if (!fromUser) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (speakingId == message.id) R.string.chat_speak_stop
                                                else R.string.chat_speak,
                                            ),
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onSpeak(message.id, message.text)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_copy)) },
                                onClick = {
                                    menuOpen = false
                                    clipboard.setText(AnnotatedString(message.text))
                                    onCopied(wholeReplyCopied)
                                },
                            )
                            if (isLastReply) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_ask_again)) },
                                    onClick = {
                                        menuOpen = false
                                        onRegenerate()
                                    },
                                )
                            }
                            // Offered for the last question only. Editing an
                            // earlier one would invalidate every answer after it,
                            // and quietly discarding those is a bigger thing than
                            // this menu can honestly offer.
                            if (isLastQuestion) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_edit_resend)) },
                                    onClick = {
                                        menuOpen = false
                                        onEditRequested(message)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (fromUser) R.string.chat_delete_exchange
                                            else R.string.chat_delete_message,
                                        ),
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card(Modifier.widthIn(max = 300.dp)) {
            Text(
                text,
                modifier = Modifier
                    .padding(12.dp)
                    .semantics { contentDescription = text },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ThinkingBubble(modelName: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card {
            Text(
                stringResource(R.string.chat_thinking, modelName ?: ""),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Composer(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    sharedText: String?,
    onSharedTextTaken: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    // Text handed over by another app, put where the user can edit it before it
    // goes anywhere. Keyed on the text itself so a second share replaces the
    // first, and consumed once taken so a recomposition does not paste it again
    // over something the user has since typed.
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            draft = sharedText
            onSharedTextTaken()
        }
    }

    // Read here rather than inside the semantics block: `Modifier.semantics` takes
    // a plain lambda, not a composable one, so a string resource cannot be looked
    // up in there.
    val sendLabel = stringResource(
        if (state.generating) R.string.chat_stop else R.string.chat_send,
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            enabled = state.canSend,
            placeholder = {
                Text(stringResource(R.string.chat_message_hint))
            },
            maxLines = 4,
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = {
                if (state.generating) {
                    onStop()
                } else {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        onSend(text)
                        draft = ""
                    }
                }
            },
            enabled = state.generating || (state.canSend && draft.isNotBlank()),
            modifier = Modifier.semantics {
                contentDescription = sendLabel
            },
        ) {
            Icon(
                if (state.generating) Icons.Filled.Close else Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
            )
        }
    }
}

// ------------------------------------------------------------- previews ---

private fun sampleMessages() = listOf(
    ChatMessage("1", MessageRole.USER, "说一个颜色，一个词就行。", 1L),
    ChatMessage("2", MessageRole.ASSISTANT, "Red", 2L, modelId = "smollm2-360m", elapsedMillis = 2366),
    ChatMessage("3", MessageRole.USER, "再解释一下天空为什么是蓝的。", 3L),
)

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PreviewConversation() {
    DemoAppTheme {
        ChatContent(
            state = ChatUiState(
                messages = sampleMessages(),
                modelId = "smollm2-360m",
                modelDisplayName = "SmolLM2 360M Instruct",
                installedModels = ModelCatalogue.models.take(1),
            ),
            onSend = {}, onStop = {}, onNewChat = {}, onOpenModels = {}, onOpenHistory = {}, onOpenSettings = {}, onRegenerate = {}, onEditMessage = { _, _ -> }, onDeleteMessage = {},
            onDismissError = {}, onSelectModel = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PreviewNoModel() {
    DemoAppTheme {
        ChatContent(
            state = ChatUiState(),
            onSend = {}, onStop = {}, onNewChat = {}, onOpenModels = {}, onOpenHistory = {}, onOpenSettings = {}, onRegenerate = {}, onEditMessage = { _, _ -> }, onDeleteMessage = {},
            onDismissError = {}, onSelectModel = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PreviewStreaming() {
    DemoAppTheme {
        ChatContent(
            state = ChatUiState(
                messages = sampleMessages().dropLast(1),
                streaming = "因为波长较短的",
                generating = true,
                modelId = "smollm2-360m",
                modelDisplayName = "SmolLM2 360M Instruct",
                installedModels = ModelCatalogue.models.take(1),
            ),
            onSend = {}, onStop = {}, onNewChat = {}, onOpenModels = {}, onOpenHistory = {}, onOpenSettings = {}, onRegenerate = {}, onEditMessage = { _, _ -> }, onDeleteMessage = {},
            onDismissError = {}, onSelectModel = {},
        )
    }
}
