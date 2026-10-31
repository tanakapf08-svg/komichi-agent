package dev.komichi.agent.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RunPhase(val label: String) {
    IDLE("待機中"), AUTHENTICATING("認証中"), APPROVAL("承認待ち"), CONNECTING("接続中"),
    THINKING("応答を生成中"), EXECUTING("操作を実行中"), RESULT_REVIEW("送信内容の確認待ち"),
    STOPPING("停止処理中"), COMPLETED("完了"), CANCELLED("停止済み"), FAILED("失敗")
}
data class RunProgress(val phase: RunPhase = RunPhase.IDLE, val detail: String = "", val active: Boolean = false,
    val startedAt: Long = 0, val tool: String = "", val step: Int = 0, val maximum: Int = 8, val generation: Long = 0, val finishedAt: Long = 0)

/** A stale callback cannot revive a stopped run or overwrite its final result. */
class RunTracker(private val now: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow(RunProgress())
    val state = mutable.asStateFlow()
    @Synchronized fun begin(detail: String, phase: RunPhase = RunPhase.CONNECTING): Long {
        val id = mutable.value.generation + 1
        mutable.value = RunProgress(phase,detail,true,now(),generation = id)
        return id
    }
    @Synchronized fun update(id: Long, phase: RunPhase, detail: String, tool: String? = null, step: Int? = null) {
        val old = mutable.value
        if (old.generation != id || !old.active || old.phase == RunPhase.STOPPING) return
        mutable.value = old.copy(phase = phase,detail = detail,tool = tool ?: old.tool,step = step ?: old.step)
    }
    @Synchronized fun stopping() { if (mutable.value.active) mutable.value = mutable.value.copy(phase = RunPhase.STOPPING,detail = "開始済み操作の停止と後処理を行っています") }
    @Synchronized fun finish(id: Long, phase: RunPhase, detail: String) {
        require(phase in setOf(RunPhase.COMPLETED,RunPhase.CANCELLED,RunPhase.FAILED))
        if (mutable.value.generation != id || !mutable.value.active) return
        val finalPhase = if (mutable.value.phase == RunPhase.STOPPING) RunPhase.CANCELLED else phase
        val finalDetail = if(mutable.value.phase == RunPhase.STOPPING && phase != RunPhase.CANCELLED) "停止しました。開始済みの操作は取り消せない場合があります" else detail
        mutable.value = mutable.value.copy(active = false,phase = finalPhase,detail = finalDetail,finishedAt = now())
    }
}

object RunMonitor { val tracker = RunTracker() }
