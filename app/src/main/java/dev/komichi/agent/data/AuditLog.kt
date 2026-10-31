package dev.komichi.agent.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.time.Instant

class AuditLog(private val vault: Vault) {
    private var entries = vault.read("audit")?.optJSONArray("entries")?.objects() ?: emptyList()
    private val mutable = MutableStateFlow(entries.map { it.toString() })
    val items = mutable.asStateFlow()
    @Synchronized fun add(tool: String, status: String) {
        entries = (entries + obj("time" to Instant.now().toString(), "tool" to tool, "status" to status)).takeLast(200)
        vault.write("audit", obj("entries" to array(entries))); mutable.value = entries.map { it.toString() }
    }
    @Synchronized fun clear() { entries = emptyList(); vault.write("audit", obj("entries" to JSONArray())); mutable.value = emptyList() }
}
