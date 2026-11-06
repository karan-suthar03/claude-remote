package me.karansuthar.clauderemote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import me.karansuthar.clauderemote.bridge.BridgeClient
import org.json.JSONObject

/** Sessions shown before "Show all". */
private const val FOLDER_SESSIONS_SHOWN = 3

/** One folder's sessions, as the picker shows them while browsing. */
@Immutable
internal sealed interface FolderSessions {
    data object Loading : FolderSessions
    data class Failed(val message: String) : FolderSessions
    /** Open sessions first (running in the bridge or a terminal), then saved ones; newest first in each. */
    data class Loaded(val open: List<SessionRow>, val closed: List<SessionRow>) : FolderSessions {
        val all: List<SessionRow> get() = open + closed
    }
}

/** Fetches [cwd]'s sessions from the bridge; never throws except for cancellation. */
internal suspend fun loadFolderSessions(client: BridgeClient, cwd: String): FolderSessions = try {
    val reply = client.request("sessions", JSONObject().put("cwd", cwd))
    FolderSessions.Loaded(
        open = parseSessions(reply.optJSONArray("open")).sortedByDescending { it.lastActive },
        closed = parseSessions(reply.optJSONArray("closed")).sortedByDescending { it.lastActive },
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    FolderSessions.Failed(e.humanMessage("Couldn't load this folder's sessions."))
}

/**
 * The "Sessions in this folder" section of the picker. Shows nothing for a folder with no
 * sessions, so plain browsing stays uncluttered.
 */
internal fun LazyListScope.folderSessionItems(
    state: FolderSessions,
    expanded: Boolean,
    enabled: Boolean,
    onExpand: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (SessionRow) -> Unit,
) {
    when (state) {
        FolderSessions.Loading -> item(key = "__sessionsLoading") {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    "Looking for sessions in this folder…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is FolderSessions.Failed -> item(key = "__sessionsFailed") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }

        is FolderSessions.Loaded -> {
            val all = state.all
            if (all.isEmpty()) return
            val shown = if (expanded) all else all.take(FOLDER_SESSIONS_SHOWN)
            item(key = "__sessionsHeader") {
                Text(
                    if (all.size == 1) "1 session in this folder" else "${all.size} sessions in this folder",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(shown, key = { "s:" + (it.key ?: it.sessionId ?: it.lastActive.toString()) }, contentType = { "session" }) { row ->
                SessionItem(
                    row = row,
                    isOpen = row.key != null,
                    onClick = { onOpen(row) },
                    onClose = null,
                    showFolder = false,
                    enabled = enabled,
                )
            }
            if (all.size > shown.size) {
                item(key = "__sessionsMore") {
                    Text(
                        "Show all ${all.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onExpand)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            item(key = "__foldersHeader") {
                Text(
                    "Folders",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
        }
    }
}
