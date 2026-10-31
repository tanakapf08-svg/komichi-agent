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
import dev.komichi.agent.agent.ToolCall
import dev.komichi.agent.auth.Account
import dev.komichi.agent.auth.AuthManager
import dev.komichi.agent.auth.LoginControl
import dev.komichi.agent.data.AuditLog
import dev.komichi.agent.data.ChatLine
import dev.komichi.agent.data.Conversation
import dev.komichi.agent.data.HistoryStore
import dev.komichi.agent.data.obj
import dev.komichi.agent.data.objects
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
    val gate = ApprovalGate()
    val audit = AuditLog(app.vault)
    val auth = AuthManager(app, app.vault)
    val shizuku = ShizukuBridge(app)
    val broker = DeviceBroker(app, preferences, gate, audit, shizuku)
    private val api = ResponsesClient(auth)
    private val history = HistoryStore(app.vault)
    var sessions by mutableStateOf(history.load()); private set
    var selectedId by mutableStateOf(sessions.firstOrNull()?.id ?: ""); private set
    var accounts by mutableStateOf(auth.accounts()); private set
    var activeAccount by mutableStateOf(auth.active()); private set
    var models by mutableStateOf<List<ModelChoice>>(emptyList()); private set
    var model by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("端末内の準備ができました。AI利用には認証してください"); private set
    var partial by mutableStateOf(""); private set
    var privilegeVersion by mutableStateOf(0); private set
    private var job: Job? = null
    private var authJob: Job? = null
    val current get() = sessions.find { it.id == selectedId }
    init {
        RuntimeControl.stop = { stop() }
        LoginControl.cancel = { authJob?.cancel() }
        if (sessions.isEmpty()) newConversation()
    }
    fun refreshStatus() { privilegeVersion++ }
    fun pickModel(id: String) { if (!busy && models.any { it.id == id }) model = id }
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
        sessions = sessions.map { if (it.id == selectedId) transform(it) else it }
    }
    private fun line(role: String, text: String) { updateCurrent { it.copy(lines = it.lines + ChatLine(role = role, text = text)) } }
    private fun append(item: JSONObject) { updateCurrent { it.copy(input = JSONArray(it.input.toString()).put(item)) } }
    private fun save() { history.save(sessions) }
    private fun refreshAccounts() { accounts = auth.accounts(); activeAccount = auth.active() }
    fun login(account: Account? = null) {
        if (busy) return
        busy = true; status = "ブラウザでChatGPTへの認証を完了してください"
        authJob = viewModelScope.launch {
            try {
                auth.login(account); refreshAccounts(); models = emptyList(); model = ""
                if (current?.input?.length() != 0) newConversationAfterAuth()
                status = "認証情報を検証しました。モデルを取得してください"
            } catch (_: CancellationException) { status = "認証を中止しました" }
              catch (e: Exception) { status = safeError(e) }
            finally { refreshAccounts(); busy = false; authJob = null }
        }
    }
    private fun newConversationAfterAuth() {
        val value = Conversation(accountId = auth.active()?.clientId ?: "")
        sessions = (listOf(value) + sessions).take(30); selectedId = value.id; save()
    }
    fun selectAccount(id: String) {
        if (busy) return
        auth.select(id); refreshAccounts(); models = emptyList(); model = ""; newConversation(); status = "アカウントを選択しました。モデルを取得してください"
    }
    fun signOut() {
        if (busy) return
        busy = true
        authJob = viewModelScope.launch {
            try { val revoked = auth.signOut(); status = if (revoked) "サインアウトしました" else "端末内の認証情報を削除しました。リモート失効は未確認です。ChatGPT設定から切断できます" }
            catch (e: Exception) { status = safeError(e) }
            finally { models = emptyList(); model = ""; refreshAccounts(); busy = false; authJob = null }
        }
    }
    fun fetchModels() {
        if (busy) return
        busy = true; status = "アカウントのモデルを取得しています"
        authJob = viewModelScope.launch {
            try { models = api.models(); model = models.firstOrNull()?.id ?: ""; status = if (models.isEmpty()) "利用可能なモデルがありません。有料APIには切り替えません" else "モデルを取得しました。推論の利用可否は最初の応答で確認します" }
            catch (e: Exception) { status = safeError(e) }
            finally { refreshAccounts(); busy = false; authJob = null }
        }
    }
    private fun beginRun() {
        RuntimeControl.sessionId = UUID.randomUUID().toString()
        RuntimeControl.running = true
        app.startForegroundService(Intent(app, RunService::class.java))
        AgentAccessibilityService.instance?.showStop(true)
    }
    private fun endRun() {
        RuntimeControl.running = false; AgentAccessibilityService.instance?.showStop(false)
        gate.cancel(); app.stopService(Intent(app, RunService::class.java)); busy = false; partial = ""
    }
    fun stop() { RuntimeControl.running = false; gate.cancel(); job?.cancel(); authJob?.cancel(); CaptureStore.cancel?.invoke(); status = "停止しました。開始済み操作は取り消せない場合があります" }
    fun send(userText: String) {
        if (busy || userText.isBlank()) return
        if (model.isBlank()) { status = "認証後にモデルを取得・選択してください"; return }
        val active = auth.active() ?: run { status = "先に認証してください"; return }
        if (!active.connected) { status = "選択アカウントで再認証してください"; return }
        if (current?.accountId?.isNotBlank() == true && current?.accountId != active.clientId) { status = "アカウントが異なります。新しい会話を開始してください"; return }
        busy = true
        job = viewModelScope.launch {
            try {
                val approved = gate.ask("OpenAIへ送信する文章を確認", Privacy.mask(userText.take(16_000)), editable = true)
                    ?: run { status = "送信を中止しました"; return@launch }
                if (approved.isBlank()) { status = "送信文が空です"; return@launch }
                require(approved.length <= 16_000) { "送信文は16000文字以内にしてください" }
                beginRun()
                updateCurrent { it.copy(accountId = active.clientId, title = if (it.lines.isEmpty()) approved.take(32) else it.title) }
                line("user", approved); append(obj("role" to "user", "content" to approved)); save()
                withTimeout(300_000) { agentLoop() }
                status = "完了しました"
            } catch (_: CancellationException) { status = "操作を停止しました（時間上限またはキャンセル）" }
              catch (e: Exception) { status = safeError(e); line("notice", status) }
            finally { withContext(NonCancellable + Dispatchers.Main) { try { save() } finally { endRun(); job = null } } }
        }
    }
    private suspend fun agentLoop() {
        var toolCount = 0
        val seen = current!!.input.objects().filter { it.optString("type") == "function_call_output" }.map { it.optString("call_id") }.toMutableSet()
        repeat(12) {
            RuntimeControl.requireRunning(); status = "応答を受信しています"; partial = ""
            val turn = api.turn(model, current!!.input) { delta -> withContext(Dispatchers.Main) { partial += delta } }
            turn.output.objects().forEach(::append)
            partial = ""; if (turn.text.isNotBlank()) line("assistant", turn.text)
            if (turn.calls.isEmpty()) { save(); return }
            val unresolved = turn.calls.toMutableList()
            try {
                require(toolCount + turn.calls.size <= 8) { "最大操作回数（8回）に達しました" }
                require(turn.calls.none { it.id in seen }) { "既に処理したツールIDの再利用を拒否しました" }
                turn.calls.forEach { call ->
                    status = "確認・実行中: ${call.name}"; line("tool", "${call.name} · 承認待ち")
                    toolCount++; val result = broker.execute(call)
                    append(obj("type" to "function_call_output", "call_id" to call.id, "output" to result))
                    seen.add(call.id); unresolved.remove(call)
                    line("tool", "${call.name} · 結果を確認して送信しました"); save()
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
        if (busy) return
        busy = true
        job = viewModelScope.launch {
            try { beginRun(); val result = broker.execute(ToolCall(UUID.randomUUID().toString(), "android", name, arguments), shareResult = false)
                line("local", "端末内テスト: $name\n$result"); status = "端末内テストが完了しました。AIへの送信はありません" }
            catch (_: CancellationException) { status = "端末内テストを停止しました" }
            catch (e: Exception) { status = safeError(e) }
            finally { try { save() } finally { endRun(); job = null } }
        }
    }
    private fun safeError(error: Exception): String {
        if (error is ApiFailure) return error.message ?: "ChatGPT接続エラー"
        return Privacy.mask(error.message?.take(400) ?: "操作に失敗しました").replace(Regex("(?i)https?://\\S+"), "[URL]")
    }
    override fun onCleared() { stop(); RuntimeControl.stop = null; LoginControl.cancel = null; super.onCleared() }
}
