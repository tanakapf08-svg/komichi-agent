package dev.komichi.agent.data

import org.json.JSONArray
import org.json.JSONObject

fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
    pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}
fun array(values: Iterable<Any?>) = JSONArray().apply { values.forEach { put(it) } }
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
