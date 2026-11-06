package me.karansuthar.clauderemote.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Model for new sessions, as a `models` value; null = the bridge's default. */
    var defaultModel: String?
        get() = prefs.getString("defaultModel", null)
        set(value) = prefs.edit().putString("defaultModel", value).apply()

    /** Effort for new sessions; null = the bridge's default. */
    var defaultEffort: String?
        get() = prefs.getString("defaultEffort", null)
        set(value) = prefs.edit().putString("defaultEffort", value).apply()

    private val _alwaysExpandSteps = MutableStateFlow(prefs.getBoolean(KEY_ALWAYS_EXPAND_STEPS, true))

    /** Show every tool call's input and output expanded (the user can still collapse each one). */
    val alwaysExpandStepsFlow: StateFlow<Boolean> = _alwaysExpandSteps

    var alwaysExpandSteps: Boolean
        get() = _alwaysExpandSteps.value
        set(value) {
            prefs.edit().putBoolean(KEY_ALWAYS_EXPAND_STEPS, value).apply()
            _alwaysExpandSteps.value = value
        }

    private companion object {
        const val KEY_ALWAYS_EXPAND_STEPS = "always_expand_steps"
    }
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
