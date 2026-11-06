package me.karansuthar.clauderemote.data

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A computer running the bridge. [url] is its WebSocket address (ws://host:8000/ws). */
@Immutable
data class Device(val id: String, val url: String, val name: String) {
    /** host:port, for display. Parsed once: it is read on every drawer and top-bar recomposition. */
    val address: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            val uri = java.net.URI(url)
            if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host
        }.getOrNull() ?: url
    }

    val displayName: String get() = name.ifBlank { address }
}

/** Saved devices, as a JSON array in SharedPreferences. */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)
    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<Device>> = _devices

    fun list(): List<Device> = _devices.value

    fun get(id: String?): Device? = id?.let { i -> _devices.value.find { it.id == i } }

    @Synchronized
    fun add(url: String, name: String): Device {
        val device = Device(UUID.randomUUID().toString(), url, name.trim())
        save(_devices.value + device)
        return device
    }

    @Synchronized
    fun remove(id: String) {
        save(_devices.value.filter { it.id != id })
        if (lastSelectedId == id) lastSelectedId = null
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val current = _devices.value
        if (current.none { it.id == id && it.name != name }) return
        save(current.map { if (it.id == id) it.copy(name = name) else it })
    }

    var lastSelectedId: String?
        get() = prefs.getString("last_selected", null)
        set(value) = prefs.edit().putString("last_selected", value).apply()

    private fun load(): List<Device> {
        val raw = prefs.getString("list", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Device(o.getString("id"), o.getString("url"), o.optString("name"))
            }
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<Device>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("url", it.url).put("name", it.name)) }
        prefs.edit().putString("list", arr.toString()).apply()
        _devices.value = list
    }
}
