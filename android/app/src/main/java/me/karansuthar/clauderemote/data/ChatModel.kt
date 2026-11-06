package me.karansuthar.clauderemote.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.karansuthar.clauderemote.bridge.BridgeClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * An image in a message or a tool result: [data] is base64 (no data: prefix) of [mediaType]
 * (image/jpeg, png, gif or webp). Kept encoded; the UI decodes it only when it is shown.
 */
@Immutable
data class ChatImage(
    val mediaType: String,
    /** Base64 data; empty when the image came in a snapshot and must be fetched by [ref]. */
    val data: String,
    /** Set for images in snapshots: fetch the data with the bridge's "image" request. */
    val ref: String? = null,
) {
    /** A cheap content key (length plus a hash of samples), for caching decoded bitmaps. */
    val cacheKey: String get() = if (data.isEmpty() && ref != null) "ref:$ref" else imageKey(data)

    // The default would print megabytes of base64.
    override fun toString() = "ChatImage($mediaType, ${data.length} chars)"
}

private fun imageKey(data: String): String {
    val n = data.length
    var h = n.toLong()
    fun mix(from: Int, to: Int) {
        for (i in from until to) h = h * 31 + data[i].code
    }
    if (n <= 12_288) {
        mix(0, n)
    } else {
        mix(0, 4_096)
        val mid = n / 2 - 2_048
        mix(mid, mid + 4_096)
        mix(n - 4_096, n)
    }
    return n.toString() + ":" + java.lang.Long.toHexString(h)
}

/** Parses `images: [{mediaType, data}]`; anything malformed is skipped. */
fun parseImages(arr: JSONArray?): List<ChatImage> {
    if (arr == null || arr.length() == 0) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val data = o.stringOrNull("data").orEmpty()
        val ref = o.stringOrNull("ref")?.takeIf { it.isNotEmpty() }
        if (data.isEmpty() && ref == null) return@mapNotNull null
        ChatImage(o.stringOrNull("mediaType")?.takeIf { it.isNotEmpty() } ?: "image/png", data, ref)
    }
}

/**
 * One tool call in an assistant reply. [name] is the tool (Bash, Edit, mcp__…); [detail] is the
 * whole call (command, diff, file content, JSON or prompt) in [detailLang] ("bash", "diff",
 * "json", a file extension or ""); [output] is the tool's result, "" until [done].
 */
@Immutable
data class Step(
    val id: String,
    val label: String,
    val sub: Boolean = false,
    val done: Boolean = false,
    val error: Boolean = false,
    val name: String = "",
    val detail: String = "",
    val detailLang: String = "",
    val output: String = "",
    /** Images in the tool's result (e.g. Read on a PNG). */
    val images: List<ChatImage> = emptyList(),
)

/**
 * One entry of the chat, in the order things happened (like Claude Code's own transcript):
 * role is "user", "assistant" (reply text), "thinking", "step" (one tool call, in [step]) or
 * "note". Never mutated (every change is a copy), so @Immutable lets Compose skip unchanged rows
 * by equals() while a reply streams.
 */
@Immutable
data class ChatItem(
    val role: String,
    val text: String = "",
    val step: Step? = null,
    /** Images attached to a user message. */
    val images: List<ChatImage> = emptyList(),
)

@Immutable
data class ChatState(
    val items: List<ChatItem> = emptyList(),
    /** Index of items[0] in the whole chat: this many earlier items can be loaded on request. */
    val start: Int = 0,
    /** "running" or "idle". */
    val state: String = "idle",
    val title: String = "",
    val cwd: String = "",
    val sessionId: String? = null,
    val error: String? = null,
    val loading: Boolean = true,
    /** The session ended (closed here or elsewhere). */
    val closed: Boolean = false,
    /** The session's model and effort as the bridge reports them; null = not known/bridge default. */
    val model: String? = null,
    val effort: String? = null,
    val costUsd: Double = 0.0,
    val contextTokens: Int = 0,
    /** Background tasks running right now. */
    val background: Int = 0,
    val tasks: List<BgTask> = emptyList(),
    /** "bridge", or "terminal": a Claude Code session running in a terminal on the computer, mirrored live. */
    val origin: String = ORIGIN_BRIDGE,
    /** When the current turn started, on this phone's clock (0 = not running). */
    val turnStartedAt: Long = 0,
    /** Output tokens of the current turn so far. */
    val turnTokens: Int = 0,
) {
    val running: Boolean get() = state == "running"
    val isTerminal: Boolean get() = origin == ORIGIN_TERMINAL
}

/** A background task of a session: a backgrounded command, a background subagent, a monitor. */
@Immutable
data class BgTask(val id: String, val type: String, val description: String, val agent: String?) {
    val isAgent: Boolean get() = agent != null || type.contains("agent")
}

private fun parseTasks(arr: JSONArray?): List<BgTask> =
    if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.stringOrNull("id") ?: return@mapNotNull null
        BgTask(id, o.stringOrNull("type").orEmpty(), o.stringOrNull("description").orEmpty(), o.stringOrNull("agent"))
    }

const val ORIGIN_BRIDGE = "bridge"
const val ORIGIN_TERMINAL = "terminal"

/** A session as listed by the bridge. [key] is set only for open sessions. */
@Immutable
data class SessionInfo(
    val key: String?,
    val sessionId: String?,
    val cwd: String,
    val title: String,
    val state: String,
    val lastActive: Long,
    val model: String? = null,
    val effort: String? = null,
    val costUsd: Double = 0.0,
    val contextTokens: Int = 0,
    val background: Int = 0,
    val tasks: List<BgTask> = emptyList(),
    /** "bridge" or "terminal" (see [ChatState.origin]). */
    val origin: String = ORIGIN_BRIDGE,
    /** How long the current turn had run when the bridge sent this, and its output tokens. */
    val turnMs: Long = 0,
    val turnTokens: Int = 0,
) {
    val running: Boolean get() = state == "running"
}

/** org.json gives "null" for a JSON null through optString; this gives null. */
fun JSONObject.stringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name) else null

fun parseSession(o: JSONObject) = SessionInfo(
    key = o.stringOrNull("key"),
    sessionId = o.stringOrNull("sessionId"),
    cwd = o.stringOrNull("cwd").orEmpty(),
    title = o.stringOrNull("title").orEmpty(),
    state = o.stringOrNull("state") ?: "idle",
    lastActive = o.optLong("lastActive"),
    model = o.stringOrNull("model")?.takeIf { it.isNotEmpty() },
    effort = o.stringOrNull("effort")?.takeIf { it.isNotEmpty() },
    costUsd = o.optDouble("costUsd", 0.0).takeIf { !it.isNaN() } ?: 0.0,
    contextTokens = o.optInt("contextTokens"),
    background = o.optInt("background"),
    tasks = parseTasks(o.optJSONArray("tasks")),
    origin = o.stringOrNull("origin")?.takeIf { it.isNotEmpty() } ?: ORIGIN_BRIDGE,
    turnMs = o.optLong("turnMs"),
    turnTokens = o.optInt("turnTokens"),
)

/** A model the bridge offers: [value] is what set_model takes; [efforts] may be empty. */
@Immutable
data class ModelInfo(
    val value: String,
    val displayName: String,
    val description: String,
    val efforts: List<String>,
)

fun parseModels(reply: JSONObject): List<ModelInfo> {
    val arr = reply.optJSONArray("models") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val value = o.stringOrNull("value") ?: return@mapNotNull null
        val efforts = o.optJSONArray("efforts")
        ModelInfo(
            value = value,
            displayName = o.stringOrNull("displayName")?.takeIf { it.isNotBlank() } ?: value,
            description = o.stringOrNull("description").orEmpty(),
            efforts = if (efforts == null) emptyList()
            else (0 until efforts.length()).mapNotNull { j -> efforts.optString(j).takeIf { it.isNotEmpty() } },
        )
    }
}

/** Replaces every session-level field with the bridge's session object. */
fun ChatState.withSession(s: SessionInfo) = copy(
    state = s.state,
    title = s.title,
    cwd = s.cwd,
    sessionId = s.sessionId,
    model = s.model,
    effort = s.effort,
    costUsd = s.costUsd,
    contextTokens = s.contextTokens,
    background = s.background,
    tasks = s.tasks,
    origin = s.origin,
    // The bridge's elapsed time, turned into a start on this phone's clock (the clocks may differ).
    turnStartedAt = when {
        !s.running -> 0
        s.turnMs > 0 -> System.currentTimeMillis() - s.turnMs
        turnStartedAt > 0 -> turnStartedAt
        else -> System.currentTimeMillis()
    },
    turnTokens = if (s.running) s.turnTokens else 0,
)

fun parseSessions(arr: JSONArray?): List<SessionInfo> =
    if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseSession) }

private fun parseStep(o: JSONObject) = Step(
    id = o.stringOrNull("id").orEmpty(),
    label = o.stringOrNull("label").orEmpty(),
    sub = o.optBoolean("sub"),
    done = o.optBoolean("done"),
    error = o.optBoolean("error"),
    name = o.stringOrNull("name").orEmpty(),
    detail = o.stringOrNull("detail").orEmpty(),
    detailLang = o.stringOrNull("detailLang").orEmpty(),
    output = o.stringOrNull("output").orEmpty(),
    images = parseImages(o.optJSONArray("images")),
)

fun parseItem(o: JSONObject): ChatItem {
    val role = o.stringOrNull("role") ?: "note"
    return ChatItem(
        role = role,
        text = o.stringOrNull("text").orEmpty(),
        step = if (role == "step") parseStep(o) else null,
        images = if (role == "step") emptyList() else parseImages(o.optJSONArray("images")),
    )
}

fun parseItems(arr: JSONArray?): List<ChatItem> =
    if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseItem) }

/**
 * State from an attach reply: {session, items}. The bridge is the only source of truth, so this
 * replaces everything the phone had; nothing from the previous state is kept.
 */
fun snapshotState(reply: JSONObject): ChatState {
    val s = reply.optJSONObject("session")?.let(::parseSession)
    val items = parseItems(reply.optJSONArray("items"))
    val base = ChatState(items = items, start = reply.optInt("start", 0), loading = false)
    return if (s == null) base else base.withSession(s)
}

/** Applies one live event; the same rules as the bridge's applyEvent. */
fun ChatState.reduce(e: JSONObject): ChatState = when (e.optString("k")) {
    "state" -> {
        val next = e.stringOrNull("state") ?: state
        when {
            next == "running" && !running -> copy(state = next, turnStartedAt = System.currentTimeMillis(), turnTokens = 0)
            next != "running" -> copy(state = next, turnStartedAt = 0, turnTokens = 0)
            else -> copy(state = next)
        }
    }
    "meta" -> {
        val s = e.optJSONObject("session")
        // Newer bridges send the whole session; older ones only {sessionId}.
        if (s != null) withSession(parseSession(s))
        else copy(sessionId = e.stringOrNull("sessionId") ?: sessionId)
    }
    else -> copy(items = applyEvent(items, e))
}

/** A copy with the last element replaced: one array copy (dropLast + plus made two per text delta). */
private fun <T> List<T>.withLast(value: T): List<T> {
    val out = ArrayList<T>(this)
    out[out.lastIndex] = value
    return out
}

private fun applyEvent(items: List<ChatItem>, e: JSONObject): List<ChatItem> {
    val last = items.lastOrNull()
    val text = e.stringOrNull("text").orEmpty()
    return when (e.optString("k")) {
        "user" -> items + ChatItem("user", text, images = parseImages(e.optJSONArray("images")))
        // Text and thinking extend the item before them only if it is the same kind; a tool step
        // in between starts a new one, so the chat keeps Claude Code's order.
        "text" -> if (last?.role == "assistant") items.withLast(last.copy(text = last.text + text)) else items + ChatItem("assistant", text)
        "thinking" -> if (last?.role == "thinking") items.withLast(last.copy(text = last.text + text)) else items + ChatItem("thinking", text)
        "sep" ->
            if (last?.role == "assistant" && last.text.isNotEmpty() && !last.text.endsWith("\n\n"))
                items.withLast(last.copy(text = last.text + "\n\n"))
            else items
        "step" -> items + ChatItem(
            "step",
            step = Step(
                id = e.stringOrNull("id").orEmpty(),
                label = e.stringOrNull("label").orEmpty(),
                sub = e.optBoolean("sub"),
                name = e.stringOrNull("name").orEmpty(),
                detail = e.stringOrNull("detail").orEmpty(),
                detailLang = e.stringOrNull("detailLang").orEmpty(),
            ),
        )
        "stepDone" -> {
            val id = e.stringOrNull("id")
            val index = items.indexOfLast { it.step?.id == id }
            if (index < 0) items   // its step is in an earlier page that isn't loaded
            else items.toMutableList().also { list ->
                val item = list[index]
                list[index] = item.copy(
                    step = item.step!!.copy(
                        done = true,
                        error = e.optBoolean("error"),
                        output = e.stringOrNull("output").orEmpty(),
                        images = parseImages(e.optJSONArray("images")),
                    ),
                )
            }
        }
        "note" -> items + ChatItem("note", text)
        else -> items
    }
}

/**
 * The phone's in-memory view of one open session, alive only while its chat is shown. The
 * bridge is the only source of truth: every attach (and re-attach after a reconnect) replaces
 * the state with its snapshot, and nothing is applied optimistically (a sent message appears
 * when the bridge echoes it as a `user` event). All state changes happen on the main thread:
 * frames from the socket are posted there in arrival order, after the attach snapshot.
 */
class ChatSession(
    private val client: BridgeClient,
    val key: String,
) : BridgeClient.SessionListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    /** The up-to-date state (main thread only); [state] may lag it by up to [STREAM_PUBLISH_MS]. */
    private var current = ChatState()
    private var publishJob: Job? = null

    /** Applies [change] and publishes it at once (user actions, attach, errors). */
    private fun update(change: (ChatState) -> ChatState) {
        current = change(current)
        publishJob?.cancel()
        publishJob = null
        _state.value = current
    }

    /**
     * Applies a streamed event now (so events keep their exact order relative to snapshots) but
     * publishes at most once per [STREAM_PUBLISH_MS]. Text deltas can arrive 100+ times a second;
     * without this every one of them would recompose the chat screen.
     */
    private fun updateStreamed(change: (ChatState) -> ChatState) {
        current = change(current)
        if (publishJob == null) {
            publishJob = scope.launch {
                delay(STREAM_PUBLISH_MS)
                publishJob = null
                _state.value = current
            }
        }
    }

    /** Attaches and replaces all state with the bridge's snapshot. */
    fun attach() {
        update { it.copy(loading = true, error = null) }
        reattach(keepError = false)
    }

    private fun reattach(keepError: Boolean = true) {
        scope.launch {
            try {
                // Resumes on Main before any later event is posted there, so events apply on top.
                val reply = client.attach(key, this@ChatSession)
                val error = if (keepError) current.error else null
                update { snapshotState(reply).copy(error = error) }
            } catch (e: Exception) {
                update { it.copy(loading = false, error = e.message ?: "Couldn't open the session") }
            }
        }
    }

    /**
     * Sends a message with optional [images] (text may then be empty). [onResult] is called on the
     * main thread with whether the bridge accepted it (not called if there was nothing to send).
     */
    fun send(text: String, images: List<ChatImage> = emptyList(), onResult: (Boolean) -> Unit = {}) {
        val body = text.trim()
        if (body.isEmpty() && images.isEmpty()) return
        scope.launch {
            val ok = try {
                val fields = JSONObject()
                    .put("key", key)
                    .put("text", body)
                if (images.isEmpty()) {
                    client.request("send", fields)
                } else {
                    val arr = JSONArray()
                    for (image in images) arr.put(JSONObject().put("mediaType", image.mediaType).put("data", image.data))
                    fields.put("images", arr)
                    // Megabytes of base64: serialised off the main thread, with time to upload.
                    client.request("send", fields, timeoutMs = SEND_IMAGES_TIMEOUT_MS, heavy = true)
                }
                update { it.copy(error = null) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(error = e.message ?: "Couldn't send") }
                // Don't trust what the phone has after a failure: fetch a fresh snapshot.
                if (!current.closed) reattach()
                false
            }
            onResult(ok)
        }
    }

    private var loadingEarlier = false

    /**
     * Loads the page of items before the first one shown (long chats open with only the latest
     * ones). Prepends them; live events keep appending at the end as before.
     */
    fun loadEarlier() {
        val before = current.start
        if (before <= 0 || loadingEarlier) return
        loadingEarlier = true
        scope.launch {
            try {
                val reply = client.request("history", JSONObject().put("key", key).put("before", before), heavy = true)
                val older = parseItems(reply.optJSONArray("items"))
                val start = reply.optInt("start", 0)
                // Only if nothing replaced the state meanwhile (a reconnect brings a new snapshot).
                if (current.start == before) update { it.copy(items = older + it.items, start = start) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(error = e.message ?: "Couldn't load earlier messages") }
            } finally {
                loadingEarlier = false
            }
        }
    }

    /** The base64 data of an image sent by reference in a snapshot. Throws on failure. */
    suspend fun fetchImage(ref: String): String =
        client.request("image", JSONObject().put("key", key).put("ref", ref), heavy = true).optString("data")

    /** The models the bridge offers. Throws on failure. */
    suspend fun models(): List<ModelInfo> = parseModels(client.request("models"))

    /**
     * Changes this session's model and/or effort (null = leave as is) and applies the session
     * the bridge returns. Throws on failure. Call on the main thread.
     */
    suspend fun setModel(model: String?, effort: String?) {
        val fields = JSONObject().put("key", key)
        if (model != null) fields.put("model", model)
        if (effort != null) fields.put("effort", effort)
        val reply = client.request("set_model", fields)
        reply.optJSONObject("session")?.let { s -> update { it.withSession(parseSession(s)) } }
    }

    fun interrupt() = client.post("interrupt", JSONObject().put("key", key))

    /**
     * Like Ctrl+B: moves running commands and subagents to the background, or only the one started by
     * the step [toolUseId]. False if nothing was running in the foreground.
     */
    /** The end (last 8 KB) of a background command's output: {output, totalBytes, truncated}. */
    suspend fun taskOutput(taskId: String): JSONObject =
        client.request("task_output", JSONObject().put("key", key).put("taskId", taskId))

    suspend fun stopTask(taskId: String) {
        client.request("stop_task", JSONObject().put("key", key).put("taskId", taskId))
    }

    suspend fun background(toolUseId: String? = null): Boolean {
        val fields = JSONObject().put("key", key)
        if (toolUseId != null) fields.put("toolUseId", toolUseId)
        return client.request("background", fields).optBoolean("ok")
    }

    /** Ends the Claude Code process; the session becomes a closed one. */
    fun close() {
        detach()
        client.post("close", JSONObject().put("key", key))
    }

    /** Stops receiving events. The session keeps running on the computer. */
    fun detach() {
        client.detach(key, this)
        scope.cancel()
    }

    fun clearError() = update { it.copy(error = null) }

    // Socket reader thread -> main thread. Each frame is still posted on its own (cheap) so the
    // main thread applies frames in arrival order; only publishing to the UI is coalesced.
    override fun onEvent(e: JSONObject) {
        scope.launch { updateStreamed { it.reduce(e) } }
    }

    override fun onClosed() {
        scope.launch { update { it.copy(closed = true, state = "idle") } }
    }

    override fun onSnapshot(reply: JSONObject) {
        // A snapshot can hold a long history: build it here on the socket's reader thread and only
        // swap it in on the main thread (posting at this point keeps the frame order).
        val snapshot = snapshotState(reply)
        scope.launch { update { snapshot } }
    }

    private companion object {
        /** About two frames: smooth streaming text, at most ~30 UI updates a second. */
        const val STREAM_PUBLISH_MS = 32L
        const val SEND_IMAGES_TIMEOUT_MS = 120_000L
    }
}
