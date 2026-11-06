package me.karansuthar.clauderemote.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One WebSocket to one computer's bridge. Connects on the first request and again on the
 * next request after a failure. Requests are matched to replies by `id`; pushed session
 * frames go to the [SessionListener] registered for their key.
 */
class BridgeClient(val url: String) {

    /** Receives a session's pushed frames. Called on OkHttp's reader thread, in arrival order. */
    interface SessionListener {
        fun onEvent(e: JSONObject)
        fun onClosed()
        /** A fresh attach reply after a reconnect: replaces everything known so far. */
        fun onSnapshot(reply: JSONObject)
    }

    private class Conn(val ws: WebSocket, val ready: CompletableDeferred<Unit>)

    private val lock = Any()
    @Volatile private var conn: Conn? = null
    private val nextId = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val listeners = ConcurrentHashMap<String, SessionListener>()
    /** Keys whose attach succeeded; re-attached after every reconnect. */
    private val live = ConcurrentHashMap.newKeySet<String>()
    /** Request id -> key, for attaches sent by a reconnect (handled on the reader thread). */
    private val reattaching = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val unreachable get() = "Can't reach $url. Is Tailscale on and the bridge running?"

    /**
     * Sends {t, id, ...fields} and returns the reply. Throws on an error reply, failure or timeout.
     * [heavy]: the request is large (e.g. images), so it is serialised off the calling thread.
     */
    suspend fun request(
        t: String,
        fields: JSONObject = JSONObject(),
        timeoutMs: Long = TIMEOUT_MS,
        heavy: Boolean = false,
    ): JSONObject {
        val id = "a${nextId.incrementAndGet()}"
        val msg = if (heavy) withContext(Dispatchers.Default) { frameOf(t, id, fields) } else frameOf(t, id, fields)
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        val frame = try {
            withTimeout(timeoutMs) {
                val c = connect()
                c.ready.await()
                if (!c.ws.send(msg)) throw IOException(unreachable)
                reply.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("The bridge at $url didn't answer in time.")
        } finally {
            pending.remove(id)
        }
        if (frame.optString("t") == "error") throw Exception(frame.optString("message", "Bridge error"))
        return frame
    }

    /** {t, id, ...fields} as text; a shallow copy, so [fields] itself is left as it was. */
    private fun frameOf(t: String, id: String, fields: JSONObject): String {
        val names = fields.names()
        val copy = if (names == null) JSONObject() else JSONObject(fields, Array(names.length()) { names.getString(it) })
        return copy.put("t", t).put("id", id).toString()
    }

    /** Fire-and-forget request (errors ignored), outliving the caller's scope. */
    fun post(t: String, fields: JSONObject = JSONObject()) {
        scope.launch { runCatching { request(t, fields) } }
    }

    /** Attaches to a session: [listener] gets its live frames from now on, and again after reconnects. */
    suspend fun attach(key: String, listener: SessionListener): JSONObject {
        listeners[key] = listener
        try {
            val reply = request("attach", JSONObject().put("key", key))
            live.add(key)
            return reply
        } catch (e: Exception) {
            listeners.remove(key, listener)
            throw e
        }
    }

    fun detach(key: String, listener: SessionListener) {
        if (listeners.remove(key, listener)) {
            live.remove(key)
            if (conn != null) post("detach", JSONObject().put("key", key))
        }
    }

    /** Reconnects now if the socket is down and a session is attached (e.g. when the app resumes). */
    fun wake() {
        if (conn == null && live.isNotEmpty()) scope.launch { runCatching { connect().ready.await() } }
    }

    fun shutdown() {
        listeners.clear()
        live.clear()
        synchronized(lock) {
            conn?.ws?.close(1000, null)
            conn = null
        }
        _connected.value = false
        scope.cancel()
    }

    private fun connect(): Conn = synchronized(lock) {
        conn?.let { return it }
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Bad address: $url")
        }
        val ready = CompletableDeferred<Unit>()
        val ws = http.newWebSocket(request, Listener(ready))
        Conn(ws, ready).also { conn = it }
    }

    private inner class Listener(private val ready: CompletableDeferred<Unit>) : WebSocketListener() {
        private fun isCurrent(ws: WebSocket) = conn?.ws === ws

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket)) return
            _connected.value = true
            ready.complete(Unit)
            // Sessions attached over an earlier socket: attach again to resume their events and
            // get a snapshot covering whatever was missed while disconnected.
            for (key in live) {
                val id = "r${nextId.incrementAndGet()}"
                reattaching[id] = key
                webSocket.send(JSONObject().put("t", "attach").put("id", id).put("key", key).toString())
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
            val id = if (frame.has("id") && !frame.isNull("id")) frame.optString("id") else null
            if (id != null) {
                reattaching.remove(id)?.let { key ->
                    val l = listeners[key] ?: return
                    if (frame.optString("t") == "error") {
                        live.remove(key)
                        listeners.remove(key, l)
                        l.onClosed()
                    } else {
                        l.onSnapshot(frame)
                    }
                    return
                }
                pending[id]?.let { it.complete(frame); return }
            }
            when (frame.optString("t")) {
                "ev" -> {
                    val e = frame.optJSONObject("e") ?: return
                    listeners[frame.optString("key")]?.onEvent(e)
                }
                "closed" -> {
                    val key = frame.optString("key")
                    live.remove(key)
                    listeners.remove(key)?.onClosed()
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            dropped(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)

        private fun dropped(webSocket: WebSocket) {
            synchronized(lock) {
                if (!isCurrent(webSocket)) return
                conn = null
            }
            _connected.value = false
            val error = IOException(unreachable)
            ready.completeExceptionally(error)
            reattaching.clear()
            // Requests in flight went over this socket; their replies will never come.
            pending.values.forEach { it.completeExceptionally(error) }
            // Keep trying while a chat is open, so it picks up again by itself.
            if (live.isNotEmpty()) scope.launch {
                delay(RETRY_MS)
                if (conn == null && live.isNotEmpty()) runCatching { connect().ready.await() }
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 20_000L
        private const val RETRY_MS = 3_000L

        private val http: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
