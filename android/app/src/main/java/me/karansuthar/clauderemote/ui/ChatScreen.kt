package me.karansuthar.clauderemote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.karansuthar.clauderemote.App
import me.karansuthar.clauderemote.data.ChatSession
import me.karansuthar.clauderemote.data.Device
import me.karansuthar.clauderemote.data.ModelInfo
import kotlinx.coroutines.launch

/**
 * One open session's chat. The session's state exists only while this is shown: it is
 * attached on entry (a fresh snapshot from the bridge) and detached and dropped on exit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(app: App, device: Device, key: String, onBack: () -> Unit) {
    val client = remember(device) { app.client(device) }
    val session = remember(client, key) { ChatSession(client, key) }
    DisposableEffect(session) {
        session.attach()
        onDispose { session.detach() }
    }
    // Back from the background: reconnect now (which re-attaches for a fresh snapshot).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { client.wake() }

    val state by session.state.collectAsStateWithLifecycle()
    val connected by client.connected.collectAsStateWithLifecycle()
    val alwaysExpandSteps by app.settings.alwaysExpandStepsFlow.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // The bridge's models, fetched once per screen (retried on reconnect until it works).
    var models by remember(session) { mutableStateOf<List<ModelInfo>?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    var tasksOpen by remember { mutableStateOf(false) }
    // Ctrl+B: every running command and subagent, or just the step [id] started.
    fun moveToBackground(id: String?) {
        scope.launch {
            val moved = runCatching { session.background(id) }
            snackbar.showSnackbar(
                when {
                    moved.isFailure -> moved.exceptionOrNull()?.message ?: "Couldn't move it to the background"
                    moved.getOrDefault(false) -> "Moved to the background. Claude carries on and is told when it finishes."
                    else -> "Nothing is running that can go to the background."
                },
            )
        }
    }
    // A message from the phone into a session still open in a terminal waits for a confirmation.
    var terminalSend by remember { mutableStateOf<(() -> Unit)?>(null) }
    var terminalCancel by remember { mutableStateOf<(() -> Unit)?>(null) }
    var changing by remember { mutableStateOf(false) }
    var defaultsChecked by remember(session) { mutableStateOf(false) }

    fun loadModels(report: Boolean) {
        scope.launch {
            try {
                models = session.models()
            } catch (e: Exception) {
                if (report) snackbar.showSnackbar(e.message ?: "Couldn't load the models")
            }
        }
    }

    fun changeModel(model: String?, effort: String?) {
        if (changing) return
        changing = true
        scope.launch {
            try {
                session.setModel(model, effort)
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Couldn't change the model")
            } finally {
                changing = false
            }
        }
    }

    LaunchedEffect(session, connected) {
        if (models == null && connected) loadModels(report = false)
    }

    // A brand-new session starts with the user's default model and effort, once.
    LaunchedEffect(state.loading, state.error) {
        if (defaultsChecked || state.loading || state.error != null) return@LaunchedEffect
        defaultsChecked = true
        if (state.closed || state.items.isNotEmpty() || state.sessionId != null) return@LaunchedEffect
        val model = app.settings.defaultModel
        val effort = app.settings.defaultEffort
        if (model == null && effort == null) return@LaunchedEffect
        if (model == state.model && effort == state.effort) return@LaunchedEffect
        changeModel(model, effort)
    }

    if (sheetOpen) {
        ModelSheet(
            models = models,
            current = state.model,
            onPick = { picked ->
                sheetOpen = false
                if (picked.value != state.model) changeModel(picked.value, null)
            },
            onSaveDefault = {
                app.settings.defaultModel = state.model
                app.settings.defaultEffort = state.effort
                scope.launch { snackbar.showSnackbar("Saved for new sessions") }
            },
            onDismiss = { sheetOpen = false },
        )
    }

    // Read once here: the app bar lambdas then capture plain values and skip recomposition while
    // a reply streams in (only items change then).
    val title = state.title
    val cwd = state.cwd
    val closed = state.closed

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    Column {
                        Text(
                            title.ifBlank { "New session" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (cwd.isNotEmpty()) {
                            Text(
                                cwd,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Always expand outputs") },
                                trailingIcon = {
                                    if (alwaysExpandSteps) Icon(Icons.Default.Check, contentDescription = "On")
                                },
                                onClick = {
                                    menuOpen = false
                                    app.settings.alwaysExpandSteps = !alwaysExpandSteps
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Move to background") },
                                enabled = state.running && !closed,
                                onClick = {
                                    menuOpen = false
                                    moveToBackground(null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Close session") },
                                enabled = !closed,
                                onClick = {
                                    menuOpen = false
                                    session.close()
                                    onBack()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (!state.loading || state.model != null) {
                SessionBar(
                    model = state.model,
                    effort = state.effort,
                    closed = state.closed,
                    costUsd = state.costUsd,
                    contextTokens = state.contextTokens,
                    background = state.background,
                    terminal = state.isTerminal,
                    models = models,
                    busy = changing,
                    onModelClick = {
                        sheetOpen = true
                        if (models == null) loadModels(report = true)
                    },
                    onEffort = { changeModel(null, it) },
                    onTasks = { tasksOpen = true },
                )
            }
            // A terminal session mirrored live; the bridge switches origin to "bridge" (a meta
            // event) once a message is sent from here, which hides this.
            if (state.isTerminal && !state.closed) TerminalBanner()
            when {
                state.closed -> Banner("This session has been closed.")
                // Attached before but the socket is down: the client is reconnecting by itself.
                !state.loading && !connected && state.error == null -> Banner("Reconnecting…")
            }
            state.error?.let { error ->
                Banner(error, action = "Retry") {
                    session.clearError()
                    session.attach()
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.loading && state.items.isEmpty()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    androidx.compose.runtime.CompositionLocalProvider(LocalImageFetcher provides session::fetchImage) {
                        MessageList(
                            state.items,
                            running = state.running && !state.closed,
                            modifier = Modifier.fillMaxSize(),
                            alwaysExpandSteps = alwaysExpandSteps,
                            start = state.start,
                            onLoadEarlier = session::loadEarlier,
                            turnStartedAt = state.turnStartedAt,
                            turnTokens = state.turnTokens,
                            background = state.background,
                            onBackground = if (state.isTerminal) null else { id -> moveToBackground(id) },
                            onTasks = { tasksOpen = true },
                        )
                    }
                }
            }
            InputBar(
                running = state.running,
                onSend = { text, images, onResult ->
                    if (state.isTerminal && !state.closed) {
                        terminalSend = { session.send(text, images, onResult) }
                        terminalCancel = { onResult(false) }
                    } else {
                        session.send(text, images, onResult)
                    }
                },
                onStop = session::interrupt,
                onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                enabled = !state.closed,
            )
        }
    }

    if (tasksOpen) {
        TasksSheet(
            tasks = state.tasks,
            session = session,
            onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
            onDismiss = { tasksOpen = false },
        )
    }

    terminalSend?.let { send ->
        TerminalSendDialog(
            onSend = {
                terminalSend = null
                terminalCancel = null
                send()
            },
            onCancel = {
                terminalCancel?.invoke()
                terminalSend = null
                terminalCancel = null
            },
        )
    }
}

@Composable
private fun TerminalSendDialog(onSend: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Default.Terminal, null) },
        title = { Text("Close the terminal first") },
        text = {
            Text(
                "This session is open in a terminal on the computer. Exit Claude Code there before " +
                    "sending from the phone: if both keep going, they write to the same session and " +
                    "the conversation gets mixed up.",
            )
        },
        confirmButton = { Button(onClick = onSend) { Text("It's closed, send") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Shown while the session is a terminal session on the computer, mirrored live. */
@Composable
private fun TerminalBanner() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "Open in a terminal on this computer, shown live. Exit Claude Code in the terminal before sending from the phone; don't use both at once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
        }
    }
}

/** A one-line notice above the chat, with an optional action. */
@Composable
private fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}
