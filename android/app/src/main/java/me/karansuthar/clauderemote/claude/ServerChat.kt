package me.karansuthar.clauderemote.claude

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

sealed interface ChatEvent {
    data class Thinking(val text: String) : ChatEvent
    data class Text(val text: String) : ChatEvent

    /** The server's Claude Code session id, to continue the conversation next turn. */
    data class ServerSession(val id: String) : ChatEvent

    /** A tool started; [label] is a short line for the chat. */
    data class Step(val label: String) : ChatEvent

    data object Finished : ChatEvent
}

/**
 * The single WebSocket to the Claude Code bridge. [warm] opens it ahead of a send;
 * frames sent before it opens are queued by OkHttp.
 */
class ServerLink(val url: String) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val listeners = CopyOnWriteArraySet<(JSONObject) -> Unit>()
    @Volatile private var socket: WebSocket? = null

    @Synchronized
    fun warm() {
        if (socket != null) return
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                listeners.forEach { it(frame) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = drop(webSocket, t.message ?: "connection failed")
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = drop(webSocket, "connection closed")
        })
    }

    @Synchronized
    private fun drop(webSocket: WebSocket, why: String) {
        if (socket === webSocket) socket = null
        val frame = JSONObject().put("t", "error").put("message", "Server: $why")
        listeners.forEach { it(frame) }
    }

    fun send(frame: JSONObject): Boolean {
        warm()
        return socket?.send(frame.toString()) ?: false
    }

    /** Asks the server to start this conversation's Claude Code process ahead of a send. */
    fun warmConversation(conversation: String) = send(JSONObject().put("t", "warm").put("conversation", conversation))

    fun cancel() = send(JSONObject().put("t", "cancel"))

    /** Closes the socket; used when the address in Settings changes. */
    @Synchronized
    fun close() {
        socket?.close(1000, null)
        socket = null
    }

    fun listen(listener: (JSONObject) -> Unit) = listeners.add(listener)
    fun unlisten(listener: (JSONObject) -> Unit) = listeners.remove(listener)
}

/** One turn through the bridge, as a flow of chat events. */
class ServerChat(private val link: ServerLink, private val model: String, private val effort: String) {
    fun reply(conversation: String?, content: JSONArray): Flow<ChatEvent> = callbackFlow {
        val listener: (JSONObject) -> Unit = { frame ->
            when (frame.optString("t")) {
                "session" -> trySend(ChatEvent.ServerSession(frame.getString("conversation")))
                "thinking" -> trySend(ChatEvent.Thinking(frame.getString("text")))
                "text" -> trySend(ChatEvent.Text(frame.getString("text")))
                "step" -> trySend(ChatEvent.Step(frame.optString("label").ifEmpty { frame.optString("name") }))
                "done" -> {
                    trySend(ChatEvent.Finished)
                    close()
                }
                "error" -> close(IllegalStateException(frame.optString("message", "server error")))
            }
        }
        link.listen(listener)
        val frame = JSONObject()
            .put("t", "send")
            .put("model", model)
            .put("effort", effort)
            .put("content", content)
        conversation?.let { frame.put("conversation", it) }
        if (!link.send(frame)) close(IllegalStateException("Server unreachable (is Tailscale on?)"))
        awaitClose { link.unlisten(listener) }
    }
}
