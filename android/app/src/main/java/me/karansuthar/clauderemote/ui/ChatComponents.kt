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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import me.karansuthar.clauderemote.data.ChatImage
import me.karansuthar.clauderemote.data.ChatItem
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
            // ChatItem is @Immutable and only the last item changes while streaming, so every
            // other row skips recomposition.
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
                Column {
                    if (item.images.isNotEmpty()) MessageImages(item.images, Modifier.padding(4.dp))
                    if (item.text.isNotEmpty() || item.images.isEmpty()) {
                        val text = remember(item.text) { safeBackticks(item.text) }
                        val top = if (item.images.isEmpty()) 10.dp else 4.dp
                        SelectionContainer {
                            Text(text, Modifier.padding(start = 14.dp, end = 14.dp, top = top, bottom = 10.dp))
                        }
                    }
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
                SelectionContainer { MarkdownText(item.text, Modifier.fillMaxWidth(), live = live) }
            }
        }
        else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
            ) {
                val base = MaterialTheme.typography.bodySmall
                val noteStyle = remember(base) { base.copy(fontStyle = FontStyle.Italic) }
                Text(
                    remember(item.text) { safeBackticks(item.text) },
                    style = noteStyle,
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
    val base = MaterialTheme.typography.bodySmall
    val style = remember(base) { base.copy(fontStyle = FontStyle.Italic) }
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
        // While thinking, show the latest line; afterwards collapse unless opened. Cached: the
        // thinking text can be long and this would otherwise split it on every streamed delta.
        val shown = remember(thinking, expanded, stillThinking) {
            safeBackticks(
                when {
                    expanded -> thinking
                    stillThinking -> thinking.trim().lines().lastOrNull { it.isNotBlank() }.orEmpty()
                    else -> ""
                },
            )
        }
        if (shown.isNotEmpty()) Text(shown, style = style, color = dim)
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

/** An image picked in the input bar: [image] and [thumb] are null while it is being prepared. */
@Immutable
private class PendingImage(val id: Long, val image: ChatImage? = null, val thumb: ImageBitmap? = null)

private val pendingIds = AtomicLong()

/** Most images in one message, and most base64 in one send (the socket queue holds 16 MB). */
private const val MAX_IMAGES = 10
private const val MAX_SEND_CHARS = 12_000_000L

/**
 * Text input with an attach button and Send; while Claude Code is running, a Stop button sits
 * beside it. Picked images show as thumbnails above the field until they are sent. [onSend] gets
 * the text, the images and a callback for whether the send went through. [onMessage] shows a
 * short notice (a snackbar).
 */
@Composable
fun InputBar(
    running: Boolean,
    onSend: (text: String, images: List<ChatImage>, onResult: (Boolean) -> Unit) -> Unit,
    onStop: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val pending = remember { mutableStateListOf<PendingImage>() }
    var sending by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_IMAGES),
    ) { uris ->
        val room = MAX_IMAGES - pending.size
        if (uris.size > room) onMessage("Up to $MAX_IMAGES images per message")
        for (uri in uris.take(room.coerceAtLeast(0))) {
            val id = pendingIds.incrementAndGet()
            pending += PendingImage(id)
            scope.launch {
                val prepared = try {
                    prepareImage(context.applicationContext, uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
                val index = pending.indexOfFirst { it.id == id }
                if (index < 0) return@launch // removed while it was being prepared
                if (prepared == null) {
                    pending.removeAt(index)
                    onMessage("Couldn't read that image")
                } else {
                    pending[index] = PendingImage(id, prepared.image, prepared.thumb)
                }
            }
        }
    }

    val preparing = pending.any { it.image == null }
    val canSend = enabled && !sending && !preparing && (text.isNotBlank() || pending.isNotEmpty())

    Column(modifier) {
        if (pending.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (p in pending) {
                    key(p.id) {
                        PendingThumb(p, removable = !sending) { pending.removeAll { it.id == p.id } }
                    }
                }
            }
        }
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    try {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (e: Exception) {
                        onMessage("No app to pick images with")
                    }
                },
                enabled = enabled && !sending && pending.size < MAX_IMAGES,
            ) { Icon(Icons.Default.AttachFile, "Attach images") }
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
            if (sending) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(
                    onClick = {
                        val images = pending.mapNotNull { it.image }
                        if (images.sumOf { it.data.length.toLong() } > MAX_SEND_CHARS) {
                            onMessage("These images are too large to send together. Remove some.")
                        } else {
                            val sentText = text
                            val sentIds = pending.map { it.id }.toSet()
                            text = ""
                            if (images.isNotEmpty()) sending = true
                            onSend(sentText, images) { ok ->
                                sending = false
                                if (ok) {
                                    pending.removeAll { it.id in sentIds }
                                } else {
                                    if (text.isEmpty()) text = sentText
                                    if (images.isNotEmpty()) onMessage("Couldn't send. Your images are still attached.")
                                }
                            }
                        }
                    },
                    enabled = canSend,
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}

/** A 56dp thumbnail of a picked image with an × to remove it; a spinner while it is prepared. */
@Composable
private fun PendingThumb(p: PendingImage, removable: Boolean, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(56.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(cs.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = p.thumb
            if (thumb != null) {
                Image(thumb, contentDescription = "Attached image", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (removable) {
            Surface(
                shape = CircleShape,
                color = cs.inverseSurface.copy(alpha = 0.85f),
                contentColor = cs.inverseOnSurface,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Remove image", onClick = onRemove),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Remove image", modifier = Modifier.padding(3.dp))
            }
        }
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
 * cost, context size and background tasks. [models] is null until fetched. Takes plain fields
 * rather than the whole ChatState so it skips recomposition while a reply streams in.
 * [terminal]: a mirrored terminal session, whose model isn't known until it is continued here.
 */
@Composable
fun SessionBar(
    model: String?,
    effort: String?,
    closed: Boolean,
    costUsd: Double,
    contextTokens: Int,
    background: Int,
    terminal: Boolean,
    models: List<ModelInfo>?,
    busy: Boolean,
    onModelClick: () -> Unit,
    onEffort: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = remember(models, model) { models?.find { it.value == model } }
    val efforts = current?.efforts.orEmpty()
    var effortOpen by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarChip(
            label = current?.displayName ?: model ?: if (terminal) "\u2014" else "Default model",
            enabled = !busy && !closed,
            onClick = onModelClick,
        )
        if (efforts.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            Box {
                BarChip(
                    label = effort ?: "Effort",
                    enabled = !busy && !closed,
                    onClick = { effortOpen = true },
                )
                DropdownMenu(expanded = effortOpen, onDismissRequest = { effortOpen = false }) {
                    efforts.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            trailingIcon = {
                                if (option == effort) Icon(Icons.Default.Check, "Current")
                            },
                            onClick = {
                                effortOpen = false
                                if (option != effort) onEffort(option)
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
        val stats = remember(costUsd, contextTokens) {
            "$" + String.format(Locale.US, "%.2f", costUsd) + " · " + formatTokens(contextTokens) + " ctx"
        }
        Text(
            stats,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
        if (background > 0) {
            Text(
                " · $background bg",
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
