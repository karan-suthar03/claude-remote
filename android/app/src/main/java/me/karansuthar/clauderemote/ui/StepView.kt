package me.karansuthar.clauderemote.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.karansuthar.clauderemote.data.Step

/**
 * One tool call: a header (status, tool, label, chevron) that toggles the full call and its
 * output. [sessionRunning] decides whether an unfinished step spins or is shown as stopped.
 * The caller owns [expanded], so a change of the "always expand" setting reaches every step.
 */
@Composable
fun StepView(
    step: Step,
    sessionRunning: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** Set while this step's command or subagent can be moved to the background (Ctrl+B). */
    onBackground: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val canBackground = onBackground != null && sessionRunning && !step.done &&
        step.name in BACKGROUNDABLE && !step.label.startsWith("Background:")
    val lineColor = cs.outlineVariant
    val outer = if (step.sub) {
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp)
            .drawBehind { drawRect(lineColor, size = Size(2.dp.toPx(), size.height)) }
            .padding(start = 8.dp)
    } else modifier.fillMaxWidth()
    val labelStyle = if (step.sub) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
    val isTodo = step.name == "TodoWrite"

    Column(outer.animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClickLabel = if (expanded) "Collapse" else "Expand", onClick = onToggle)
                .padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepStatus(step, sessionRunning)
            Spacer(Modifier.width(8.dp))
            if (step.name.isNotEmpty()) {
                ToolBadge(displayToolName(step.name), step.sub)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                safeBackticks(step.label.ifEmpty { step.name }),
                style = labelStyle,
                color = if (step.error) cs.error else cs.onSurfaceVariant,
                maxLines = if (isTodo) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (canBackground) {
                androidx.compose.material3.TextButton(
                    onClick = { onBackground?.invoke() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) { Text("Background", style = MaterialTheme.typography.labelMedium) }
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = cs.outline,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) StepDetails(step, sessionRunning)
    }
}

/** Tools whose running call Claude Code can move to the background. */
private val BACKGROUNDABLE = setOf("Bash", "PowerShell", "Agent", "Task")

@Composable
private fun StepDetails(step: Step, sessionRunning: Boolean) {
    val caption = MaterialTheme.typography.labelSmall
    val dim = MaterialTheme.colorScheme.outline
    Column(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (step.detail.isNotEmpty()) {
            CodeBlock(
                step.detail,
                step.detailLang,
                label = step.detailLang.ifEmpty { "input" },
                maxHeight = 320.dp,
            )
        }
        when {
            step.output.isNotEmpty() -> CodeBlock(
                step.output,
                "",
                label = if (step.error) "Error" else "Output",
                maxHeight = 320.dp,
                isError = step.error,
            )
            step.images.isNotEmpty() -> Text("Output", style = caption, color = dim)
            !step.done -> Text(if (sessionRunning) "Running…" else "No result", style = caption, color = dim)
            else -> Text("No output", style = caption, color = dim)
        }
        if (step.images.isNotEmpty()) StepImages(step.images)
    }
}

@Composable
private fun StepStatus(step: Step, sessionRunning: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when {
            step.error -> Icon(Icons.Default.ErrorOutline, "Failed", tint = cs.error, modifier = Modifier.size(16.dp))
            step.done -> Icon(Icons.Default.Check, "Done", tint = cs.primary, modifier = Modifier.size(16.dp))
            sessionRunning -> CircularProgressIndicator(
                Modifier.size(12.dp).semantics { contentDescription = "Running" },
                strokeWidth = 1.5.dp,
            )
            else -> Box(
                Modifier
                    .size(8.dp)
                    .border(1.5.dp, cs.outline, CircleShape)
                    .semantics { contentDescription = "Not finished" },
            )
        }
    }
}

@Composable
private fun ToolBadge(name: String, small: Boolean) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        val base = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
        val style = remember(base) { base.copy(fontFamily = FontFamily.Monospace) }
        Text(
            name,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp).padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** "mcp__server__tool" -> "server·tool"; anything else as is. */
private fun displayToolName(name: String): String {
    if (!name.startsWith("mcp__")) return name
    val parts = name.removePrefix("mcp__").split("__").filter { it.isNotEmpty() }
    return if (parts.isEmpty()) name else parts.joinToString("·")
}
