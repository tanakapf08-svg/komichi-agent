package dev.komichi.agent.security

import dev.komichi.agent.device.AgentAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

data class Approval(val id: String, val title: String, val detail: String, val editable: Boolean,
                    val result: CompletableDeferred<String?>, val overlay: Boolean)

class ApprovalGate(private val onWaiting: (String, Boolean) -> Unit = { _,_ -> }) {
    private val mutable = MutableStateFlow<Approval?>(null)
    val pending = mutable.asStateFlow()
    suspend fun ask(title: String, detail: String, editable: Boolean = false): String? = withContext(Dispatchers.Main) {
        check(mutable.value == null) { "別の承認を待っています" }
        val service = AgentAccessibilityService.instance
        val item = Approval(UUID.randomUUID().toString(), title, detail, editable,
            CompletableDeferred(), service != null && RuntimeControl.running)
        mutable.value = item
        onWaiting(title,editable)
        try {
            if (item.overlay) service?.showApproval(item, this@ApprovalGate)
            withTimeout(60_000) { item.result.await() }
        } finally {
            service?.dismissApproval()
            if (mutable.value?.id == item.id) mutable.value = null
        }
    }
    fun resolve(id: String, value: String?) {
        val item = mutable.value ?: return
        if (item.id == id) item.result.complete(value)
    }
    fun cancel() { mutable.value?.result?.complete(null) }
}

object RuntimeControl {
    @Volatile var running = false
    @Volatile var sessionId = ""
    var stop: (() -> Unit)? = null
    fun emergencyStop() { running = false; stop?.invoke() }
    fun requireRunning() { check(running) { "操作は停止しています" } }
}
