package dev.komichi.agent.data

import dev.komichi.agent.agent.ToolCall
import dev.komichi.agent.agent.ToolCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class WorkspaceDocument(val path: String, val text: String, val updatedAt: Long) {
    val version: String get() = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
data class AssistantPreset(val id: String, val name: String, val instructions: String)
data class SavedRoutine(val id: String, val name: String, val steps: List<ToolCall>)

object LibraryPolicy {
    fun path(raw: String, allowRoot: Boolean = false): String {
        require(raw.length <= 240 && raw.none { it.isISOControl() } && !raw.startsWith('/') && '\\' !in raw) { "作業スペース内の相対パスを指定してください" }
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." || it.length > 80 } && parts.size <= 12) { "パスが不正です" }
        require(allowRoot || parts.isNotEmpty()) { "ファイル名が必要です" }
        return parts.joinToString("/")
    }
    fun text(text: String) { require(text.length <= 64_000 && '\u0000' !in text) { "テキストは64000文字以内にしてください" } }
    fun routine(raw: String): List<ToolCall> {
        require(raw.length <= 64_000)
        val steps = JSONArray(raw)
        require(steps.length() in 1..8) { "ワークフローは1〜8操作にしてください" }
        return steps.objects().map { entry ->
            require(entry.keys().asSequence().toSet() == setOf("tool","arguments")) { "toolとargumentsのみ指定できます" }
            val call = ToolCall(UUID.randomUUID().toString(),"android",entry.getString("tool"),entry.getJSONObject("arguments"))
            ToolCatalog.validate(call)
            require(!call.arguments.has("snapshot_id")) { "画面IDは毎回変わるため保存できません。画面操作はチャットから依頼してください" }
            call
        }
    }
    fun routineJson(steps: List<ToolCall>): String = array(steps.map { obj("tool" to it.name,"arguments" to it.arguments) }).toString(2)
}

/** Virtual text workspace: encrypted, no shell and no host filesystem paths. */
class LibraryStore(private val loadState: () -> JSONObject?, private val writeState: (JSONObject) -> Unit) {
    constructor(vault: Vault): this({ vault.read("library") },{ vault.write("library",it) })
    private var data = loadState() ?: obj("documents" to JSONArray(),"presets" to JSONArray(),"routines" to JSONArray())
    @Synchronized fun documents(): List<WorkspaceDocument> = data.getJSONArray("documents").objects().map {
        WorkspaceDocument(it.getString("path"),it.getString("text"),it.getLong("updated_at"))
    }.sortedBy { it.path }
    @Synchronized fun read(path: String): WorkspaceDocument = documents().firstOrNull { it.path == LibraryPolicy.path(path) } ?: error("ファイルがありません")
    @Synchronized fun write(path: String, text: String, expectedVersion: String): WorkspaceDocument {
        val name = LibraryPolicy.path(path); LibraryPolicy.text(text)
        val all = documents(); val old = all.firstOrNull { it.path == name }
        require((old?.version ?: "") == expectedVersion) { "ファイルが変更されています。最新版を読み直してください。新規作成ではversionを空にします" }
        require(all.none { it.path.startsWith("$name/") || name.startsWith("${it.path}/") }) { "同名のファイル／フォルダと競合します" }
        val next = WorkspaceDocument(name,text,System.currentTimeMillis())
        val updated = all.filterNot { it.path == name } + next
        require(updated.size <= 100 && updated.sumOf { it.text.length } <= 1_000_000) { "作業スペースの上限（100ファイル・合計100万文字）です" }
        data.put("documents",array(updated.map { obj("path" to it.path,"text" to it.text,"updated_at" to it.updatedAt) })); save()
        return next
    }
    @Synchronized fun remove(path: String, expectedVersion: String) {
        val old = read(path); require(old.version == expectedVersion) { "変更されたファイルの削除を拒否しました" }
        data.put("documents",array(documents().filterNot { it.path == old.path }.map { obj("path" to it.path,"text" to it.text,"updated_at" to it.updatedAt) })); save()
    }
    @Synchronized fun presets(): List<AssistantPreset> = data.getJSONArray("presets").objects().map { AssistantPreset(it.getString("id"),it.getString("name"),it.getString("instructions")) }
    @Synchronized fun savePreset(id: String, name: String, instructions: String) {
        require(name.isNotBlank() && name.length <= 80 && instructions.length <= 4000)
        val next = AssistantPreset(id.ifBlank { UUID.randomUUID().toString() },name,instructions)
        val all = presets().filterNot { it.id == id } + next; require(all.size <= 20)
        data.put("presets",array(all.map { obj("id" to it.id,"name" to it.name,"instructions" to it.instructions) })); save()
    }
    @Synchronized fun deletePreset(id: String) { data.put("presets",array(presets().filterNot { it.id == id }.map { obj("id" to it.id,"name" to it.name,"instructions" to it.instructions) })); save() }
    @Synchronized fun routines(): List<SavedRoutine> = data.getJSONArray("routines").objects().map {
        SavedRoutine(it.getString("id"),it.getString("name"),LibraryPolicy.routine(it.getJSONArray("steps").toString()))
    }
    @Synchronized fun saveRoutine(id: String, name: String, raw: String) {
        require(name.isNotBlank() && name.length <= 80)
        val steps = LibraryPolicy.routine(raw); val all = routines().filterNot { it.id == id } + SavedRoutine(id.ifBlank { UUID.randomUUID().toString() },name,steps)
        require(all.size <= 20)
        data.put("routines",array(all.map { obj("id" to it.id,"name" to it.name,"steps" to JSONArray(LibraryPolicy.routineJson(it.steps))) })); save()
    }
    @Synchronized fun deleteRoutine(id: String) { data.put("routines",array(routines().filterNot { it.id == id }.map { obj("id" to it.id,"name" to it.name,"steps" to JSONArray(LibraryPolicy.routineJson(it.steps))) })); save() }
    private fun save() { writeState(data) }
}
