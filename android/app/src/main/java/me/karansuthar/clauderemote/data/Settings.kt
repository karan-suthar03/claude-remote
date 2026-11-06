package me.karansuthar.clauderemote.data

import android.content.Context

enum class ModelOption(val label: String) {
    HAIKU("Haiku"),
    SONNET("Sonnet"),
    OPUS("Opus");

    /** Claude Code's model alias for this model. */
    val alias: String get() = name.lowercase()
}

enum class EffortOption(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The bridge's WebSocket address, entered by the user (e.g. ws://<tailscale-ip>:8000/ws). */
    var serverUrl: String
        get() = prefs.getString("server_url", null).orEmpty()
        set(value) = prefs.edit().putString("server_url", value.trim()).apply()

    var model: ModelOption
        get() = prefs.getString("model", null)?.let { name -> ModelOption.entries.find { it.name == name } } ?: ModelOption.SONNET
        set(value) = prefs.edit().putString("model", value.name).apply()

    var effort: EffortOption
        get() = prefs.getString("effort", null)?.let { name -> EffortOption.entries.find { it.name == name } } ?: EffortOption.MEDIUM
        set(value) = prefs.edit().putString("effort", value.name).apply()
}

/** Accepts "host", "host:port", "ws://host:port" or a full ws:// URL; null if it can't be one. */
fun normalizeServerUrl(input: String): String? {
    var s = input.trim()
    if (s.isEmpty()) return null
    if (!s.startsWith("ws://") && !s.startsWith("wss://")) s = "ws://$s"
    val uri = runCatching { java.net.URI(s) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    val port = if (uri.port > 0) ":${uri.port}" else if (uri.scheme == "ws") ":8000" else ""
    val path = uri.path.takeIf { !it.isNullOrEmpty() && it != "/" } ?: "/ws"
    return "${uri.scheme}://$host$port$path"
}
