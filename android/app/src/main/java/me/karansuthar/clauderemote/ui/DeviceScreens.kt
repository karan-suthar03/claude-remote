package me.karansuthar.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.karansuthar.clauderemote.App
import me.karansuthar.clauderemote.BuildConfig
import me.karansuthar.clauderemote.data.Device
import me.karansuthar.clauderemote.data.FoundDevice
import me.karansuthar.clauderemote.data.normalizeServerUrl
import org.json.JSONObject

/** Closed sessions shown on the device screen (most recent first). */
private const val CLOSED_SHOWN = 50

// ---------------------------------------------------------------- drawer

@Composable
fun DeviceDrawer(
    app: App,
    devices: List<Device>,
    selectedId: String?,
    onSelect: (Device) -> Unit,
    onRemove: (Device) -> Unit,
    onRename: (Device, String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    found: List<FoundDevice> = emptyList(),
    onAddFound: (FoundDevice) -> Unit = {},
) {
    var confirmRemove by remember { mutableStateOf<Device?>(null) }
    var renaming by remember { mutableStateOf<Device?>(null) }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                Modifier.padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark(size = 40.dp, fontSize = 16.sp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Claude Remote", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Claude Code on your computers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(
                Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            SectionLabel(
                "Devices",
                modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
            ) {
                if (devices.isEmpty()) {
                    Text(
                        "No devices yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                devices.forEach { device ->
                    DrawerDeviceItem(
                        app = app,
                        device = device,
                        selected = device.id == selectedId,
                        onClick = { onSelect(device) },
                        onRename = { renaming = device },
                        onRemove = { confirmRemove = device },
                    )
                }
                if (found.isNotEmpty()) {
                    SectionLabel(
                        "Available to add",
                        modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 8.dp),
                    )
                    found.forEach { f ->
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Add, null) },
                            label = { FoundDeviceLabel(f) },
                            selected = false,
                            onClick = { onAddFound(f) },
                            colors = drawerItemColors(),
                            modifier = Modifier.height(64.dp),
                        )
                    }
                }
            }

            HorizontalDivider(
                Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Add, null) },
                    label = { Text("Add device", style = MaterialTheme.typography.labelLarge) },
                    selected = false,
                    onClick = onAdd,
                    colors = drawerItemColors(),
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Settings", style = MaterialTheme.typography.labelLarge) },
                    selected = false,
                    onClick = onSettings,
                    colors = drawerItemColors(),
                )
            }
        }
    }

    confirmRemove?.let { device ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Remove device?") },
            text = { Text("${device.displayName} (${device.address}) will be removed from this phone. Its sessions stay on the computer.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmRemove = null
                        onRemove(device)
                    },
                    colors = destructiveButtonColors(),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }

    renaming?.let { device ->
        RenameDeviceDialog(
            device = device,
            onDismiss = { renaming = null },
            onRename = { name ->
                renaming = null
                onRename(device, name)
            },
        )
    }
}

@Composable
private fun DrawerDeviceItem(
    app: App,
    device: Device,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    val client = remember(device.id, device.url) { app.client(device) }
    val connected by client.connected.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }

    NavigationDrawerItem(
        icon = {
            Box(
                Modifier
                    .size(10.dp)
                    .background(
                        if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
            )
        },
        label = {
            Column {
                Text(
                    device.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (connected) "Connected · ${device.address}" else device.address,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFamily,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        badge = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, "Options for ${device.displayName}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = {
                            menu = false
                            onRemove()
                        },
                    )
                }
            }
        },
        selected = selected,
        onClick = onClick,
        colors = drawerItemColors(),
        modifier = Modifier.height(64.dp),
    )
}

@Composable
private fun FoundDeviceLabel(f: FoundDevice) {
    Column {
        Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            f.subtitle,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MonoFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun drawerItemColors() = NavigationDrawerItemDefaults.colors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedContainerColor = Color.Transparent,
    selectedIconColor = MaterialTheme.colorScheme.primary,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
    unselectedTextColor = MaterialTheme.colorScheme.onSurface,
    selectedBadgeColor = MaterialTheme.colorScheme.onPrimaryContainer,
    unselectedBadgeColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** Filled coral button colors, swapped to the error color for destructive confirmations. */
@Composable
internal fun destructiveButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.error,
    contentColor = MaterialTheme.colorScheme.onError,
)

// ---------------------------------------------------------------- dialogs

@Composable
fun AddDeviceDialog(
    onDismiss: () -> Unit,
    onAdd: (url: String, name: String) -> Unit,
    found: List<FoundDevice> = emptyList(),
) {
    var address by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val url = normalizeServerUrl(address)
        if (url == null) error = "That doesn't look like an address." else onAdd(url, name.trim())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add device") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (found.isNotEmpty()) {
                    SectionLabel("Found on your tailnet", modifier = Modifier.padding(bottom = 4.dp))
                    found.forEach { f ->
                        ListItem(
                            headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(f.subtitle, fontFamily = MonoFamily) },
                            trailingContent = { Icon(Icons.Default.Add, "Add ${f.name}") },
                            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onAdd(f.url, "") },
                        )
                    }
                    SectionLabel("Or enter an address", modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; error = null },
                    label = { Text("Address") },
                    placeholder = { Text("100.x.y.z") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(error ?: "The 100.x.y.z address the installer printed (the computer's Tailscale IP).")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { Button(onClick = ::submit, enabled = address.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDeviceDialog(device: Device, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by rememberSaveable(device.id) { mutableStateOf(device.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Edit, null) },
        title = { Text("Rename device") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text(device.address) },
                singleLine = true,
                supportingText = { Text("Leave empty to use the computer's own name.") },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onRename(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SettingsDialog(app: App, onDismiss: () -> Unit) {
    var defaultModel by remember { mutableStateOf(app.settings.defaultModel) }
    var defaultEffort by remember { mutableStateOf(app.settings.defaultEffort) }
    var alwaysExpand by remember { mutableStateOf(app.settings.alwaysExpandSteps) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Settings, null) },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsSection("New sessions")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Default model: ${defaultModel ?: "Claude Code default"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Effort: ${defaultEffort ?: "default"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            app.settings.defaultModel = null
                            app.settings.defaultEffort = null
                            defaultModel = null
                            defaultEffort = null
                        },
                        enabled = defaultModel != null || defaultEffort != null,
                    ) { Text("Reset") }
                }
                Text(
                    "Model and effort are chosen per session in the chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                SettingsSection("Chat")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Always expand tool outputs", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Show each tool call's details and output without tapping it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = alwaysExpand,
                        onCheckedChange = {
                            alwaysExpand = it
                            app.settings.alwaysExpandSteps = it
                        },
                    )
                }

                SettingsSection("About")
                Text("Claude Remote ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Everything shown comes live from your computers; nothing about your sessions is stored on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SettingsSection(title: String) {
    SectionLabel(title, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

// ---------------------------------------------------------------- setup

@Composable
fun SetupScreen(onAdd: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    // Built once per colour scheme rather than on every recomposition.
    val steps = remember(onSurface, codeBg) {
        val code = SpanStyle(
            fontFamily = MonoFamily,
            fontSize = 14.sp,
            color = onSurface,
            background = codeBg,
        )
        listOf(
            buildAnnotatedString {
                append("Install Tailscale on this phone and your computer, signed in to the same account.")
            },
            buildAnnotatedString {
                append("On the computer (with Node.js 20+), run the one-line installer from ")
                withStyle(code) { append("github.com/karan-suthar03/claude-remote") }
                append(". Or ask Claude Code there to set up Claude Remote.")
            },
            buildAnnotatedString {
                append("The installer prints an address like ")
                withStyle(code) { append("100.x.y.z") }
                append(". Add the computer here with it.")
            },
            buildAnnotatedString {
                append("If Claude Code isn't signed in on the computer, sign in from the device screen.")
            },
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        BrandMark(size = 64.dp, fontSize = 24.sp)
        Spacer(Modifier.height(4.dp))
        Text("Set up Claude Remote", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Use Claude Code on your computer from this phone. Four steps and you're in.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        steps.forEachIndexed { i, step ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp)) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Text(
                        step,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp, top = 3.dp),
                    )
                }
            }
        }
        Button(
            onClick = onAdd,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp),
        ) {
            Icon(Icons.Default.Add, null, Modifier.padding(end = 8.dp))
            Text("Add device", style = MaterialTheme.typography.titleSmall)
        }
    }
}

// ---------------------------------------------------------------- device home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceHome(
    app: App,
    device: Device,
    onMenu: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSession: (key: String) -> Unit,
) {
    val client = remember(device.id, device.url) { app.client(device) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var refreshTick by remember { mutableIntStateOf(0) }
    var loading by remember(device.id) { mutableStateOf(true) }
    var pullRefreshing by remember(device.id) { mutableStateOf(false) }
    var loaded by remember(device.id) { mutableStateOf(false) }
    var error by remember(device.id) { mutableStateOf<String?>(null) }
    // Sessions are fetched live from the computer and replaced wholesale on every load.
    var open by remember(device.id) { mutableStateOf<List<SessionRow>>(emptyList()) }
    var closed by remember(device.id) { mutableStateOf<List<SessionRow>>(emptyList()) }
    var resuming by remember(device.id) { mutableStateOf(false) }
    var confirmClose by remember(device.id) { mutableStateOf<SessionRow?>(null) }
    // Account and usage are fetched live from the computer; nothing is kept on the phone.
    var account by remember(device.id) { mutableStateOf<AccountInfo?>(null) }
    var usage by remember(device.id) { mutableStateOf<UsageInfo?>(null) }
    var authProblem by remember(device.id) { mutableStateOf(false) }
    var showSignIn by rememberSaveable(device.id) { mutableStateOf(false) }
    var confirmSignOut by remember(device.id) { mutableStateOf(false) }
    val needsSignIn = authProblem || account?.loggedIn == false

    suspend fun loadAccount() {
        try {
            val acc = parseAccount(client.request("account", JSONObject()))
            // State writes are equals()-checked, so an unchanged poll result recomposes nothing.
            account = acc
            usage = if (acc.loggedIn) {
                try {
                    parseUsage(client.request("usage", JSONObject()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            } else null
            if (acc.loggedIn) authProblem = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            account = null
            usage = null
        }
    }

    suspend fun loadSessions() {
        val reply = client.request("sessions", JSONObject())
        val newOpen = parseSessions(reply.optJSONArray("open")).sortedByDescending { it.lastActive }
        val newClosed = parseSessions(reply.optJSONArray("closed")).sortedByDescending { it.lastActive }.take(CLOSED_SHOWN)
        // Polled every 5 s and usually unchanged: keep the old list objects so nothing recomposes.
        if (newOpen != open) open = newOpen
        if (newClosed != closed) closed = newClosed
        loaded = true
    }

    // Runs on first show, on refresh/retry, and again whenever this screen comes back
    // (it leaves composition while a chat or the folder picker is shown).
    LaunchedEffect(device.id, device.url, refreshTick) {
        loading = true
        error = null
        try {
            // Sessions first: they are what the screen is for. Account and usage need the
            // computer to run `claude auth status` (a second or two), so they load alongside
            // and fill their cards in when ready instead of holding the list back.
            coroutineScope {
                launch { loadAccount() }
                launch {
                    val info = client.request("info", JSONObject())
                    val name = info.optString("name").trim()
                    // Only fill in a name the user hasn't chosen themselves.
                    if (name.isNotEmpty() && device.name.isBlank()) app.devices.rename(device.id, name)
                }
                loadSessions()
                loading = false
            }
            authProblem = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The bridge is the only source of truth: don't keep showing an old list.
            open = emptyList()
            closed = emptyList()
            loaded = false
            authProblem = isAuthError(e.message)
            if (!authProblem) {
                account = null
                usage = null
            }
            error = e.humanMessage("Couldn't reach the computer.")
        } finally {
            loading = false
            pullRefreshing = false
        }
    }

    // Account and usage change slowly: refresh them once a minute while visible. Only while
    // RESUMED, so nothing polls (radio, battery) once the app is in the background.
    LaunchedEffect(device.id, device.url) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(60_000)
                if (!loading) loadAccount()
            }
        }
    }

    // Poll while visible (RESUMED) so running states stay current.
    LaunchedEffect(device.id, device.url) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(5_000)
                if (loading) continue
                try {
                    loadSessions()
                    error = null
                    authProblem = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isAuthError(e.message)) authProblem = true
                    error = e.humanMessage("Couldn't reach the computer.")
                }
            }
        }
    }

    fun refresh() {
        if (loading) return
        refreshTick++
    }

    fun resume(row: SessionRow) {
        val sessionId = row.sessionId
        if (sessionId == null) {
            scope.launch { snackbar.showSnackbar("This session can't be resumed.") }
            return
        }
        if (resuming) return
        resuming = true
        scope.launch {
            try {
                onOpenSession(client.resumeSession(sessionId, row.cwd))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(e.humanMessage("Couldn't resume the session."))
            } finally {
                resuming = false
            }
        }
    }

    fun closeSession(row: SessionRow) {
        val key = row.key ?: return
        scope.launch {
            try {
                client.request("close", JSONObject().put("key", key))
                snackbar.showSnackbar("Session closed.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(e.humanMessage("Couldn't close the session."))
            }
            refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Devices") } },
                title = {
                    Column {
                        Text(device.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            device.address,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = MonoFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = ::refresh, enabled = !loading) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = pullRefreshing,
            onRefresh = {
                if (!loading) {
                    pullRefreshing = true
                    refreshTick++
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            // Every state is inside a LazyColumn so pull-to-refresh works everywhere.
            LazyColumn(Modifier.fillMaxSize()) {
                when {
                    error != null && !loaded -> item(key = "__error") {
                        Column(
                            Modifier.fillParentMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        ) {
                            if (needsSignIn) {
                                AccountCard(
                                    account = account,
                                    needsSignIn = true,
                                    onSignIn = { showSignIn = true },
                                    onSignOut = { confirmSignOut = true },
                                )
                            } else {
                                StateMessage(
                                    icon = Icons.Default.CloudOff,
                                    title = "Can't reach ${device.displayName}",
                                    body = error ?: "",
                                )
                            }
                            Button(onClick = ::refresh, enabled = !loading) { Text("Retry") }
                        }
                    }

                    loading && !loaded -> item(key = "__loading") {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    else -> {
                        if (needsSignIn || account?.loggedIn == true) item(key = "__account") {
                            AccountCard(
                                account = account,
                                needsSignIn = needsSignIn,
                                onSignIn = { showSignIn = true },
                                onSignOut = { confirmSignOut = true },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                            )
                        }
                        val currentUsage = usage
                        if (!needsSignIn && account?.loggedIn == true && currentUsage != null &&
                            currentUsage.available && currentUsage.limits.isNotEmpty()
                        ) item(key = "__usage") {
                            UsageCard(
                                usage = currentUsage,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                            )
                        }
                        item(key = "__new") {
                            Button(
                                onClick = onNewSession,
                                shape = MaterialTheme.shapes.medium,
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp, pressedElevation = 2.dp),
                                modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                            ) {
                                Icon(Icons.Default.Add, null, Modifier.padding(end = 8.dp))
                                Text("New session", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        if (error != null) item(key = "__inlineError") {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.CloudOff,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    error ?: "",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                                )
                                TextButton(onClick = ::refresh, enabled = !loading) { Text("Retry") }
                            }
                        }
                        if (resuming) item(key = "__resuming") {
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                        item(key = "__openHeader") { SectionHeader("Open") }
                        if (open.isEmpty()) item(key = "__openEmpty") {
                            EmptyRow(Icons.Default.Terminal, "No open sessions. Start one with New session.")
                        }
                        items(open, key = { "o:" + (it.key ?: it.sessionId ?: it.cwd) }, contentType = { "session" }) { row ->
                            SessionItem(
                                row = row,
                                isOpen = true,
                                onClick = { row.key?.let(onOpenSession) },
                                onClose = { confirmClose = row },
                            )
                        }
                        item(key = "__closedHeader") { SectionHeader("Recent") }
                        if (closed.isEmpty()) item(key = "__closedEmpty") {
                            EmptyRow(Icons.Default.History, "No saved sessions on this computer.")
                        }
                        items(closed, key = { "c:" + (it.sessionId ?: (it.cwd + it.lastActive)) }, contentType = { "session" }) { row ->
                            SessionItem(row = row, isOpen = false, onClick = { resume(row) }, onClose = null)
                        }
                        item(key = "__bottom") { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
                    }
                }
            }
        }
    }

    confirmClose?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmClose = null },
            icon = { Icon(Icons.Default.Close, null) },
            title = { Text("Close session?") },
            text = {
                Text(
                    "Claude Code stops in ${folderNameOf(row.cwd)}" +
                        (if (row.running) ", interrupting what it's doing" else "") +
                        ". You can resume it later from Recent.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClose = null
                        closeSession(row)
                    },
                    colors = destructiveButtonColors(),
                ) { Text("Close session") }
            },
            dismissButton = { TextButton(onClick = { confirmClose = null }) { Text("Cancel") } },
        )
    }

    if (showSignIn) {
        SignInDialog(
            client = client,
            onDismiss = { showSignIn = false },
            onSignedIn = {
                showSignIn = false
                authProblem = false
                refreshTick++
            },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Claude Code will be signed out on ${device.displayName}. Sessions there won't work until you sign in again.") },
            confirmButton = {
                Button(colors = destructiveButtonColors(), onClick = {
                    confirmSignOut = false
                    scope.launch {
                        try {
                            account = parseAccount(client.request("logout", JSONObject()))
                            usage = null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            snackbar.showSnackbar(e.humanMessage("Couldn't sign out."))
                        }
                        refreshTick++
                    }
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    SectionLabel(text, modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun EmptyRow(icon: ImageVector, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** A centered icon + title + explanation, for empty and error screens. */
@Composable
internal fun StateMessage(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body.isNotBlank()) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
