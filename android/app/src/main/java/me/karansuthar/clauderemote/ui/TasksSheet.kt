package me.karansuthar.clauderemote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.karansuthar.clauderemote.data.BgTask
import me.karansuthar.clauderemote.data.ChatSession

/**
 * The session's background tasks, like /tasks in the terminal: what each one is, its output
 * (commands; refreshed while shown) and a stop button. The list comes live from the bridge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksSheet(
    tasks: List<BgTask>,
    session: ChatSession,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var openId by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 12.dp, bottom = 16.dp),
        ) {
            Text("Background tasks", style = MaterialTheme.typography.titleMedium)
            if (tasks.isEmpty()) {
                Text(
                    "Nothing is running in the background.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            tasks.forEachIndexed { i, task ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TaskRow(
                    task = task,
                    session = session,
                    open = openId == task.id,
                    onToggle = { openId = if (openId == task.id) null else task.id },
                    onMessage = onMessage,
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: BgTask,
    session: ChatSession,
    open: Boolean,
    onToggle: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stopping by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.description.ifBlank { task.type },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (task.isAgent) "Agent${task.agent?.let { " · $it" }.orEmpty()}" else "Command",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (!task.isAgent) {
                TextButton(onClick = onToggle) { Text(if (open) "Hide" else "Output") }
            }
            TextButton(
                enabled = !stopping,
                onClick = {
                    stopping = true
                    scope.launch {
                        runCatching { session.stopTask(task.id) }
                            .onFailure { onMessage(it.message ?: "Couldn't stop it") }
                        stopping = false
                    }
                },
            ) { Text("Stop", color = MaterialTheme.colorScheme.error) }
        }
        if (open && !task.isAgent) TaskOutput(task.id, session)
    }
}

private const val TAIL_LINES = 40

/** The end of the command's output, refreshed every two seconds while shown. */
@Composable
private fun TaskOutput(taskId: String, session: ChatSession) {
    var text by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(taskId) {
        while (true) {
            runCatching { session.taskOutput(taskId) }
                .onSuccess { r ->
                    text = r.optString("output")
                    note = "Newest lines" + if (r.optBoolean("truncated")) " (of ${r.optLong("totalBytes") / 1024} KB)" else ""
                }
                .onFailure { if (text == null) text = it.message ?: "Couldn't read its output" }
            delay(2000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
        val shown = text
        if (shown == null) {
            Text("Loading…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        } else {
            // The newest lines, as the terminal's /tasks view shows them; escape sequences removed.
            val lines = shown.replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "").trimEnd().lines()
            CodeBlock(
                lines.takeLast(TAIL_LINES).joinToString("\n").ifEmpty { "(no output yet)" },
                "",
                label = "output",
                maxHeight = 320.dp,
            )
            if (note.isNotEmpty()) {
                Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
