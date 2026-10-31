package dev.komichi.agent.data

import org.json.JSONArray
import java.util.UUID

data class ChatLine(val id: String = UUID.randomUUID().toString(), val role: String, val text: String,
    val state: String = "", val toolName: String = "", val createdAt: Long = System.currentTimeMillis())
data class Conversation(val id: String = UUID.randomUUID().toString(), val title: String = "新しい会話",
    val lines: List<ChatLine> = emptyList(), val input: JSONArray = JSONArray(), val accountId: String = "",
    val pinned: Boolean = false, val updatedAt: Long = System.currentTimeMillis(), val archived: Boolean = false)

class HistoryStore(private val vault: Vault) {
    fun load(): List<Conversation> = vault.read("history")?.optJSONArray("sessions")?.objects()?.map { session ->
        Conversation(session.getString("id"), session.getString("title"), session.getJSONArray("lines").objects().map {
            ChatRecovery.line(ChatLine(it.getString("id"), it.getString("role"), it.getString("text"),it.optString("state"),it.optString("tool_name"),it.optLong("created_at",0)))
        }, ChatRecovery.input(session.getJSONArray("input")), session.optString("account_id"),session.optBoolean("pinned"),session.optLong("updated_at",0),session.optBoolean("archived"))
    } ?: emptyList()
    fun save(sessions: List<Conversation>) {
        vault.write("history", obj("sessions" to array(sessions.take(30).map { session ->
            obj("id" to session.id, "title" to session.title, "account_id" to session.accountId,
                "lines" to array(session.lines.map { obj("id" to it.id, "role" to it.role, "text" to it.text,"state" to it.state,"tool_name" to it.toolName,"created_at" to it.createdAt) }), "input" to session.input,
                "pinned" to session.pinned,"updated_at" to session.updatedAt,"archived" to session.archived)
        })))
    }
}
