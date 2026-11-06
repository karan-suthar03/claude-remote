package me.karansuthar.clauderemote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.karansuthar.clauderemote.bridge.BridgeClient
import org.json.JSONArray
import org.json.JSONObject

private const val RECENT_MAX = 8

@Immutable
private data class FolderEntry(val name: String, val path: String)

@Immutable
private data class FolderListing(val path: String, val parent: String?, val dirs: List<FolderEntry>)

/** The last component of [path] ("C:\work\app" -> "app", "/home/me/" -> "me"); the path itself for roots. */
internal fun folderNameOf(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    if (trimmed.isEmpty()) return path
    val i = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    return if (i >= 0 && i < trimmed.lastIndex) trimmed.substring(i + 1) else trimmed
}

private fun parseEntries(arr: JSONArray?): List<FolderEntry> {
    if (arr == null) return emptyList()
    val out = ArrayList<FolderEntry>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val path = o.optString("path")
        if (path.isEmpty()) continue
        val name = o.optString("name").ifEmpty { path }
        out.add(FolderEntry(name, path))
    }
    return out
}

private fun parseListing(reply: JSONObject, requested: String): FolderListing {
    val parent = if (!reply.has("parent") || reply.isNull("parent")) {
        null
    } else {
        reply.optString("parent").takeIf { it.isNotEmpty() && it != "null" }
    }
    return FolderListing(
        path = reply.optString("path").ifEmpty { requested },
        parent = parent,
        dirs = parseEntries(reply.optJSONArray("dirs")),
    )
}

/** Distinct working folders of open and closed sessions, most recently used first. */
private fun parseRecentFolders(reply: JSONObject): List<FolderEntry> {
    val all = ArrayList<Pair<String, Long>>()
    for (name in listOf("open", "closed")) {
        val arr = reply.optJSONArray(name) ?: continue
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cwd = o.optString("cwd").trim()
            if (cwd.isNotEmpty()) all.add(cwd to o.optLong("lastActive", 0L))
        }
    }
    return all.sortedByDescending { it.second }
        .map { it.first }
        .distinct()
        .take(RECENT_MAX)
        .map { FolderEntry(folderNameOf(it), it) }
}

/** The folder history, saved across rotation and process death ("" stands for the roots list). */
private val BackStackSaver = listSaver<SnapshotStateList<String?>, String>(
    save = { list -> list.map { it ?: "" } },
    restore = { saved -> mutableStateListOf<String?>().apply { addAll(saved.map { it.ifEmpty { null } }) } },
)

/**
 * Picks a folder on the computer and opens a Claude Code session there. Folders and recent
 * folders are fetched live from the bridge on every visit; nothing is stored on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPicker(client: BridgeClient, onBack: () -> Unit, onOpened: (key: String) -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // null = the roots list; otherwise a folder path.
    var location by rememberSaveable { mutableStateOf<String?>(null) }
    val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf<String?>() }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var roots by remember { mutableStateOf<List<FolderEntry>?>(null) }
    var recent by remember { mutableStateOf<List<FolderEntry>>(emptyList()) }
    var listing by remember { mutableStateOf<FolderListing?>(null) }
    var loading by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable(location) { mutableStateOf("") }

    fun navigate(to: String?) {
        if (to == location) return
        backStack.add(location)
        location = to
    }

    fun goBack() {
        if (backStack.isNotEmpty()) {
            location = backStack.removeAt(backStack.lastIndex)
        } else {
            onBack()
        }
    }

    fun start(cwd: String) {
        if (starting) return
        starting = true
        scope.launch {
            try {
                val reply = client.request("open", JSONObject().put("cwd", cwd))
                val key = reply.optString("key")
                if (key.isEmpty()) throw Exception("The computer didn't return a session.")
                onOpened(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(e.humanMessage("Couldn't start the session."))
            } finally {
                starting = false
            }
        }
    }

    BackHandler { goBack() }

    LaunchedEffect(location, reloadKey) {
        val loc = location
        loading = true
        error = null
        listing = null
        try {
            if (loc == null) {
                coroutineScope {
                    // Recent folders are a convenience: if they fail, the roots still show.
                    val recentJob = async {
                        try {
                            parseRecentFolders(client.request("sessions"))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                    val reply = client.request("roots")
                    roots = parseEntries(reply.optJSONArray("roots"))
                    recent = recentJob.await()
                }
            } else {
                val reply = client.request("ls", JSONObject().put("path", loc))
                listing = parseListing(reply, loc)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.humanMessage(if (loc == null) "Couldn't list the computer's drives." else "Couldn't open this folder.")
        } finally {
            loading = false
        }
    }

    val current = listing
    val canStart = location != null && current != null && !loading && error == null && !starting

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text("New session", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = location?.let(::folderNameOf) ?: "Choose a folder",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (location != null) {
                        IconButton(onClick = { showHidden = !showHidden }) {
                            Icon(
                                if (showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (showHidden) "Hide hidden folders" else "Show hidden folders",
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (location == null) {
                            "Pick a folder. Claude Code will run there with your computer's settings."
                        } else {
                            "Claude Code will run in this folder with your computer's settings."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { current?.let { start(it.path) } },
                        enabled = canStart,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (starting) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text("Starting…", Modifier.padding(start = 12.dp))
                        } else {
                            Text("Start Claude Code here")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (location != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = current?.path ?: location.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Filter folders") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                    error != null -> Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StateMessage(
                            icon = Icons.Filled.CloudOff,
                            title = if (location == null) "Can't list folders" else "Can't open this folder",
                            body = error.orEmpty(),
                        )
                        Button(onClick = { reloadKey++ }) { Text("Retry") }
                        if (backStack.isNotEmpty()) {
                            OutlinedButton(onClick = { goBack() }) { Text("Go back") }
                        }
                        if (location != null) {
                            OutlinedButton(onClick = { navigate(null) }) { Text("All drives") }
                        }
                    }

                    location == null -> {
                        val list = roots.orEmpty()
                        if (list.isEmpty() && recent.isEmpty()) {
                            StateMessage(
                                icon = Icons.Filled.Storage,
                                title = "No locations",
                                body = "The computer didn't report any drives or folders.",
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                if (recent.isNotEmpty()) {
                                    item(key = "__recentHeader") { PickerHeader("Recent folders") }
                                    items(recent, key = { "r:" + it.path }, contentType = { "folder" }) { folder ->
                                        FolderRow(icon = Icons.Filled.History, label = folder.name, sub = folder.path) {
                                            navigate(folder.path)
                                        }
                                    }
                                }
                                item(key = "__rootsHeader") { PickerHeader("Locations") }
                                items(list, key = { "l:" + it.path }, contentType = { "folder" }) { root ->
                                    val icon = if (root.name.equals("Home", ignoreCase = true)) {
                                        Icons.Filled.Home
                                    } else {
                                        Icons.Filled.Storage
                                    }
                                    FolderRow(icon = icon, label = root.name, sub = root.path) {
                                        navigate(root.path)
                                    }
                                }
                            }
                        }
                    }

                    current != null -> {
                        val query = filter.trim()
                        // Folders can number in the thousands: filter only when the listing, the
                        // hidden toggle or the query changes, not on every recomposition.
                        val visible = remember(current, showHidden) {
                            if (showHidden) current.dirs else current.dirs.filterNot { it.name.startsWith(".") }
                        }
                        val dirs = remember(visible, query) {
                            if (query.isEmpty()) {
                                visible
                            } else {
                                visible.filter { it.name.contains(query, ignoreCase = true) }
                            }
                        }
                        val hiddenCount = current.dirs.size - visible.size
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            val parent = current.parent
                            if (parent != null) {
                                item(key = "__up") {
                                    FolderRow(icon = Icons.Filled.ArrowUpward, label = "..  Up") {
                                        navigate(parent)
                                    }
                                }
                            }
                            item(key = "__roots") {
                                FolderRow(icon = Icons.Filled.Storage, label = "All drives") {
                                    navigate(null)
                                }
                            }
                            item(key = "__divider") { HorizontalDivider() }
                            if (dirs.isEmpty()) {
                                item(key = "__empty") {
                                    Row(
                                        Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.FolderOpen,
                                            null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            text = when {
                                                current.dirs.isEmpty() -> "No subfolders."
                                                visible.isEmpty() -> "Only hidden folders here."
                                                else -> "No folders match \"$query\"."
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 12.dp),
                                        )
                                    }
                                }
                            } else {
                                items(dirs, key = { "d:" + it.path }, contentType = { "folder" }) { dir ->
                                    FolderRow(icon = Icons.Filled.Folder, label = dir.name) {
                                        navigate(dir.path)
                                    }
                                }
                            }
                            if (!showHidden && hiddenCount > 0) {
                                item(key = "__hidden") {
                                    Text(
                                        text = if (hiddenCount == 1) "1 hidden folder not shown" else "$hiddenCount hidden folders not shown",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showHidden = true }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun FolderRow(icon: ImageVector, label: String, sub: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (sub != null && sub != label) {
            {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            null
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
