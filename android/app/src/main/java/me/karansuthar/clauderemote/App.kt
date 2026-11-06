package me.karansuthar.clauderemote

import android.app.Application
import me.karansuthar.clauderemote.claude.ServerLink
import me.karansuthar.clauderemote.data.ConversationStore
import me.karansuthar.clauderemote.data.Settings

class App : Application() {
    val conversations by lazy { ConversationStore(this) }
    val settings by lazy { Settings(this) }

    private var link: ServerLink? = null

    /** The connection to the bridge set in Settings; null until an address is entered. */
    @Synchronized
    fun server(): ServerLink? {
        val url = settings.serverUrl.ifEmpty { return null }
        link?.takeIf { it.url == url }?.let { return it }
        link?.close()
        return ServerLink(url).also { link = it }
    }
}
