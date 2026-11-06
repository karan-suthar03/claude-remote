package me.karansuthar.clauderemote.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------------------------

/**
 * Renders [markdown] (CommonMark-ish plus GitHub tables, task lists and strikethrough). Partial
 * text from a stream renders sensibly: an unclosed code fence is an open code block and unclosed
 * emphasis stays literal. Wrap in a SelectionContainer to make it selectable. [live]: the text is
 * still streaming in, so it is re-parsed at most every [LIVE_PARSE_MS], off the main thread.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, live: Boolean = false) {
    val blocks = if (live) rememberLiveBlocks(markdown) else remember(markdown) { parseMarkdown(markdown) }
    val style = rememberMdStyle()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Blocks are @Immutable data classes, so unchanged blocks of a re-parse skip recomposition.
        for (block in blocks) MdBlockView(block, style)
    }
}

/** How often a streaming reply is re-parsed at most. */
private const val LIVE_PARSE_MS = 80L

/**
 * Blocks for a reply that is still streaming. Re-parsing the whole reply on every delta (dozens a
 * second) on the main thread is the main source of jank on long replies, so: parse on
 * Dispatchers.Default, conflate updates that arrive meanwhile, and wait [LIVE_PARSE_MS] between
 * parses. Only the first parse (on entering composition) is synchronous, to avoid a blank frame.
 */
@Composable
private fun rememberLiveBlocks(markdown: String): List<MdBlock> {
    val latest = rememberUpdatedState(markdown)
    val blocks = remember { mutableStateOf(parseMarkdown(markdown)) }
    LaunchedEffect(Unit) {
        var parsed = markdown
        snapshotFlow { latest.value }.conflate().collect { text ->
            if (text != parsed) {
                blocks.value = withContext(Dispatchers.Default) { parseMarkdown(text) }
                parsed = text
                delay(LIVE_PARSE_MS)
            }
        }
    }
    return blocks.value
}

/** Plain text for the system font: a raw backtick (U+0060) is drawn as a combining accent on some phones. */
fun safeBackticks(text: String): String = if (text.indexOf('`') < 0) text else text.replace('`', SAFE_BACKTICK)

/** Most characters a code block renders; the rest is cut with a note (the copy button copies all). */
const val MAX_CODE_CHARS = 20_000

/**
 * A code box: a header with [label] (default: the language) and a copy button, then monospace,
 * horizontally scrollable, selectable text with light syntax colouring for [language]. A
 * [maxHeight] makes the text scroll vertically inside it. [isError] tints it with the error colour.
 */
@Composable
fun CodeBlock(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    maxHeight: Dp = Dp.Unspecified,
    isError: Boolean = false,
) {
    val colors = rememberSyntaxColors()
    val lang = remember(language) { language.trim().lowercase(Locale.ROOT) }
    val shown = remember(code) { prepareCode(code) }
    val highlighted = rememberHighlighted(shown, lang, colors)
    val lineKinds = remember(shown, lang) { if (isDiffLang(lang)) diffLineKinds(shown) else null }
    val cs = MaterialTheme.colorScheme
    val labelBase = MaterialTheme.typography.labelSmall
    val labelStyle = remember(labelBase) { labelBase.copy(fontFamily = FontFamily.Monospace) }
    val container = if (isError) cs.errorContainer.copy(alpha = 0.5f) else cs.surfaceVariant.copy(alpha = 0.7f)
    Surface(color = container, shape = RoundedCornerShape(10.dp), modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label ?: lang.ifEmpty { "text" },
                    style = labelStyle,
                    color = if (isError) cs.error else cs.outline,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                CopyButton(code)
            }
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))
            val scrollY = if (maxHeight != Dp.Unspecified) {
                Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())
            } else Modifier
            if (lineKinds != null) {
                BoxWithConstraints(scrollY.fillMaxWidth()) {
                    val minTextWidth = (maxWidth - 24.dp).coerceAtLeast(0.dp)
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        CodeText(highlighted, lineKinds, colors, Modifier.widthIn(min = minTextWidth))
                    }
                }
            } else {
                Box(scrollY.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    CodeText(highlighted, null, colors, Modifier)
                }
            }
            if (shown.length < code.length) {
                Text(
                    "Showing the first ${"%,d".format(Locale.US, shown.length)} of " +
                        "${"%,d".format(Locale.US, code.length)} characters.",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Code text, copy, syntax colours
// ---------------------------------------------------------------------------------------------

private const val SAFE_BACKTICK = '\u02CB'

/** Code at least this long is highlighted on Dispatchers.Default instead of during composition. */
private const val ASYNC_HIGHLIGHT_CHARS = 3_000

/**
 * Syntax colouring for [code]. Short code is coloured inline (cheap, no flash of plain text);
 * long code (up to [MAX_CODE_CHARS]) runs the regexes off the main thread. While a block streams in
 * (the text only grows), the previous pass's colours are kept for the unchanged prefix so the
 * colouring doesn't flicker between passes.
 */
@Composable
private fun rememberHighlighted(code: String, lang: String, colors: SyntaxColors): AnnotatedString {
    if (code.length < ASYNC_HIGHLIGHT_CHARS) {
        return remember(code, lang, colors) { highlightCode(code, lang, colors) }
    }
    val result = remember { mutableStateOf<AnnotatedString?>(null) }
    LaunchedEffect(code, lang, colors) {
        result.value = withContext(Dispatchers.Default) { highlightCode(code, lang, colors) }
    }
    val done = result.value
    return remember(code, done) {
        when {
            done == null -> AnnotatedString(code)
            done.text == code -> done
            code.startsWith(done.text) -> AnnotatedString(code, done.spanStyles)
            else -> AnnotatedString(code)
        }
    }
}

@Composable
private fun CodeText(text: AnnotatedString, lineKinds: ByteArray?, colors: SyntaxColors, modifier: Modifier) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val drawLines = if (lineKinds == null) Modifier else Modifier.drawBehind {
        val l = layout ?: return@drawBehind
        val extra = 12.dp.toPx()
        val count = minOf(l.lineCount, lineKinds.size)
        for (i in 0 until count) {
            val color = when (lineKinds[i].toInt()) {
                DIFF_ADD -> colors.addedBg
                DIFF_REMOVE -> colors.removedBg
                DIFF_HUNK -> colors.hunkBg
                else -> continue
            }
            val top = l.getLineTop(i)
            drawRect(color, topLeft = Offset(-extra, top), size = Size(size.width + 2 * extra, l.getLineBottom(i) - top))
        }
    }
    val base = MaterialTheme.typography.bodySmall
    val codeStyle = remember(base) { base.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.sp) }
    SelectionContainer {
        Text(
            text,
            style = codeStyle,
            softWrap = false,
            onTextLayout = { layout = it },
            modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp).then(drawLines),
        )
    }
}

@Composable
private fun CopyButton(text: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    IconButton(
        onClick = {
            runCatching {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Code", text))
                copied = true
            }
        },
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = if (copied) "Copied" else "Copy code",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun prepareCode(code: String): String {
    var s = code.trimEnd('\n', '\r')
    if (s.length > MAX_CODE_CHARS) {
        var end = MAX_CODE_CHARS
        if (Character.isHighSurrogate(s[end - 1])) end--
        s = s.substring(0, end)
    }
    return s
}

@Immutable
private class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val dim: Color,
    val addedBg: Color,
    val removedBg: Color,
    val hunkBg: Color,
)

@Composable
private fun rememberSyntaxColors(): SyntaxColors {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    return remember(cs, dark) {
        SyntaxColors(
            keyword = cs.primary,
            string = cs.tertiary,
            comment = cs.outline,
            number = cs.secondary,
            dim = cs.outline,
            addedBg = cs.secondary.copy(alpha = if (dark) 0.28f else 0.18f),
            removedBg = cs.error.copy(alpha = if (dark) 0.24f else 0.14f),
            hunkBg = cs.outline.copy(alpha = 0.12f),
        )
    }
}

private const val DIFF_ADD = 1
private const val DIFF_REMOVE = 2
private const val DIFF_HUNK = 3
private const val HIGHLIGHT_LIMIT = MAX_CODE_CHARS

private fun isDiffLang(lang: String) = lang == "diff" || lang == "patch"

private fun diffLineKinds(code: String): ByteArray {
    val lines = code.split('\n')
    val kinds = ByteArray(lines.size)
    for (i in lines.indices) {
        val l = lines[i]
        kinds[i] = when {
            l.startsWith("@@") -> DIFF_HUNK
            l.startsWith("+++") || l.startsWith("---") -> 0
            l.startsWith("+") -> DIFF_ADD
            l.startsWith("-") -> DIFF_REMOVE
            else -> 0
        }.toByte()
    }
    return kinds
}

private fun highlightDiff(code: String, c: SyntaxColors): AnnotatedString = buildAnnotatedString {
    append(code)
    val dim = SpanStyle(color = c.dim)
    var start = 0
    while (start <= code.length) {
        var end = code.indexOf('\n', start)
        if (end < 0) end = code.length
        if (code.startsWith("@@", start) || code.startsWith("+++", start) || code.startsWith("---", start)) {
            if (end > start) addStyle(dim, start, end)
        }
        start = end + 1
    }
}

// Group 1 comment, 2 string, 3 number, 4 keyword.
private const val NEVER = "(?!)"
private const val STRINGS = """(?:"[^"\\\n]*(?:\\.[^"\\\n]*)*"?)|(?:'[^'\\\n]*(?:\\.[^'\\\n]*)*'?)"""
private const val BACKTICK_STRING = """`[^`\\]*(?:\\.[^`\\]*)*`?"""
private const val NUMBER = """\b(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?)\b"""

private fun words(vararg w: String) = """\b(?:${w.joinToString("|")})\b"""

private fun langRegex(comment: String, string: String, keyword: String, ignoreCase: Boolean = false): Regex {
    val pattern = "($comment)|($string)|($NUMBER)|($keyword)"
    return if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
}

private val C_LIKE by lazy {
    langRegex(
        """//[^\n]*|/\*[\s\S]*?(?:\*/|$)""",
        "$STRINGS|$BACKTICK_STRING",
        words(
            "abstract", "as", "async", "await", "break", "case", "catch", "class", "const", "continue",
            "default", "defer", "delete", "do", "else", "enum", "export", "extends", "false", "final",
            "finally", "fn", "for", "fun", "func", "function", "go", "if", "impl", "implements", "import",
            "in", "instanceof", "interface", "internal", "is", "let", "match", "mod", "mut", "new", "nil",
            "null", "object", "open", "override", "package", "private", "protected", "pub", "public",
            "return", "self", "Self", "sealed", "static", "struct", "super", "switch", "this", "throw",
            "throws", "trait", "true", "try", "type", "typeof", "undefined", "use", "val", "var", "void",
            "when", "where", "while", "with", "yield", "readonly", "namespace", "declare", "lateinit",
            "data", "companion", "suspend", "inline", "operator", "unsafe", "crate", "dyn", "loop", "move",
            "int", "long", "short", "char", "float", "double", "boolean", "bool", "byte", "unsigned",
            "signed", "sizeof", "typedef", "extern", "goto", "volatile", "auto", "chan", "select", "range",
            "from", "of", "get", "set", "lambda", "elif",
        ),
    )
}

private val HASH_LIKE by lazy {
    langRegex(
        """(?<![\w$])#[^\n]*""",
        STRINGS,
        words(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
            "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is",
            "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while",
            "with", "yield", "self", "begin", "end", "elsif", "unless", "until", "module", "require",
            "then", "fi", "do", "done", "esac", "case", "function", "local", "export", "echo", "source",
            "alias", "exit", "set", "unset", "readonly", "shift", "true", "false", "null", "nil", "sudo",
            "cd", "yes", "no",
        ),
    )
}

private val SQL by lazy {
    langRegex(
        """--[^\n]*|/\*[\s\S]*?(?:\*/|$)""",
        STRINGS,
        words(
            "select", "from", "where", "and", "or", "not", "insert", "into", "values", "update", "set",
            "delete", "create", "table", "drop", "alter", "index", "join", "left", "right", "inner", "outer",
            "on", "group", "by", "order", "having", "limit", "offset", "as", "distinct", "null", "is", "in",
            "exists", "union", "all", "primary", "key", "foreign", "references", "default", "case", "when",
            "then", "else", "end", "begin", "commit", "rollback", "with", "like", "between", "count", "sum",
            "avg", "min", "max", "integer", "text", "varchar", "boolean", "unique", "if", "view", "returning",
        ),
        ignoreCase = true,
    )
}

private val JSON by lazy { langRegex(NEVER, STRINGS, words("true", "false", "null")) }

private val MARKUP by lazy { langRegex("""<!--[\s\S]*?(?:-->|$)""", STRINGS, """</?[A-Za-z][\w:.-]*|/?>""") }

private val CSS by lazy { langRegex("""/\*[\s\S]*?(?:\*/|$)""", STRINGS, """@[\w-]+|!important""") }

private fun regexFor(lang: String): Regex? = when (lang) {
    "kotlin", "kt", "kts", "java", "scala", "groovy", "gradle", "js", "javascript", "jsx", "ts",
    "typescript", "tsx", "mjs", "cjs", "c", "h", "cpp", "c++", "cc", "hpp", "cs", "csharp", "objc", "m",
    "go", "rust", "rs", "swift", "dart", "php", "proto", "zig", "vue", "svelte" -> C_LIKE
    "python", "py", "ruby", "rb", "sh", "bash", "zsh", "shell", "console", "fish", "yaml", "yml",
    "toml", "r", "perl", "pl", "powershell", "ps1", "dockerfile", "makefile", "mk", "conf", "ini",
    "env", "properties", "cmake", "nix", "gitignore" -> HASH_LIKE
    "sql", "psql", "mysql", "sqlite" -> SQL
    "json", "jsonc", "json5", "jsonl" -> JSON
    "html", "htm", "xml", "svg", "xhtml", "plist" -> MARKUP
    "css", "scss", "sass", "less" -> CSS
    else -> null
}

private fun highlightCode(code: String, lang: String, c: SyntaxColors): AnnotatedString {
    if (isDiffLang(lang)) return highlightDiff(code, c)
    val regex = regexFor(lang)
    if (regex == null || code.length > HIGHLIGHT_LIMIT) return AnnotatedString(code)
    return try {
        val comment = SpanStyle(color = c.comment, fontStyle = FontStyle.Italic)
        val string = SpanStyle(color = c.string)
        val number = SpanStyle(color = c.number)
        val keyword = SpanStyle(color = c.keyword)
        buildAnnotatedString {
            append(code)
            for (m in regex.findAll(code)) {
                val g = m.groups
                val style = when {
                    g[1] != null -> comment
                    g[2] != null -> string
                    g[3] != null -> number
                    g[4] != null -> keyword
                    else -> continue
                }
                addStyle(style, m.range.first, m.range.last + 1)
            }
        }
    } catch (e: Exception) {
        AnnotatedString(code)
    } catch (e: StackOverflowError) {
        AnnotatedString(code)
    }
}

// ---------------------------------------------------------------------------------------------
// Block model and parser
// ---------------------------------------------------------------------------------------------

// @Immutable: a streaming reply is re-parsed into new instances; equals() lets every unchanged
// block skip recomposition instead of being compared by identity.
@Immutable
internal sealed interface MdBlock {
    @Immutable
    data class Heading(val level: Int, val text: String) : MdBlock
    @Immutable
    data class Paragraph(val text: String) : MdBlock
    @Immutable
    data class Code(val lang: String, val code: String, val indent: Int) : MdBlock
    @Immutable
    data class ListBlock(val items: List<MdListItem>) : MdBlock
    @Immutable
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data object Rule : MdBlock
    /** [align] per column: -1 left/none, 0 centre, 1 right. Rows have exactly header.size cells. */
    @Immutable
    data class Table(val header: List<String>, val align: List<Int>, val rows: List<List<String>>) : MdBlock
}

@Immutable
internal data class MdListItem(
    val level: Int,
    val marker: String,
    val ordered: Boolean,
    /** null: not a task; otherwise whether it is ticked. */
    val checked: Boolean?,
    val text: String,
)

private val HEADING = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
private val HR = Regex("""^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$""")
private val LIST = Regex("""^([ \t]*)([-*+]|\d{1,9}[.)])(?:[ \t]+(.*))?$""")
private val FENCE = Regex("""^([ \t]*)(`{3,}|~{3,})([^`]*)$""")
private val QUOTE = Regex("""^ {0,3}> ?(.*)$""")
private val SETEXT1 = Regex("""^ {0,3}=+[ \t]*$""")
private val SETEXT2 = Regex("""^ {0,3}-+[ \t]*$""")
private val TABLE_DELIM = Regex("""^[ \t]*\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$""")

private const val MAX_QUOTE_DEPTH = 6
private const val MAX_TABLE_ROWS = 500

private fun indentOf(s: String): Int {
    var n = 0
    for (ch in s) {
        n += when (ch) {
            ' ' -> 1
            '\t' -> 4
            else -> return n
        }
    }
    return n
}

/** Drops up to [n] columns of leading whitespace. */
private fun stripIndent(s: String, n: Int): String {
    var col = 0
    var i = 0
    while (i < s.length && col < n) {
        when (s[i]) {
            ' ' -> col += 1
            '\t' -> col += 4
            else -> break
        }
        i++
    }
    return s.substring(i)
}

private fun isTableStart(lines: List<String>, i: Int): Boolean =
    i + 1 < lines.size && lines[i].contains('|') && lines[i + 1].contains('|') && lines[i + 1].contains('-') &&
        TABLE_DELIM.matches(lines[i + 1])

private fun startsBlock(lines: List<String>, i: Int): Boolean {
    val l = lines[i]
    return FENCE.matches(l) || HEADING.matches(l) || HR.matches(l) || QUOTE.matches(l) ||
        LIST.matches(l) || isTableStart(lines, i)
}

internal fun parseMarkdown(src: String, depth: Int = 0): List<MdBlock> {
    if (src.isBlank()) return emptyList()
    val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val out = ArrayList<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }

        val fence = FENCE.matchEntire(line)
        if (fence != null) {
            val fenceIndent = indentOf(fence.groupValues[1])
            val marker = fence.groupValues[2]
            val lang = fence.groupValues[3].trim().substringBefore(' ').trim('{', '}', '.')
            val body = StringBuilder()
            i++
            var first = true
            while (i < lines.size) {
                val l = lines[i]
                val t = l.trim()
                if (t.length >= marker.length && t.all { it == marker[0] }) {
                    i++
                    break
                }
                if (!first) body.append('\n')
                first = false
                body.append(stripIndent(l, fenceIndent))
                i++
            }
            out += MdBlock.Code(lang, body.toString(), ((fenceIndent + 1) / 3).coerceAtMost(4))
            continue
        }

        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            out += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            i++
            continue
        }

        if (HR.matches(line)) {
            out += MdBlock.Rule
            i++
            continue
        }

        if (QUOTE.matches(line)) {
            val sb = StringBuilder()
            while (i < lines.size) {
                val m = QUOTE.matchEntire(lines[i]) ?: break
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(m.groupValues[1])
                i++
            }
            out += if (depth < MAX_QUOTE_DEPTH) MdBlock.Quote(parseMarkdown(sb.toString(), depth + 1))
            else MdBlock.Paragraph(sb.toString())
            continue
        }

        if (isTableStart(lines, i)) {
            val header = splitRow(line)
            val align = splitRow(lines[i + 1]).map { cell ->
                val c = cell.trim()
                when {
                    c.startsWith(':') && c.endsWith(':') -> 0
                    c.endsWith(':') -> 1
                    else -> -1
                }
            }
            val cols = header.size
            i += 2
            val rows = ArrayList<List<String>>()
            while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                if (rows.size < MAX_TABLE_ROWS) {
                    val cells = splitRow(lines[i])
                    rows += List(cols) { c -> cells.getOrElse(c) { "" } }
                }
                i++
            }
            out += MdBlock.Table(header, List(cols) { c -> align.getOrElse(c) { -1 } }, rows)
            continue
        }

        if (LIST.matches(line)) {
            i = parseList(lines, i, out)
            continue
        }

        // Paragraph (or a setext heading).
        val sb = StringBuilder(line.trim())
        i++
        var done = false
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank()) break
            if (SETEXT1.matches(l) || SETEXT2.matches(l)) {
                out += MdBlock.Heading(if (SETEXT1.matches(l)) 1 else 2, sb.toString())
                i++
                done = true
                break
            }
            if (startsBlock(lines, i)) break
            sb.append('\n').append(l.trim())
            i++
        }
        if (!done) out += MdBlock.Paragraph(sb.toString())
    }
    return out
}

/** Parses a list starting at [start] into [out]; returns the index of the first line after it. */
private fun parseList(lines: List<String>, start: Int, out: MutableList<MdBlock>): Int {
    val items = ArrayList<MdListItem>()
    val stack = ArrayList<Int>()
    var level = 0
    var marker = ""
    var ordered = false
    var checked: Boolean? = null
    var text: StringBuilder? = null
    var afterBlank = false

    fun flush() {
        val t = text ?: return
        items += MdListItem(level, marker, ordered, checked, t.toString())
        text = null
    }

    var i = start
    while (i < lines.size) {
        val l = lines[i]
        if (l.isBlank()) {
            var j = i + 1
            while (j < lines.size && lines[j].isBlank()) j++
            if (j >= lines.size) {
                i = j
                break
            }
            val next = lines[j]
            if (LIST.matches(next) || indentOf(next) >= 2) {
                i = j
                afterBlank = true
                continue
            }
            break
        }
        val m = if (HR.matches(l)) null else LIST.matchEntire(l)
        if (m != null) {
            flush()
            val indent = indentOf(m.groupValues[1])
            while (stack.isNotEmpty() && indent < stack[stack.size - 1]) stack.removeAt(stack.size - 1)
            if (stack.isEmpty() || indent >= stack[stack.size - 1] + 2) stack += indent
            level = (stack.size - 1).coerceIn(0, 8)
            marker = m.groupValues[2]
            ordered = marker[0].isDigit()
            var body = m.groupValues[3]
            checked = null
            if (body.length >= 3 && body[0] == '[' && body[2] == ']' && (body.length == 3 || body[3] == ' ')) {
                when (body[1]) {
                    ' ' -> checked = false
                    'x', 'X' -> checked = true
                }
                if (checked != null) body = body.substring(3).trimStart()
            }
            text = StringBuilder(body.trim())
            afterBlank = false
            i++
            continue
        }
        if (FENCE.matches(l) || HEADING.matches(l) || HR.matches(l) || isTableStart(lines, i)) break
        if (QUOTE.matches(l) && indentOf(l) < 2) break
        if (afterBlank && indentOf(l) < 2) break
        val t = text ?: break
        if (t.isNotEmpty()) t.append('\n')
        t.append(l.trim())
        afterBlank = false
        i++
    }
    flush()
    if (items.isNotEmpty()) out += MdBlock.ListBlock(items)
    return if (i == start) start + 1 else i
}

/** Splits a table row on unescaped pipes outside code spans. */
private fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith('|')) s = s.substring(1)
    if (s.endsWith('|') && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
    val cells = ArrayList<String>()
    val cur = StringBuilder()
    var inCode = false
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < s.length && s[i + 1] == '|' -> {
                cur.append('|')
                i++
            }
            c == '`' -> {
                inCode = !inCode
                cur.append(c)
            }
            c == '|' && !inCode -> {
                cells += cur.toString().trim()
                cur.setLength(0)
            }
            else -> cur.append(c)
        }
        i++
    }
    cells += cur.toString().trim()
    return cells
}

// ---------------------------------------------------------------------------------------------
// Inline parser
// ---------------------------------------------------------------------------------------------

@Immutable
internal class InlineStyle(
    val code: SpanStyle,
    val link: TextLinkStyles,
    val listener: LinkInteractionListener,
)

private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val BOLD_ITALIC = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
private val STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)
private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>\"'"
/** "🖼 " between no-break spaces, so a Markdown image's chip keeps its icon and label together. */
private const val IMAGE_CHIP = "\u00A0\uD83D\uDDBC\uFE0F\u00A0"
private val SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:.+""")

internal fun inlineMarkdown(text: String, st: InlineStyle): AnnotatedString {
    val b = AnnotatedString.Builder(text.length)
    try {
        appendInline(b, text, st, allowLinks = true)
    } catch (e: StackOverflowError) {
        return AnnotatedString(safeBackticks(text))
    }
    return b.toAnnotatedString()
}

private fun runLength(s: String, at: Int, c: Char): Int {
    var j = at
    while (j < s.length && s[j] == c) j++
    return j - at
}

/** Index of a closing backtick run of exactly [run] at or after [from], or -1. */
private fun findCodeClose(s: String, from: Int, run: Int): Int {
    var j = s.indexOf('`', from)
    while (j >= 0) {
        val r = runLength(s, j, '`')
        if (r == run) return j
        j = s.indexOf('`', j + r)
    }
    return -1
}

/** Index of a closing delimiter run of [c] of exactly [run], skipping code spans, or -1. */
private fun findDelimClose(s: String, from: Int, c: Char, run: Int): Int {
    var j = from
    while (j < s.length) {
        val ch = s[j]
        when {
            ch == '\\' -> j += 2
            ch == '`' -> {
                val r = runLength(s, j, '`')
                val close = findCodeClose(s, j + r, r)
                j = if (close < 0) j + r else close + r
            }
            ch == c -> {
                val r = runLength(s, j, c)
                if (r == run && j > from && !s[j - 1].isWhitespace() &&
                    (c != '_' || s.getOrNull(j + r)?.isLetterOrDigit() != true)
                ) return j
                j += r
            }
            else -> j++
        }
    }
    return -1
}

private class LinkMatch(val labelStart: Int, val labelEnd: Int, val url: String, val end: Int)

/** Parses `[label](url "title")` with '[' at [at]. */
private fun parseLink(s: String, at: Int): LinkMatch? {
    var depth = 0
    var j = at
    while (j < s.length) {
        val ch = s[j]
        if (ch == '\\') {
            j += 2
            continue
        }
        if (ch == '`') {
            val r = runLength(s, j, '`')
            val close = findCodeClose(s, j + r, r)
            j = if (close < 0) j + r else close + r
            continue
        }
        if (ch == '[') depth++
        else if (ch == ']') {
            depth--
            if (depth == 0) break
        }
        j++
    }
    if (j >= s.length || s.getOrNull(j + 1) != '(') return null
    val labelEnd = j
    var k = j + 2
    var parens = 1
    while (k < s.length) {
        val ch = s[k]
        if (ch == '\\') {
            k += 2
            continue
        }
        if (ch == '\n') return null
        if (ch == '(') parens++
        else if (ch == ')') {
            parens--
            if (parens == 0) break
        }
        k++
    }
    if (k >= s.length) return null
    var dest = s.substring(j + 2, k).trim()
    dest = if (dest.startsWith('<')) dest.substring(1).substringBefore('>') else dest.substringBefore(' ')
    return LinkMatch(at + 1, labelEnd, dest, k + 1)
}

private fun isUrlStart(s: String, i: Int): Boolean =
    (s.startsWith("https://", i) || s.startsWith("http://", i)) && (i == 0 || !s[i - 1].isLetterOrDigit())

/** End of a bare URL starting at [i], with trailing punctuation and unbalanced ')' dropped. */
private fun bareUrlEnd(s: String, i: Int): Int {
    var e = i
    while (e < s.length && !s[e].isWhitespace() && s[e] != '<' && s[e] != '`' && s[e] != '>') e++
    while (e > i) {
        val last = s[e - 1]
        if (last in ".,;:!?'\"*_~") {
            e--
            continue
        }
        if (last == ')') {
            var open = 0
            var close = 0
            for (k in i until e) {
                if (s[k] == '(') open++ else if (s[k] == ')') close++
            }
            if (close > open) {
                e--
                continue
            }
        }
        break
    }
    return e
}

private fun AnnotatedString.Builder.appendLinked(url: String, st: InlineStyle, content: AnnotatedString.Builder.() -> Unit) {
    if (SCHEME.matches(url)) withLink(LinkAnnotation.Url(url, st.link, st.listener)) { content() }
    else content()
}

private fun appendInline(b: AnnotatedString.Builder, s: String, st: InlineStyle, allowLinks: Boolean) {
    val n = s.length
    var i = 0
    while (i < n) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < n && s[i + 1] in ESCAPABLE -> {
                val next = s[i + 1]
                b.append(if (next == '`') SAFE_BACKTICK else next)
                i += 2
            }
            c == '`' -> {
                val run = runLength(s, i, '`')
                val close = findCodeClose(s, i + run, run)
                if (close < 0) {
                    // Unmatched (e.g. still streaming): never emit a raw backtick in body text.
                    repeat(run) { b.append(SAFE_BACKTICK) }
                    i += run
                } else {
                    var code = s.substring(i + run, close).replace('\n', ' ')
                    if (code.length >= 2 && code[0] == ' ' && code[code.length - 1] == ' ' && code.isNotBlank()) {
                        code = code.substring(1, code.length - 1)
                    }
                    b.withStyle(st.code) { append(code) }
                    i = close + run
                }
            }
            c == '*' || c == '_' || (c == '~' && runLength(s, i, '~') == 2) -> {
                val run = runLength(s, i, c)
                val after = s.getOrNull(i + run)
                val before = s.getOrNull(i - 1)
                val canOpen = run <= 3 && after != null && !after.isWhitespace() &&
                    !(c == '_' && before != null && before.isLetterOrDigit())
                val close = if (canOpen) findDelimClose(s, i + run, c, run) else -1
                if (close > i + run) {
                    val style = when {
                        c == '~' -> STRIKE
                        run == 1 -> ITALIC
                        run == 2 -> BOLD
                        else -> BOLD_ITALIC
                    }
                    val inner = s.substring(i + run, close)
                    b.withStyle(style) { appendInline(this, inner, st, allowLinks) }
                    i = close + run
                } else {
                    b.append(s, i, i + run)
                    i += run
                }
            }
            allowLinks && (c == '[' || (c == '!' && s.getOrNull(i + 1) == '[')) -> {
                val image = c == '!'
                val m = parseLink(s, if (image) i + 1 else i)
                if (m == null) {
                    b.append(c)
                    i++
                } else {
                    val label = s.substring(m.labelStart, m.labelEnd)
                    if (image) {
                        // No network image loading: a chip-like link that opens the image's URL.
                        b.appendLinked(m.url, st) {
                            withStyle(st.code) { append(IMAGE_CHIP + safeBackticks(label.ifBlank { "image" }) + "\u00A0") }
                        }
                    } else {
                        b.appendLinked(m.url, st) { appendInline(this, label, st, allowLinks = false) }
                    }
                    i = m.end
                }
            }
            allowLinks && c == '<' && (s.startsWith("http", i + 1) || s.startsWith("mailto:", i + 1)) -> {
                val close = s.indexOf('>', i + 1)
                val url = if (close > 0) s.substring(i + 1, close) else ""
                if (url.isNotEmpty() && url.none { it.isWhitespace() }) {
                    b.appendLinked(url, st) { append(url) }
                    i = close + 1
                } else {
                    b.append(c)
                    i++
                }
            }
            allowLinks && c == 'h' && isUrlStart(s, i) -> {
                val end = bareUrlEnd(s, i)
                val url = s.substring(i, end)
                if (url.length > 8) {
                    b.appendLinked(url, st) { append(url) }
                    i = end
                } else {
                    b.append(c)
                    i++
                }
            }
            else -> {
                b.append(c)
                i++
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Block rendering
// ---------------------------------------------------------------------------------------------

@Immutable
private class MdStyle(
    val inline: InlineStyle,
    val ruleColor: Color,
    val quoteBar: Color,
    val quoteText: Color,
    val tableHeaderBg: Color,
    val tableBorder: Color,
    val bullet: Color,
    val taskDone: Color,
)

@Composable
private fun rememberMdStyle(): MdStyle {
    val cs = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    return remember(cs, uriHandler) {
        val listener = LinkInteractionListener { annotation ->
            val url = (annotation as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
            try {
                uriHandler.openUri(url)
            } catch (e: Exception) {
                // No app can open it: ignore rather than crash.
            }
        }
        MdStyle(
            inline = InlineStyle(
                code = SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 0.88.em,
                    background = cs.surfaceVariant,
                    color = cs.onSurfaceVariant,
                ),
                link = TextLinkStyles(
                    style = SpanStyle(color = cs.primary, textDecoration = TextDecoration.Underline),
                    pressedStyle = SpanStyle(color = cs.primary, background = cs.primary.copy(alpha = 0.12f)),
                ),
                listener = listener,
            ),
            ruleColor = cs.outlineVariant,
            quoteBar = cs.outlineVariant,
            quoteText = cs.onSurfaceVariant,
            tableHeaderBg = cs.surfaceVariant,
            tableBorder = cs.outlineVariant,
            bullet = cs.onSurfaceVariant,
            taskDone = cs.outline,
        )
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle {
    val t = MaterialTheme.typography
    val base = when (level) {
        1 -> t.headlineSmall
        2 -> t.titleLarge
        3 -> t.titleMedium
        else -> t.titleSmall
    }
    return remember(base) { base.copy(fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun MdBlockView(block: MdBlock, style: MdStyle) {
    when (block) {
        is MdBlock.Paragraph -> {
            val text = remember(block.text, style) { inlineMarkdown(block.text, style.inline) }
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
        is MdBlock.Heading -> {
            val text = remember(block.text, style) { inlineMarkdown(block.text, style.inline) }
            Text(text, style = headingStyle(block.level), modifier = Modifier.padding(top = 4.dp))
        }
        is MdBlock.Code -> CodeBlock(
            block.code,
            block.lang,
            modifier = if (block.indent > 0) Modifier.padding(start = (block.indent * 18).dp) else Modifier,
        )
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = style.ruleColor)
        is MdBlock.Quote -> {
            val bar = style.quoteBar
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawRoundRect(bar, size = Size(3.dp.toPx(), size.height), cornerRadius = CornerRadius(2.dp.toPx()))
                    }
                    .padding(start = 13.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides style.quoteText) {
                    for (b in block.blocks) MdBlockView(b, style)
                }
            }
        }
        is MdBlock.ListBlock -> MdList(block, style)
        is MdBlock.Table -> MdTable(block, style)
    }
}

private fun bulletFor(level: Int) = when (level % 3) {
    0 -> "•"
    1 -> "◦"
    else -> "▪"
}

@Composable
private fun MdList(block: MdBlock.ListBlock, style: MdStyle) {
    val textStyle = MaterialTheme.typography.bodyLarge
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (item in block.items) {
            val text = remember(item.text, style) { inlineMarkdown(item.text, style.inline) }
            Row(Modifier.fillMaxWidth().padding(start = (item.level * 18).dp)) {
                when {
                    item.checked != null -> Icon(
                        if (item.checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = if (item.checked) "Done" else "Not done",
                        tint = if (item.checked) MaterialTheme.colorScheme.primary else style.bullet,
                        modifier = Modifier.padding(top = 2.dp).size(18.dp),
                    )
                    item.ordered -> Text(
                        item.marker,
                        style = textStyle,
                        color = style.bullet,
                        modifier = Modifier.widthIn(min = 20.dp),
                    )
                    else -> Text(
                        bulletFor(item.level),
                        style = textStyle,
                        color = style.bullet,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(14.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text,
                    style = textStyle,
                    color = if (item.checked == true) style.taskDone else Color.Unspecified,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MdTable(block: MdBlock.Table, style: MdStyle) {
    val cols = block.header.size
    if (cols == 0) return
    val cells = remember(block, style) {
        val all = ArrayList<AnnotatedString>((block.rows.size + 1) * cols)
        for (h in block.header) all += inlineMarkdown(h, style.inline)
        for (row in block.rows) for (c in 0 until cols) all += inlineMarkdown(row[c], style.inline)
        all
    }
    val rows = block.rows.size + 1
    val shape = RoundedCornerShape(8.dp)
    val textStyle = MaterialTheme.typography.bodyMedium
    val headerStyle = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.Bold) }
    Box(
        Modifier
            .horizontalScroll(rememberScrollState())
            .clip(shape)
            .border(1.dp, style.tableBorder, shape),
    ) {
        TableGrid(cols, rows) {
            for (index in cells.indices) {
                val header = index < cols
                val align = when (block.align[index % cols]) {
                    0 -> TextAlign.Center
                    1 -> TextAlign.End
                    else -> TextAlign.Start
                }
                Box(
                    Modifier
                        .then(if (header) Modifier.background(style.tableHeaderBg) else Modifier)
                        .border(0.5.dp, style.tableBorder)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        cells[index],
                        style = if (header) headerStyle else textStyle,
                        textAlign = align,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Lays out [cols] x [rows] cells (row-major): each column as wide as its widest cell (capped). */
@Composable
private fun TableGrid(cols: Int, rows: Int, content: @Composable () -> Unit) {
    Layout(content) { measurables, _ ->
        val maxCell = 280.dp.roundToPx()
        val minCell = 48.dp.roundToPx()
        val count = minOf(measurables.size, cols * rows)
        val widths = IntArray(cols)
        for (idx in 0 until count) {
            val c = idx % cols
            val w = measurables[idx].maxIntrinsicWidth(Constraints.Infinity).coerceIn(minCell, maxCell)
            if (w > widths[c]) widths[c] = w
        }
        val heights = IntArray(rows)
        for (idx in 0 until count) {
            val r = idx / cols
            val h = measurables[idx].minIntrinsicHeight(widths[idx % cols])
            if (h > heights[r]) heights[r] = h
        }
        val placeables = (0 until count).map { idx ->
            measurables[idx].measure(Constraints.fixed(widths[idx % cols], heights[idx / cols]))
        }
        layout(widths.sum(), heights.sum()) {
            var y = 0
            var idx = 0
            for (r in 0 until rows) {
                var x = 0
                for (c in 0 until cols) {
                    if (idx < count) placeables[idx].place(x, y)
                    x += widths[c]
                    idx++
                }
                y += heights[r]
            }
        }
    }
}
