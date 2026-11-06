package me.karansuthar.clauderemote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import me.karansuthar.clauderemote.data.EffortOption
import me.karansuthar.clauderemote.data.ModelOption
import me.karansuthar.clauderemote.data.normalizeServerUrl
import me.karansuthar.clauderemote.ui.AppTheme
import me.karansuthar.clauderemote.ui.InputBar
import me.karansuthar.clauderemote.ui.MessageList
import java.text.DateFormat
import java.util.Date

private enum class Screen { Chat, History, Settings }

class MainActivity : ComponentActivity() {
    private val app by lazy { application as App }
    private val chat by lazy { ChatController(app) }
    private var screen by mutableStateOf(Screen.Chat)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (chat.state.value.conversationId == null) chat.newConversation()
        // First run: nothing works until the bridge address is set.
        if (app.settings.serverUrl.isEmpty()) screen = Screen.Settings
        setContent { AppTheme { Root() } }
    }

    override fun onResume() {
        super.onResume()
        app.server()?.warm()
    }

    override fun onDestroy() {
        super.onDestroy()
        chat.close()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        BackHandler(enabled = screen != Screen.Chat) { screen = Screen.Chat }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(when (screen) { Screen.Chat -> "Claude Code"; Screen.History -> "Chats"; Screen.Settings -> "Settings" }) },
                    navigationIcon = {
                        if (screen != Screen.Chat) IconButton(onClick = { screen = Screen.Chat }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        if (screen == Screen.Chat) {
                            IconButton(onClick = chat::newConversation) { Icon(Icons.Default.Add, "New chat") }
                            IconButton(onClick = { screen = Screen.History }) { Icon(Icons.Default.History, "Chats") }
                            IconButton(onClick = { screen = Screen.Settings }) { Icon(Icons.Default.Settings, "Settings") }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
                when (screen) {
                    Screen.Chat -> ChatScreen()
                    Screen.History -> HistoryScreen()
                    Screen.Settings -> SettingsScreen()
                }
            }
        }
    }

    @Composable
    private fun ChatScreen() {
        val state by chat.state.collectAsStateWithLifecycle()
        Column(Modifier.fillMaxSize()) {
            MessageList(state.messages, Modifier.weight(1f).fillMaxWidth())
            InputBar(busy = state.busy, onSend = chat::send, onStop = chat::stop)
        }
    }

    @Composable
    private fun HistoryScreen() {
        val items = remember { app.conversations.list() }
        if (items.isEmpty()) Text("No chats yet.", Modifier.padding(16.dp))
        LazyColumn {
            items(items, key = { it.id }) { item ->
                ListItem(
                    headlineContent = { Text(item.title.ifEmpty { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.updatedAt))) },
                    modifier = Modifier.clickable {
                        chat.open(item.id)
                        screen = Screen.Chat
                    },
                )
                HorizontalDivider()
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
        ) {
            BridgeSetup()

            var model by remember { mutableStateOf(app.settings.model) }
            Text("Model", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            ModelOption.entries.forEach { option ->
                Choice(selected = model == option, title = option.label) {
                    model = option
                    app.settings.model = option
                }
            }

            var effort by remember { mutableStateOf(app.settings.effort) }
            Text("Effort", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Text("Higher means more thinking: better results, slower.", style = MaterialTheme.typography.bodySmall)
            EffortOption.entries.forEach { option ->
                Choice(selected = effort == option, title = option.label) {
                    effort = option
                    app.settings.effort = option
                }
            }
        }
    }

    /** The bridge address, with the setup steps spelled out for a first run. */
    @Composable
    private fun BridgeSetup() {
        var saved by remember { mutableStateOf(app.settings.serverUrl) }
        var input by remember { mutableStateOf(saved) }
        var error by remember { mutableStateOf<String?>(null) }

        Text("Computer", style = MaterialTheme.typography.titleMedium)
        if (saved.isEmpty()) {
            Text(
                """
                To use Claude Code from this phone:
                1. Install Tailscale on this phone and on your computer, signed in to the same account.
                2. On the computer, install Node.js 20+ and Claude Code, and sign in to Claude Code once (run "claude").
                3. On the computer, start the bridge from this project's server folder: "npm install", then "npm start".
                4. Find the computer's Tailscale IP (the 100.x.y.z address in the Tailscale app) and enter it below.
                Full steps are in the project's README.
                """.trimIndent(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Text("Connected to $saved", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; error = null },
            label = { Text("Bridge address") },
            placeholder = { Text("100.x.y.z or 100.x.y.z:8000") },
            singleLine = true,
            isError = error != null,
            supportingText = { Text(error ?: "Your computer's Tailscale IP. Port 8000 is used if you leave it out.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(
            onClick = {
                val url = normalizeServerUrl(input)
                if (url == null) {
                    error = "That doesn't look like an address."
                } else {
                    app.settings.serverUrl = url
                    saved = url
                    input = url
                    app.server()?.warm()
                    screen = Screen.Chat
                }
            },
            enabled = input.isNotBlank(),
        ) { Text("Save") }
    }

    @Composable
    private fun Choice(selected: Boolean, title: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Text(title, Modifier.padding(start = 12.dp))
        }
    }
}
