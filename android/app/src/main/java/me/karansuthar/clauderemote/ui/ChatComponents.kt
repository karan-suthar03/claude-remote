package me.karansuthar.clauderemote.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import me.karansuthar.clauderemote.UiMessage

@Composable
fun MessageList(messages: List<UiMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val last = messages.lastOrNull()
    // Follow the stream as text arrives.
    LaunchedEffect(messages.size, last?.text?.length, last?.thinking?.length, last?.steps?.size, last?.streaming) {
        if (messages.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
        // The bubble animates its size, so it is still growing now; follow it once it settles.
        // A newer change relaunches this effect and cancels the wait.
        delay(300)
        listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        itemsIndexed(messages) { _, message -> MessageBubble(message) }
    }
}

@Composable
private fun MessageBubble(message: UiMessage) {
    val alignment = if (message.fromUser) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        if (message.fromUser) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    SelectionContainer { Text(message.text) }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().animateContentSize()) {
                if (message.thinking.isNotEmpty()) ThinkingBlock(message.thinking, stillThinking = message.streaming && message.text.isEmpty() && message.steps.isEmpty())
                else if (message.streaming && message.text.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                message.steps.forEach { step ->
                    Text("· $step", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                }
                if (message.steps.isNotEmpty()) Spacer(Modifier.size(4.dp))
                if (message.text.isNotEmpty()) SelectionContainer { Text(message.text) }
                message.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ThinkingBlock(thinking: String, stillThinking: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic)
    Column(Modifier.clickable { expanded = !expanded }.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stillThinking) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(6.dp))
            }
            Text(if (expanded) "Thinking ▴" else "Thinking ▾", style = style, color = MaterialTheme.colorScheme.outline)
        }
        // While thinking, show the latest line; afterwards collapse unless opened.
        val shown = when {
            expanded -> thinking
            stillThinking -> thinking.trim().lines().lastOrNull { it.isNotBlank() }.orEmpty()
            else -> ""
        }
        if (shown.isNotEmpty()) Text(shown, style = style, color = MaterialTheme.colorScheme.outline)
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

/** Text input; while a reply runs, the send button becomes stop. */
@Composable
fun InputBar(
    busy: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Message Claude Code") },
            modifier = Modifier.weight(1f),
            maxLines = 6,
            shape = RoundedCornerShape(24.dp),
        )
        if (busy) {
            IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Stop") }
        } else {
            IconButton(
                onClick = {
                    onSend(text)
                    text = ""
                },
                enabled = text.isNotBlank(),
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}
