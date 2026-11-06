package me.karansuthar.clauderemote.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One message as shown in the chat. Claude Code keeps the real history in its session. */
data class StoredMessage(
    val fromUser: Boolean,
    val text: String,
    val steps: List<String> = emptyList(),
)

data class Conversation(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val title: String,
    val messages: List<StoredMessage>,
    /** Claude Code session id on the server, to resume the conversation. */
    val serverSession: String? = null,
)

data class ConversationSummary(val id: String, val title: String, val updatedAt: Long)

class ConversationStore(context: Context) {
    private val dir = File(context.filesDir, "conversations").apply { mkdirs() }

    fun create(): Conversation {
        val now = System.currentTimeMillis()
        return Conversation(UUID.randomUUID().toString(), now, now, "", emptyList())
    }

    fun save(conversation: Conversation) {
        val json = JSONObject()
            .put("id", conversation.id)
            .put("createdAt", conversation.createdAt)
            .put("updatedAt", conversation.updatedAt)
            .put("title", conversation.title)
            .put("serverSession", conversation.serverSession ?: JSONObject.NULL)
            .put("messages", JSONArray().apply {
                conversation.messages.forEach { m ->
                    put(JSONObject()
                        .put("fromUser", m.fromUser)
                        .put("text", m.text)
                        .put("steps", JSONArray(m.steps)))
                }
            })
        val tmp = File(dir, "${conversation.id}.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file(conversation.id))
    }

    fun load(id: String): Conversation? {
        val file = file(id).takeIf { it.exists() } ?: return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val array = json.optJSONArray("messages") ?: JSONArray()
        val messages = List(array.length()) { i ->
            val m = array.getJSONObject(i)
            val steps = m.optJSONArray("steps") ?: JSONArray()
            StoredMessage(m.getBoolean("fromUser"), m.getString("text"), List(steps.length()) { steps.getString(it) })
        }
        return Conversation(
            id = json.getString("id"),
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
            title = json.getString("title"),
            messages = messages,
            serverSession = json.optString("serverSession").takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    fun list(): List<ConversationSummary> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    ConversationSummary(json.getString("id"), json.getString("title"), json.getLong("updatedAt"))
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }

    fun delete(id: String) {
        file(id).delete()
    }

    private fun file(id: String) = File(dir, "$id.json")
}
