package dev.komichi.agent

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.komichi.agent.agent.ApiFailure
import dev.komichi.agent.agent.ResponsesClient
import dev.komichi.agent.agent.ModelChoice
import dev.komichi.agent.agent.ModelCatalog
import dev.komichi.agent.agent.ToolCall
import dev.komichi.agent.agent.ToolCatalog
import dev.komichi.agent.agent.RunMonitor
import dev.komichi.agent.agent.RunPhase
import dev.komichi.agent.auth.Account
import dev.komichi.agent.auth.AuthManager
import dev.komichi.agent.auth.LoginControl
import dev.komichi.agent.data.AuditLog
import dev.komichi.agent.data.ChatLine
import dev.komichi.agent.data.Conversation
import dev.komichi.agent.data.HistoryStore
import dev.komichi.agent.data.obj
import dev.komichi.agent.data.objects
import dev.komichi.agent.data.ChatArchive
import dev.komichi.agent.data.LibraryPolicy
import dev.komichi.agent.data.SavedRoutine
import dev.komichi.agent.device.UserDenied
import dev.komichi.agent.device.TextDocuments
import dev.komichi.agent.data.WorkspaceDocument
import android.net.Uri
import android.provider.OpenableColumns
import dev.komichi.agent.device.AgentAccessibilityService
import dev.komichi.agent.device.DeviceBroker
import dev.komichi.agent.device.RunService
import dev.komichi.agent.device.ShizukuBridge
import dev.komichi.agent.device.CaptureStore
import dev.komichi.agent.security.ApprovalGate
import dev.komichi.agent.security.Privacy
import dev.komichi.agent.security.RuntimeControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AgentViewModel(application: Application): AndroidViewModel(application) {
    private val app = application as KomichiApp
    val preferences = app.preferences
    val library = app.library
    private val tracker = RunMonitor.tracker
    val runProgress = tracker.state
    private var runId = 0L
    private var currentToolLine = ""
    private var currentToolFailed = false
    val gate = ApprovalGate { title,editable ->
        tracker.update(runId,if(editable) RunPhase.RESULT_REVIEW else RunPhase.APPROVAL,title)
        if(currentToolLine.isNotBlank()) updateToolLine(if(editable) "review" else "approval",title)
    }
    val audit = AuditLog(app.vault)
    val auth = AuthManager(app, app.vault)
    val shizuku = ShizukuBridge(app)
    val broker = DeviceBroker(app, preferences, gate, audit, shizuku,library,onExecuting = { name ->
        tracker.update(runId,RunPhase.EXECUTING,ToolCatalog.label(name),tool = name)
        updateToolLine("running","${ToolCatalog.label(name)} · 動作中")
    },onResult = { name,failed ->
        currentToolFailed = failed
        updateToolLine(if(failed) "failed" else "executed","${ToolCatalog.label(name)} · ${if(failed) "操作失敗" else "実行済み"}")
    })
    private val api = ResponsesClient(auth,preferences::toolEnabled) {
        "Local capability state: Root opt-in=${preferences.rootEnabled()}, full app scope=${preferences.fullAccessEnabled()}. " +
            "Root file tools exist for allowed app-private data and require explicit operation approval. " +
            "If Root is disabled, direct the user to 接続・権限 → Root連携; do not invent a blanket Android limitation."
    }
    private val history = HistoryStore(app.vault)
    var sessions by mutableStateOf(history.load()); private set
    var selectedId by mutableStateOf(sessions.firstOrNull()?.id ?: ""); private set
    var accounts by mutableStateOf(auth.accounts()); private set
    var activeAccount by mutableStateOf(auth.active()); private set
    private val modelCatalog = ModelCatalog(viewModelScope, { accountId ->
        check(auth.active()?.clientId == accountId) { "アカウントが変更されました" }
        try { api.models() } finally { refreshAccounts() }
    }, preferences::selectedModel, preferences::setSelectedModel, ::safeError)
    val modelCatalogState = modelCatalog.state
    val models get() = modelCatalogState.value.models
    val model get() = modelCatalogState.value.selected
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("準備完了 · ChatGPTに接続してください"); private set
    var partial by mutableStateOf(""); private set
    var privilegeVersion by mutableStateOf(0); private set
    var libraryVersion by mutableStateOf(0); private set
    var selectedPreset by mutableStateOf(preferences.selectedPreset()); private set
    var importDraft by mutableStateOf<WorkspaceDocument?>(null); private set
    private var exportPayload: String? = null
    private var job: Job? = null
    private var authJob: Job? = null
    private var deviceSession = ""
    val current get() = sessions.find { it.id == selectedId }
    init {
        RuntimeControl.stop = { stop() }
        LoginControl.cancel = { authJob?.cancel() }
        if (sessions.isEmpty()) newConversation()
        if (activeAccount?.connected == true) status = "保存済みのChatGPTアカウントを使用します"
        fetchModels()
    }
    fun refreshStatus() { privilegeVersion++ }
    fun pickModel(id: String) { if (!busy) modelCatalog.select(id) }
    fun newConversation() {
        if (busy) return
        val conversation = Conversation(accountId = auth.active()?.clientId ?: "")
        sessions = (listOf(conversation) + sessions).take(30); selectedId = conversation.id; history.save(sessions)
    }
    fun selectConversation(id: String) { if (!busy) selectedId = id }
    fun deleteConversation(id: String) {
        if (busy) return
        sessions = sessions.filter { it.id != id }; selectedId = sessions.firstOrNull()?.id ?: ""
        if (sessions.isEmpty()) newConversation() else history.save(sessions)
    }
    private fun updateCurrent(transform: (Conversation) -> Conversation) {
        sessions = sessions.map { if (it.id == selectedId) transform(it).copy(updatedAt = System.currentTimeMillis()) else it }
    }
    private fun line(role: String, text: String) { updateCurrent { it.copy(lines = it.lines + ChatLine(role = role, text = text)) } }
    private fun append(item: JSONObject) { updateCurrent { it.copy(input = JSONArray(it.input.toString()).put(item)) } }
    private fun save() { history.save(sessions) }
    fun reportLocal(message: String) { status = message }
    fun prepareExport(text: String) { if(!busy) { require(text.length <= 256_000) { "書き出すテキストが大きすぎます" }; exportPayload = text } }
    fun discardExport() { exportPayload = null }
    fun writeExport(uri: Uri) {
        val text = exportPayload ?: return; exportPayload = null
        localTransfer("ファイルを書き出しています") { TextDocuments.write(app,uri,text); "ファイルを書き出しました。保存先は暗号化されていない場合があります" }
    }
    fun readImport(uri: Uri, historyFile: Boolean) {
        localTransfer("選択したファイルを読み込んでいます") {
            val text = TextDocuments.read(app,uri,if(historyFile) 256_000 else 64_000)
            if(historyFile) {
                val imported = ChatArchive.restore(text)
                sessions = (listOf(imported) + sessions).take(30); selectedId = imported.id; save()
                "履歴を表示用に読み込みました。内容はAIへ送信しません"
            } else {
                val name = withContext(Dispatchers.IO) {
                    var value = "imported.txt"
                    app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) value = it.getString(0) ?: value }
                    value.replace(Regex("[\\p{Cntrl}/\\\\]"),"_").take(80).ifBlank { "imported.txt" }
                }
                importDraft = WorkspaceDocument("imports/$name",text,0)
                "読み込みました。保存する前に内容とファイル名を確認してください"
            }
        }
    }
    fun dismissImport() { importDraft = null }
    private fun localTransfer(label: String, action: suspend () -> String) {
        if(busy) return
        busy = true; runId = tracker.begin(label,RunPhase.EXECUTING)
        job = viewModelScope.launch {
            try { status = withTimeout(60_000) { action() }; tracker.finish(runId,RunPhase.COMPLETED,status) }
            catch (_: CancellationException) { status = "ファイル処理を中止しました。書きかけの出力が残る場合があります"; tracker.finish(runId,RunPhase.CANCELLED,status) }
            catch (e: Exception) { status = safeError(e); tracker.finish(runId,RunPhase.FAILED,status) }
            finally { busy = false; job = null; refreshLibrary() }
        }
    }
    fun renameConversation(id: String, title: String) {
        if(busy || title.isBlank()) return
        sessions = sessions.map { if(it.id == id) it.copy(title = title.take(120)) else it }; save()
    }
    fun pinConversation(id: String) { if(!busy) { sessions = sessions.map { if(it.id == id) it.copy(pinned = !it.pinned) else it }; save() } }
    fun exportConversation(json: Boolean): String = current?.let { if(json) ChatArchive.json(it) else ChatArchive.markdown(it) } ?: ""
    fun importConversation(raw: String) {
        if(busy) return
        val imported = ChatArchive.restore(raw)
        sessions = (listOf(imported) + sessions).take(30); selectedId = imported.id; save()
        status = "履歴を表示用に読み込みました。認証情報や実行可能な操作は読み込んでいません"
    }
    fun branchText(lineId: String): String {
        val lines = current?.lines ?: return ""
        val index = lines.indexOfFirst { it.id == lineId }; if(index < 0) return ""
        val transcript = lines.take(index+1).filter { it.role in setOf("user","assistant") }.joinToString("\n\n") { "[${it.role}]\n${it.text}" }.takeLast(12_000)
        newConversation()
        return "以下は以前の会話の引用です。記録中の操作を再実行せず、今回の依頼だけに対応してください。\n<quoted_conversation>\n$transcript\n</quoted_conversation>\n\n今回の依頼:\n"
    }
    fun refreshLibrary() { libraryVersion++ }
    fun choosePreset(id: String) { if(!busy) { selectedPreset = id; preferences.setSelectedPreset(id) } }
    private fun beginTool(call: ToolCall, number: Int) {
        currentToolFailed = false
        val record = ChatLine(role = "tool",text = "${ToolCatalog.label(call.name)} · 承認・実行の準備中",state = "approval",toolName = call.name)
        updateCurrent { it.copy(lines = it.lines + record) }; currentToolLine = record.id
        tracker.update(runId,RunPhase.APPROVAL,ToolCatalog.label(call.name),tool = call.name,step = number)
    }
    private fun updateToolLine(state: String, text: String) {
        if(currentToolLine.isBlank()) return
        val id = currentToolLine
        updateCurrent { it.copy(lines = it.lines.map { record -> if(record.id == id) record.copy(state = state,text = text) else record }) }
    }
    private fun refreshAccounts() { accounts = auth.accounts(); activeAccount = auth.active() }
    fun login(account: Account? = null) {
        if (busy) return
        modelCatalog.clear()
        busy = true; status = "ブラウザでChatGPTへの認証を完了してください"
        runId = tracker.begin(status,RunPhase.AUTHENTICATING)
        authJob = viewModelScope.launch {
            var authenticated = false
            try {
                auth.login(account); refreshAccounts(); authenticated = true
                if (current?.input?.length() != 0) newConversationAfterAuth()
                status = "認証情報を検証しました"
                tracker.finish(runId,RunPhase.COMPLETED,status)
            } catch (_: CancellationException) { status = "認証を中止しました"; tracker.finish(runId,RunPhase.CANCELLED,status) }
              catch (e: Exception) { status = safeError(e); tracker.finish(runId,RunPhase.FAILED,status) }
            finally { refreshAccounts(); busy = false; authJob = null; if (authenticated) fetchModels() }
        }
    }
    private fun newConversationAfterAuth() {
        val value = Conversation(accountId = auth.active()?.clientId ?: "")
        sessions = (listOf(value) + sessions).take(30); selectedId = value.id; save()
    }
    fun selectAccount(id: String) {
        if (busy) return
        modelCatalog.clear(); auth.select(id); refreshAccounts(); newConversation(); status = "アカウントを選択しました"; fetchModels()
    }
    fun signOut() {
        if (busy) return
        modelCatalog.clear()
        busy = true
        runId = tracker.begin("サインアウトしています",RunPhase.AUTHENTICATING)
        authJob = viewModelScope.launch {
            try { val revoked = auth.signOut(); status = if (revoked) "サインアウトしました" else "端末内の認証情報を削除しました。リモート失効は未確認です。ChatGPT設定から切断できます"; tracker.finish(runId,RunPhase.COMPLETED,status) }
            catch (e: CancellationException) { status = "サインアウトを中止しました"; tracker.finish(runId,RunPhase.CANCELLED,status) }
            catch (e: Exception) { status = safeError(e); tracker.finish(runId,RunPhase.FAILED,status) }
            finally { refreshAccounts(); busy = false; authJob = null }
        }
    }
    fun fetchModels() {
        if (busy) return
        val account = auth.active()?.takeIf { it.connected } ?: return
        modelCatalog.refresh(account.clientId)
    }
    private suspend fun beginRun() {
        deviceSession = UUID.randomUUID().toString()
        RuntimeControl.sessionId = deviceSession
        RuntimeControl.running = true
        RunService.startAndAwait(app,deviceSession)
        AgentAccessibilityService.instance?.showStop(true)
    }
    private fun endRun() {
        tracker.state.value.takeIf { it.generation == runId && !it.active }?.let { status = it.detail }
        if(deviceSession.isNotBlank() && RuntimeControl.sessionId == deviceSession) {
            RuntimeControl.running = false; AgentAccessibilityService.instance?.showStop(false); RunService.stopAfterReady(deviceSession)
        }
        deviceSession = ""
        gate.cancel(); busy = false; partial = ""; currentToolLine = ""; refreshLibrary()
    }
    fun stop() { tracker.stopping(); RuntimeControl.running = false; gate.cancel(); job?.cancel(); authJob?.cancel(); modelCatalog.cancelRefresh(); CaptureStore.cancel?.invoke(); status = if(busy) "停止処理中です。開始済み操作を終了しています" else "停止しました" }
    fun send(userText: String) {
        if (busy || userText.isBlank()) return
        if (current?.archived == true) { status = "インポートした履歴は表示専用です。新しい会話かメッセージの「分岐」を使ってください"; return }
        if (model.isBlank()) { status = "認証後にモデルを取得・選択してください"; return }
        val active = auth.active() ?: run { status = "先に認証してください"; return }
        if (!active.connected) { status = "選択アカウントで再認証してください"; return }
        if (current?.accountId?.isNotBlank() == true && current?.accountId != active.clientId) { status = "アカウントが異なります。新しい会話を開始してください"; return }
        busy = true
        runId = tracker.begin("送信する文章を確認してください",RunPhase.RESULT_REVIEW)
        job = viewModelScope.launch {
            try {
                val preset = library.presets().firstOrNull { it.id == selectedPreset }
                val request = if(preset == null) userText else "ユーザーが選んだ指示プリセット（${preset.name}）:\n${preset.instructions}\n\n今回の依頼:\n$userText"
                val approved = gate.ask("OpenAIへ送信する文章を確認", Privacy.mask(request.take(16_000)), editable = true)
                    ?: throw UserDenied()
                if (approved.isBlank()) throw UserDenied()
                require(approved.length <= 16_000) { "送信文は16000文字以内にしてください" }
                beginRun()
                updateCurrent { it.copy(accountId = active.clientId, title = if (it.lines.isEmpty()) approved.substringAfter("今回の依頼:\n",approved).take(32) else it.title) }
                line("user", approved); append(obj("role" to "user", "content" to approved)); save()
                withTimeout(600_000) { agentLoop() }
                status = "完了しました"
                tracker.finish(runId,RunPhase.COMPLETED,status)
            } catch (_: CancellationException) { status = "操作を停止しました（時間上限またはキャンセル）"; updateToolLine("cancelled",status); tracker.finish(runId,RunPhase.CANCELLED,status) }
              catch (_: UserDenied) { status = "承認を中止しました。実行済みの操作は巻き戻しません"; updateToolLine("cancelled",status); tracker.finish(runId,RunPhase.CANCELLED,status) }
              catch (e: Exception) { status = safeError(e); updateToolLine("failed",status); tracker.finish(runId,RunPhase.FAILED,status); line("notice", status) }
            finally { withContext(NonCancellable + Dispatchers.Main) { try { save() } finally { endRun(); job = null } } }
        }
    }
    private suspend fun agentLoop() {
        var toolCount = 0
        val seen = current!!.input.objects().filter { it.optString("type") == "function_call_output" }.map { it.optString("call_id") }.toMutableSet()
        repeat(12) {
            RuntimeControl.requireRunning(); status = "応答を受信しています"; partial = ""
            currentToolLine = ""; tracker.update(runId,RunPhase.CONNECTING,"ChatGPTに接続しています")
            val turn = api.turn(model, current!!.input,onStarted = { withContext(Dispatchers.Main) { tracker.update(runId,RunPhase.THINKING,"AIの応答を待っています") } }) { delta -> withContext(Dispatchers.Main) { partial += delta; tracker.update(runId,RunPhase.THINKING,"応答を生成しています") } }
            turn.output.objects().forEach(::append)
            partial = ""; if (turn.text.isNotBlank()) line("assistant", turn.text)
            if (turn.calls.isEmpty()) { save(); return }
            val unresolved = turn.calls.toMutableList()
            try {
                require(toolCount + turn.calls.size <= 8) { "最大操作回数（8回）に達しました" }
                require(turn.calls.none { it.id in seen }) { "既に処理したツールIDの再利用を拒否しました" }
                turn.calls.forEach { call ->
                    status = "動作中: ${ToolCatalog.label(call.name)}"
                    toolCount++; beginTool(call,toolCount); val result = broker.execute(call)
                    append(obj("type" to "function_call_output", "call_id" to call.id, "output" to result))
                    seen.add(call.id); unresolved.remove(call)
                    updateToolLine(if(currentToolFailed) "failed" else "completed","${ToolCatalog.label(call.name)} · ${if(currentToolFailed) "失敗した結果" else "実行結果"}を確認して送信しました"); currentToolLine = ""; save()
                }
            } finally {
                // Close pending calls locally on cancellation/failure so a later user turn
                // never accidentally resumes an unapproved operation.
                unresolved.forEach { call -> append(obj("type" to "function_call_output", "call_id" to call.id,
                    "output" to obj("status" to "stopped", "action" to "Do not retry without a new user request").toString())) }
                save()
            }
        }
        error("推論ラウンドの上限に達しました")
    }
    fun manualTool(name: String, arguments: JSONObject = JSONObject()) {
        runLocal("端末内の操作",listOf(ToolCall(UUID.randomUUID().toString(),"android",name,arguments)),false)
    }
    fun runRoutine(routine: SavedRoutine) { runLocal(routine.name,routine.steps,true) }
    private fun runLocal(title: String, steps: List<ToolCall>, preview: Boolean) {
        if(busy) return
        if(current?.archived == true) newConversation()
        busy = true; runId = tracker.begin(title)
        job = viewModelScope.launch {
            try {
                require(steps.size in 1..8); steps.forEach(ToolCatalog::validate)
                if(preview && gate.ask("ワークフローを実行: $title",LibraryPolicy.routineJson(steps)+"\n\n各操作でも確認します。AIへは接続しません。") == null) throw UserDenied()
                beginRun()
                withTimeout(600_000) { steps.forEachIndexed { index,call ->
                    beginTool(call,index+1)
                    val result = broker.execute(call.copy(id = UUID.randomUUID().toString()),shareResult = false)
                    line("local","${ToolCatalog.label(call.name)}\n$result")
                    updateToolLine(if(currentToolFailed) "failed" else "completed","${ToolCatalog.label(call.name)} · ${if(currentToolFailed) "失敗" else "完了"}（端末内）")
                    currentToolLine = ""
                    if(currentToolFailed) error(JSONObject(result).optString("detail","操作に失敗しました"))
                } }
                status = "端末内の操作が完了しました。結果はチャットに表示します。AIへの送信はありません"
                tracker.finish(runId,RunPhase.COMPLETED,status)
            } catch (_: CancellationException) { status = "端末内の操作を停止しました"; updateToolLine("cancelled",status); tracker.finish(runId,RunPhase.CANCELLED,status) }
              catch (_: UserDenied) { status = "承認を中止しました。実行済みの操作は巻き戻しません"; updateToolLine("cancelled",status); tracker.finish(runId,RunPhase.CANCELLED,status) }
              catch (e: Exception) { status = safeError(e); updateToolLine("failed",status); tracker.finish(runId,RunPhase.FAILED,status) }
            finally { withContext(NonCancellable + Dispatchers.Main) { try { save() } finally { endRun(); job = null } } }
        }
    }
    private fun safeError(error: Exception): String {
        if (error is ApiFailure) return error.message ?: "ChatGPT接続エラー"
        return Privacy.mask(error.message?.take(400) ?: "操作に失敗しました").replace(Regex("(?i)https?://\\S+"), "[URL]")
    }
    override fun onCleared() { stop(); RuntimeControl.stop = null; LoginControl.cancel = null; super.onCleared() }
}
