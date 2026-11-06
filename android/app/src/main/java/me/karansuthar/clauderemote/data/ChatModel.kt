package me.karansuthar.clauderemote.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.karansuthar.clauderemote.bridge.BridgeClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * One tool call in an assistant reply. [name] is the tool (Bash, Edit, mcp__…); [detail] is the
 * whole call (command, diff, file content, JSON or prompt) in [detailLang] ("bash", "diff",
 * "json", a file extension or ""); [output] is the tool's result, "" until [done].
 */
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
)

/** A chat line: role is "user", "assistant" or "note". */
data class ChatItem(
    val role: String,
    val text: String = "",
    val thinking: String = "",
    val steps: List<Step> = emptyList(),
)

data class ChatState(
    val items: List<ChatItem> = emptyList(),
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
) {
    val running: Boolean get() = state == "running"
}

/** A session as listed by the bridge. [key] is set only for open sessions. */
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
)

/** A model the bridge offers: [value] is what set_model takes; [efforts] may be empty. */
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
)

fun parseItem(o: JSONObject): ChatItem {
    val steps = o.optJSONArray("steps")
    return ChatItem(
        role = o.stringOrNull("role") ?: "note",
        text = o.stringOrNull("text").orEmpty(),
        thinking = o.stringOrNull("thinking").orEmpty(),
        steps = if (steps == null) emptyList() else (0 until steps.length()).mapNotNull { steps.optJSONObject(it)?.let(::parseStep) },
    )
}

/**
 * State from an attach reply: {session, items}. The bridge is the only source of truth, so this
 * replaces everything the phone had; nothing from the previous state is kept.
 */
fun snapshotState(reply: JSONObject): ChatState {
    val s = reply.optJSONObject("session")?.let(::parseSession)
    val arr = reply.optJSONArray("items")
    val items = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseItem) }
    val base = ChatState(items = items, loading = false)
    return if (s == null) base else base.withSession(s)
}

/** Applies one live event; the same rules as the bridge's applyEvent. */
fun ChatState.reduce(e: JSONObject): ChatState = when (e.optString("k")) {
    "state" -> copy(state = e.stringOrNull("state") ?: state)
    "meta" -> {
        val s = e.optJSONObject("session")
        // Newer bridges send the whole session; older ones only {sessionId}.
        if (s != null) withSession(parseSession(s))
        else copy(sessionId = e.stringOrNull("sessionId") ?: sessionId)
    }
    else -> copy(items = applyEvent(items, e))
}

private fun applyEvent(items: List<ChatItem>, e: JSONObject): List<ChatItem> {
    val last = items.lastOrNull()
    // Changes the last item if it is an assistant reply, otherwise starts a new one.
    fun withAssistant(change: (ChatItem) -> ChatItem): List<ChatItem> =
        if (last?.role == "assistant") items.dropLast(1) + change(last)
        else items + change(ChatItem("assistant"))

    val text = e.stringOrNull("text").orEmpty()
    return when (e.optString("k")) {
        "user" -> items + ChatItem("user", text)
        "text" -> withAssistant { it.copy(text = it.text + text) }
        "thinking" -> withAssistant { it.copy(thinking = it.thinking + text) }
        "sep" ->
            if (last?.role == "assistant" && last.text.isNotEmpty() && !last.text.endsWith("\n\n"))
                items.dropLast(1) + last.copy(text = last.text + "\n\n")
            else items
        "step" -> withAssistant {
            it.copy(
                steps = it.steps + Step(
                    id = e.stringOrNull("id").orEmpty(),
                    label = e.stringOrNull("label").orEmpty(),
                    sub = e.optBoolean("sub"),
                    name = e.stringOrNull("name").orEmpty(),
                    detail = e.stringOrNull("detail").orEmpty(),
                    detailLang = e.stringOrNull("detailLang").orEmpty(),
                ),
            )
        }
        "stepDone" -> {
            val id = e.stringOrNull("id")
            val error = e.optBoolean("error")
            val output = e.stringOrNull("output").orEmpty()
            val index = items.indexOfLast { item -> item.steps.any { it.id == id } }
            if (index < 0) items
            else items.toMutableList().also { list ->
                val item = list[index]
                list[index] = item.copy(
                    steps = item.steps.map { if (it.id == id) it.copy(done = true, error = error, output = output) else it },
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

    private fun update(change: (ChatState) -> ChatState) {
        _state.value = change(_state.value)
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
                val error = if (keepError) _state.value.error else null
                update { snapshotState(reply).copy(error = error) }
            } catch (e: Exception) {
                update { it.copy(loading = false, error = e.message ?: "Couldn't open the session") }
            }
        }
    }

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        scope.launch {
            try {
                client.request(
                    "send",
                    JSONObject()
                        .put("key", key)
                        .put("text", body),
                )
                update { it.copy(error = null) }
            } catch (e: Exception) {
                update { it.copy(error = e.message ?: "Couldn't send") }
                // Don't trust what the phone has after a failure: fetch a fresh snapshot.
                if (!_state.value.closed) reattach()
            }
        }
    }

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

    // Socket reader thread -> main thread.
    override fun onEvent(e: JSONObject) {
        scope.launch { update { it.reduce(e) } }
    }

    override fun onClosed() {
        scope.launch { update { it.copy(closed = true, state = "idle") } }
    }

    override fun onSnapshot(reply: JSONObject) {
        scope.launch { update { snapshotState(reply) } }
    }
}
