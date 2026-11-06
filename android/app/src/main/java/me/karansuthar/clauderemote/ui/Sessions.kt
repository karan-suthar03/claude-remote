package me.karansuthar.clauderemote.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.karansuthar.clauderemote.bridge.BridgeClient
import org.json.JSONArray
import org.json.JSONObject

/** A session as the bridge's `sessions` reply lists it (open or closed). */
@Immutable
internal data class SessionRow(
    val key: String?,
    val sessionId: String?,
    val cwd: String,
    val title: String,
    val running: Boolean,
    val lastActive: Long,
    /** A Claude Code session running in a terminal on the computer, mirrored live. */
    val terminal: Boolean = false,
)

internal fun parseSessions(arr: JSONArray?): List<SessionRow> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        SessionRow(
            key = o.optStringOrNull("key"),
            sessionId = o.optStringOrNull("sessionId"),
            cwd = o.optString("cwd"),
            title = o.optString("title"),
            running = o.optString("state") == "running",
            lastActive = o.optLong("lastActive", 0L),
            terminal = o.optString("origin") == "terminal",
        )
    }
}

private fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotEmpty() } else null

/**
 * Reopens a saved session in [cwd] and returns its key. Throws with a message for the user
 * when the computer can't.
 */
internal suspend fun BridgeClient.resumeSession(sessionId: String, cwd: String): String {
    val params = JSONObject().put("resume", sessionId)
    if (cwd.isNotBlank()) params.put("cwd", cwd)
    val key = request("open", params).optString("key")
    if (key.isEmpty()) throw Exception("The computer didn't return a session.")
    return key
}

@Composable
private fun StatusDot(running: Boolean, isOpen: Boolean) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when {
            running -> {
                CircularProgressIndicator(
                    Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.tertiaryContainer,
                )
                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
            }
            isOpen -> Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
            else -> Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
        }
    }
}

/** "Just now", "5 minutes ago", "Yesterday"…; empty when unknown. */
private fun relativeTime(time: Long): String {
    if (time <= 0) return ""
    val now = System.currentTimeMillis()
    // The computer's clock can run slightly ahead of the phone's: never show "in N minutes".
    return if (now - time < DateUtils.MINUTE_IN_MILLIS) "Just now"
    else DateUtils.getRelativeTimeSpanString(minOf(time, now), now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/**
 * One session card. [showFolder] false is for lists of a single folder's sessions: the title
 * leads and the folder isn't repeated.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionItem(
    row: SessionRow,
    isOpen: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)?,
    showFolder: Boolean = true,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val time = relativeTime(row.lastActive)

    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .combinedClickable(
                        enabled = enabled,
                        onClick = onClick,
                        onLongClick = if (onClose != null && row.key != null) {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menu = true
                            }
                        } else null,
                        onLongClickLabel = if (onClose != null) "Session options" else null,
                    )
                    .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(running = isOpen && row.running, isOpen = isOpen)
                Column(
                    Modifier.weight(1f).padding(start = 12.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (showFolder) {
                        Text(
                            folderNameOf(row.cwd).ifBlank { "Unknown folder" },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (row.title.isNotBlank()) {
                            Text(
                                row.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            row.cwd,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = MonoFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            row.title.ifBlank { "Untitled session" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isOpen) Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (row.terminal) Badge("Terminal", emphasized = true)
                        Badge(if (row.running) "Running" else "Idle", emphasized = row.running)
                    }
                    if (time.isNotEmpty()) {
                        Text(
                            time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (onClose != null) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Close session") },
                    leadingIcon = { Icon(Icons.Filled.Close, null) },
                    onClick = {
                        menu = false
                        onClose()
                    },
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, emphasized: Boolean) {
    val colors = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (emphasized) colors.onTertiaryContainer else colors.onSecondaryContainer,
        modifier = Modifier
            .background(
                if (emphasized) colors.tertiaryContainer else colors.secondaryContainer,
                MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
