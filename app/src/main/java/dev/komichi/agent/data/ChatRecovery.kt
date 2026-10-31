package dev.komichi.agent.data

import org.json.JSONArray

/** A process restart never replays an old tool call or leaves a forever-running UI card. */
object ChatRecovery {
    fun input(source: JSONArray): JSONArray {
        val result = JSONArray(source.toString())
        val completed = source.objects().filter { it.optString("type") == "function_call_output" }.map { it.optString("call_id") }.toSet()
        source.objects().filter { it.optString("type") == "function_call" && it.optString("call_id").isNotBlank() && it.optString("call_id") !in completed }.distinctBy { it.getString("call_id") }.forEach {
            result.put(obj("type" to "function_call_output","call_id" to it.getString("call_id"),"output" to obj("status" to "result_unknown_after_restart","note" to "Previous process ended. Do not resume or retry prior actions without a new explicit user request. Read fresh state first.").toString()))
        }
        return result
    }
    fun line(line: ChatLine): ChatLine = if(line.state in setOf("running","approval","review","executed")) line.copy(state = "interrupted",text = line.text + "\n前回の処理は中断しています。操作の完了状態は未確認です") else line
}
