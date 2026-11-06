package me.karansuthar.clauderemote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.karansuthar.clauderemote.claude.ChatEvent
import me.karansuthar.clauderemote.claude.ServerChat
import me.karansuthar.clauderemote.data.Conversation
import me.karansuthar.clauderemote.data.StoredMessage
import org.json.JSONArray
import org.json.JSONObject

data class UiMessage(
    val fromUser: Boolean,
    val text: String,
    val thinking: String = "",
    val streaming: Boolean = false,
    val note: String? = null,
    /** Tools run for this reply, as short lines ("Editing src/main.kt"). */
    val steps: List<String> = emptyList(),
)

data class ChatState(
    val conversationId: String? = null,
    val messages: List<UiMessage> = emptyList(),
    val busy: Boolean = false,
)

/** One conversation's state and the send loop. */
class ChatController(private val app: App) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    private var conversation: Conversation? = null
    private var sending: Job? = null

    fun newConversation() {
        sending?.cancel()
        conversation = null
        _state.value = ChatState()
    }

    fun open(id: String) {
        sending?.cancel()
        val loaded = app.conversations.load(id) ?: return newConversation()
        conversation = loaded
        _state.value = ChatState(loaded.id, loaded.messages.map { UiMessage(it.fromUser, it.text, steps = it.steps) })
        loaded.serverSession?.let { app.server()?.warmConversation(it) }
    }

    /** Stops the reply running now. */
    fun stop() {
        if (_state.value.busy) app.server()?.cancel()
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.busy) return
        val link = app.server()

        val base = conversation ?: app.conversations.create()
        var current = base.copy(
            title = base.title.ifEmpty { prompt.take(60) },
            messages = base.messages + StoredMessage(fromUser = true, text = prompt),
        )
        conversation = current
        _state.update {
            it.copy(
                conversationId = current.id,
                busy = true,
                messages = it.messages +
                    UiMessage(fromUser = true, text = prompt) +
                    UiMessage(fromUser = false, text = "", streaming = true),
            )
        }

        sending = scope.launch {
            val reply = StringBuilder()
            val steps = mutableListOf<String>()
            val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
            try {
                if (link == null) error("No bridge address set. Add it in Settings.")
                ServerChat(link, app.settings.model.alias, app.settings.effort.name.lowercase())
                    .reply(current.serverSession, content)
                    .collect { event ->
                        when (event) {
                            is ChatEvent.Thinking -> updateLast { it.copy(thinking = it.thinking + event.text) }
                            is ChatEvent.Text -> {
                                reply.append(event.text)
                                updateLast { it.copy(text = it.text + event.text) }
                            }
                            is ChatEvent.Step -> {
                                steps += event.label
                                updateLast { it.copy(steps = it.steps + event.label) }
                            }
                            is ChatEvent.ServerSession -> {
                                current = current.copy(serverSession = event.id)
                                conversation = current
                            }
                            ChatEvent.Finished -> updateLast { it.copy(streaming = false) }
                        }
                    }
            } catch (e: Exception) {
                updateLast { it.copy(streaming = false, note = friendlyError(e)) }
            } finally {
                if (reply.isNotEmpty() || steps.isNotEmpty()) {
                    current = current.copy(
                        messages = current.messages + StoredMessage(fromUser = false, text = reply.toString(), steps = steps.toList()),
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                conversation = current
                save(current)
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun close() = scope.cancel()

    private fun friendlyError(e: Throwable): String {
        val raw = e.message.orEmpty()
        return when {
            raw.startsWith("No bridge address") -> raw
            e is java.io.IOException || raw.contains("unreachable") || raw.startsWith("Server:") ->
                "Can't reach the bridge at ${app.settings.serverUrl}. Is Tailscale on here and on that computer, and is the bridge running?"
            raw.contains("exited before answering") ->
                "Claude Code stopped unexpectedly. Try again; if it keeps happening, check its login."
            else -> "Something went wrong: ${raw.ifEmpty { e.javaClass.simpleName }}"
        }
    }

    private suspend fun save(c: Conversation) = withContext(Dispatchers.IO) { app.conversations.save(c) }

    private fun updateLast(change: (UiMessage) -> UiMessage) = _state.update { s ->
        s.copy(messages = s.messages.dropLast(1) + change(s.messages.last()))
    }
}
