package me.karansuthar.clauderemote.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import me.karansuthar.clauderemote.data.ChatItem
import me.karansuthar.clauderemote.data.ChatState
import me.karansuthar.clauderemote.data.ModelInfo
import me.karansuthar.clauderemote.data.Step

/**
 * The chat; [running] means Claude Code is working on this session right now. Follows new output
 * only while the user is at the bottom; scrolling up stops that until they come back down.
 */
@Composable
fun MessageList(
    items: List<ChatItem>,
    running: Boolean,
    modifier: Modifier = Modifier,
    alwaysExpandSteps: Boolean = true,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val last = items.lastOrNull()
    // A "working" row at the end while running and no reply has started yet.
    val showWorking = running && last?.role != "assistant"
    val count = items.size + if (showWorking) 1 else 0
    val threshold = with(LocalDensity.current) { 48.dp.roundToPx() }
    val nearBottom by remember(listState, threshold) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            lastVisible.index >= info.totalItemsCount - 1 &&
                lastVisible.offset + lastVisible.size <= info.viewportEndOffset + threshold
        }
    }
    var follow by remember { mutableStateOf(true) }
    val autoScrolling = remember { BooleanArray(1) }
    var seenCount by remember { mutableIntStateOf(0) }
    // Only a scroll the user makes (drag or fling) away from the bottom stops following.
    LaunchedEffect(listState) {
        snapshotFlow { nearBottom }.collect { atBottom ->
            if (atBottom) follow = true
            else if (listState.isScrollInProgress && !autoScrolling[0]) follow = false
        }
    }
    LaunchedEffect(follow, nearBottom, count) { if (follow || nearBottom) seenCount = count }

    val stepsDone = last?.steps?.count { it.done } ?: 0
    LaunchedEffect(count, last?.text?.length, last?.thinking?.length, last?.steps?.size, stepsDone, running, follow) {
        if (!follow || count == 0) return@LaunchedEffect
        autoScrolling[0] = true
        try {
            listState.scrollToItem(count - 1, Int.MAX_VALUE)
        } finally {
            autoScrolling[0] = false
        }
        // Expanding content may still be growing; follow it once it settles. A newer change
        // relaunches this effect and cancels the wait.
        delay(300)
        if (!follow) return@LaunchedEffect
        autoScrolling[0] = true
        try {
            listState.scrollToItem(count - 1, Int.MAX_VALUE)
        } finally {
            autoScrolling[0] = false
        }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(12.dp),
        ) {
            // Items are only ever appended (a snapshot replaces the list), so the index is stable.
            itemsIndexed(items, key = { index, _ -> index }, contentType = { _, item -> item.role }) { index, item ->
                MessageBubble(
                    item,
                    live = running && index == items.lastIndex,
                    running = running,
                    alwaysExpandSteps = alwaysExpandSteps,
                )
            }
            if (showWorking) item(key = "working", contentType = "working") { Working("Working…") }
        }
        if (!nearBottom && count > 0) {
            val hasNew = count > seenCount
            SmallFloatingActionButton(
                onClick = {
                    follow = true
                    scope.launch {
                        autoScrolling[0] = true
                        try {
                            listState.animateScrollToItem(count - 1)
                            listState.scrollToItem(count - 1, Int.MAX_VALUE)
                        } finally {
                            autoScrolling[0] = false
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = if (hasNew) 12.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom")
                    if (hasNew) {
                        Spacer(Modifier.size(4.dp))
                        Text("New messages", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Working(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

/** [live]: this is the reply being written now. */
@Composable
private fun MessageBubble(item: ChatItem, live: Boolean, running: Boolean, alwaysExpandSteps: Boolean) {
    when (item.role) {
        "user" -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    val text = remember(item.text) { safeBackticks(item.text) }
                    SelectionContainer { Text(text) }
                }
            }
        }
        "assistant" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.thinking.isNotEmpty()) {
                ThinkingBlock(item.thinking, stillThinking = live && item.text.isEmpty() && item.steps.isEmpty())
            } else if (live && item.text.isEmpty() && item.steps.isEmpty()) {
                Working("Thinking…")
            }
            if (item.steps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (step in item.steps) {
                        key(step.id) { StepView(step, sessionRunning = running, defaultExpanded = alwaysExpandSteps) }
                    }
                }
            }
            if (item.text.isNotBlank()) {
                SelectionContainer { MarkdownText(item.text, Modifier.fillMaxWidth()) }
            }
        }
        else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    safeBackticks(item.text),
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ThinkingBlock(thinking: String, stillThinking: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic)
    val dim = MaterialTheme.colorScheme.outline
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "Hide thinking" else "Show thinking") { expanded = !expanded }
            .padding(bottom = 4.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stillThinking) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(6.dp))
            }
            Text("Thinking", style = style, color = dim)
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = dim,
                modifier = Modifier.size(16.dp),
            )
        }
        // While thinking, show the latest line; afterwards collapse unless opened.
        val shown = when {
            expanded -> thinking
            stillThinking -> thinking.trim().lines().lastOrNull { it.isNotBlank() }.orEmpty()
            else -> ""
        }
        if (shown.isNotEmpty()) Text(safeBackticks(shown), style = style, color = dim)
    }
}

/** An action waiting for a yes: what will happen, and the two answers. */
@Composable
fun ConfirmCard(question: String, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(question, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onAnswer(false) }) { Text("No") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = { onAnswer(true) }) { Text("Yes") }
            }
        }
    }
}

/** Text input with Send; while Claude Code is running, a Stop button sits beside it. */
@Composable
fun InputBar(
    running: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(if (enabled) "Message Claude Code" else "Session closed") },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            maxLines = 6,
            shape = RoundedCornerShape(24.dp),
        )
        if (running && enabled) {
            IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Stop") }
        }
        IconButton(
            onClick = {
                onSend(text)
                text = ""
            },
            enabled = enabled && text.isNotBlank(),
        ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
    }
}

/** "34k" style token count. */
fun formatTokens(n: Int): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> "${(n / 1_000.0).roundToInt()}k"
    else -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
}

/**
 * A slim bar under the app bar: the session's model and effort (each opens a picker) and its
 * cost, context size and background tasks. [models] is null until fetched.
 */
@Composable
fun SessionBar(
    state: ChatState,
    models: List<ModelInfo>?,
    busy: Boolean,
    onModelClick: () -> Unit,
    onEffort: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = models?.find { it.value == state.model }
    val efforts = current?.efforts.orEmpty()
    var effortOpen by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarChip(
            label = current?.displayName ?: state.model ?: "Default model",
            enabled = !busy && !state.closed,
            onClick = onModelClick,
        )
        if (efforts.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            Box {
                BarChip(
                    label = state.effort ?: "Effort",
                    enabled = !busy && !state.closed,
                    onClick = { effortOpen = true },
                )
                DropdownMenu(expanded = effortOpen, onDismissRequest = { effortOpen = false }) {
                    efforts.forEach { effort ->
                        DropdownMenuItem(
                            text = { Text(effort) },
                            trailingIcon = {
                                if (effort == state.effort) Icon(Icons.Default.Check, "Current")
                            },
                            onClick = {
                                effortOpen = false
                                if (effort != state.effort) onEffort(effort)
                            },
                        )
                    }
                }
            }
        }
        if (busy) {
            Spacer(Modifier.size(6.dp))
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        }
        Spacer(Modifier.weight(1f))
        val stats = "$" + String.format(Locale.US, "%.2f", state.costUsd) + " · " +
            formatTokens(state.contextTokens) + " ctx"
        Text(
            stats,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
        if (state.background > 0) {
            Text(
                " · ${state.background} bg",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
            Spacer(Modifier.size(4.dp))
            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
        }
    }
}

@Composable
private fun BarChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 4.dp).height(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
        }
    }
}

/** Picks the session's model; [onSaveDefault] keeps the current model and effort for new sessions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    models: List<ModelInfo>?,
    current: String?,
    onPick: (ModelInfo) -> Unit,
    onSaveDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Model", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onSaveDefault, enabled = current != null) { Text("Use for new sessions") }
            }
            HorizontalDivider()
            when {
                models == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
                models.isEmpty() -> Text(
                    "No models available.",
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(20.dp),
                )
                else -> LazyColumn {
                    items(models, key = { it.value }) { model ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(model) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                                if (model.description.isNotBlank()) {
                                    Text(
                                        model.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                            if (model.value == current) {
                                Spacer(Modifier.size(12.dp))
                                Icon(Icons.Default.Check, "Current", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
