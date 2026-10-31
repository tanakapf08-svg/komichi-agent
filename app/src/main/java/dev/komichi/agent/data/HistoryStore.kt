package dev.komichi.agent.data

import org.json.JSONArray
import java.util.UUID

data class ChatLine(val id: String = UUID.randomUUID().toString(), val role: String, val text: String)
data class Conversation(val id: String = UUID.randomUUID().toString(), val title: String = "新しい会話",
    val lines: List<ChatLine> = emptyList(), val input: JSONArray = JSONArray(), val accountId: String = "")

class HistoryStore(private val vault: Vault) {
    fun load(): List<Conversation> = vault.read("history")?.optJSONArray("sessions")?.objects()?.map { session ->
        Conversation(session.getString("id"), session.getString("title"), session.getJSONArray("lines").objects().map {
            ChatLine(it.getString("id"), it.getString("role"), it.getString("text"))
        }, session.getJSONArray("input"), session.optString("account_id"))
    } ?: emptyList()
    fun save(sessions: List<Conversation>) {
        vault.write("history", obj("sessions" to array(sessions.take(30).map { session ->
            obj("id" to session.id, "title" to session.title, "account_id" to session.accountId,
                "lines" to array(session.lines.map { obj("id" to it.id, "role" to it.role, "text" to it.text) }), "input" to session.input)
        })))
    }
}
