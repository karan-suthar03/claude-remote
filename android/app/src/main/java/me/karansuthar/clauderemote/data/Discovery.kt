package me.karansuthar.clauderemote.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.karansuthar.clauderemote.bridge.BridgeClient

/** A computer on the tailnet running the bridge that isn't saved on this phone yet. */
@Immutable
data class FoundDevice(val name: String, val address: String, val url: String, val host: String = "") {
    /** The address, with the computer's Tailscale name when the bridge goes by another name. */
    val subtitle: String get() = if (host.isNotBlank() && !host.equals(name, ignoreCase = true)) "$host · $address" else address
}

/**
 * Bridges found through the saved ones: each bridge lists the other computers on its tailnet that
 * run the bridge ("peers"). Kept in memory only; nothing is stored on the phone.
 */
class Discovery {
    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found
    private val mutex = Mutex()

    /** Asks every saved device's bridge for its peers and keeps the ones not saved yet. */
    suspend fun refresh(saved: List<Device>, client: (Device) -> BridgeClient) {
        if (saved.isEmpty()) {
            _found.value = emptyList()
            return
        }
        mutex.withLock {
            val replies = coroutineScope {
                saved.map { d ->
                    async {
                        // Older bridges don't know "peers"; an unreachable one just adds nothing.
                        runCatching { client(d).request("peers", timeoutMs = 15_000) }.getOrNull()
                    }
                }.awaitAll()
            }
            val known = saved.map { hostOf(it.url) to portOf(it.url) }.toSet()
            val byUrl = LinkedHashMap<String, FoundDevice>()
            for (reply in replies.filterNotNull()) {
                val arr = reply.optJSONArray("peers") ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = normalizeServerUrl(o.optString("url")) ?: continue
                    if ((hostOf(url) to portOf(url)) in known) continue
                    byUrl.getOrPut(url) {
                        FoundDevice(o.optString("name").ifBlank { o.optString("host") }, o.optString("address"), url, o.optString("host"))
                    }
                }
            }
            _found.value = byUrl.values.sortedBy { it.name.lowercase() }
        }
    }

    /** Drops [url] from the list (just added). */
    fun forget(url: String) {
        _found.value = _found.value.filter { it.url != url }
    }

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull()
    private fun portOf(url: String) = runCatching { java.net.URI(url).port }.getOrDefault(-1)
}
