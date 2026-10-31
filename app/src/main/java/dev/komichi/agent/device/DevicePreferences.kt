package dev.komichi.agent.device

import dev.komichi.agent.data.Vault
import dev.komichi.agent.data.array
import dev.komichi.agent.data.obj

class DevicePreferences(private val vault: Vault) {
    private var data = vault.read("device") ?: obj("allowed" to array(emptyList()), "root" to false, "files" to array(emptyList()))
    @Synchronized fun allowed(): Set<String> = data.getJSONArray("allowed").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    @Synchronized fun allow(packageName: String, allowed: Boolean) {
        data.put("allowed", array(if (allowed) allowed() + packageName else allowed() - packageName)); save()
    }
    @Synchronized fun rootEnabled(): Boolean = data.optBoolean("root")
    @Synchronized fun setRoot(enabled: Boolean) { data.put("root", enabled); save() }
    @Synchronized fun files(): List<String> = data.getJSONArray("files").let { a -> (0 until a.length()).map { a.getString(it) } }
    @Synchronized fun addFile(uri: String) { data.put("files", array((files() + uri).distinct().takeLast(20))); save() }
    @Synchronized fun clearFiles() { data.put("files", array(emptyList())); save() }
    private fun save() = vault.write("device", data)
}
