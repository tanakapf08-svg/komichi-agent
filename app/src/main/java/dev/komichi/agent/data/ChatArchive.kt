package dev.komichi.agent.data

import org.json.JSONObject

/** Export is explicit plaintext; credentials and provider tool-call history are excluded. */
object ChatArchive {
    fun json(conversation: Conversation): String = obj("schema" to "komichi-chat","version" to 1,"title" to conversation.title,
        "lines" to array(conversation.lines.map { obj("role" to it.role,"text" to it.text) })).toString(2)
    fun markdown(conversation: Conversation): String = "# ${conversation.title}\n\n" + conversation.lines.joinToString("\n\n") {
        "## ${when(it.role){ "user" -> "あなた"; "assistant" -> "Komichi"; else -> "端末内の記録" }}\n\n${it.text}"
    }
    fun restore(raw: String): Conversation {
        require(raw.length <= 256_000) { "履歴ファイルが大きすぎます" }
        val root = JSONObject(raw)
        require(root.getString("schema") == "komichi-chat" && root.getInt("version") == 1) { "Komichiの履歴JSON（version 1）を指定してください" }
        val title = root.getString("title"); require(title.length in 1..120)
        val lines = root.getJSONArray("lines"); require(lines.length() <= 300)
        val parsed = lines.objects().map {
            val role = it.getString("role"); val text = it.getString("text")
            require(role in setOf("user","assistant","tool","local","notice") && text.length <= 64_000)
            ChatLine(role = role,text = text)
        }
        require(parsed.sumOf { it.text.length } <= 200_000)
        // Imported records are for viewing. Never import callable tool IDs or an account binding.
        return Conversation(title = title,lines = parsed,archived = true)
    }
}
