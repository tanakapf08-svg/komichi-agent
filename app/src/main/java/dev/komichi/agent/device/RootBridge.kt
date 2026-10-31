package dev.komichi.agent.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RootAccessStatus(val checking: Boolean = false, val checked: Boolean = false, val granted: Boolean = false,
    val message: String = "Root UID確認ボタンで確認します。起動時にsuを自動実行しません")

object RootBridge {
    private val mutableStatus = MutableStateFlow(RootAccessStatus())
    val status = mutableStatus.asStateFlow()
    enum class Operation { IDENTITY, FORCE_STOP }
    /** Only enum-selected fixed commands; a strictly validated package token is the only variable. */
    suspend fun run(operation: Operation, packageName: String? = null): String = withContext(Dispatchers.IO) {
        if (operation == Operation.IDENTITY) mutableStatus.value = RootAccessStatus(checking = true)
        val command = when (operation) {
            Operation.IDENTITY -> "/system/bin/id -u"
            Operation.FORCE_STOP -> {
                require(packageName != null && packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
                require(packageName != "dev.komichi.agent" && !packageName.startsWith("com.android.") && !packageName.startsWith("moe.shizuku."))
                "/system/bin/am force-stop $packageName"
            }
        }
        val process = try { ProcessBuilder("su", "-c", command).redirectErrorStream(true).start() }
            catch (_: Exception) {
                if (operation == Operation.IDENTITY) mutableStatus.value = RootAccessStatus(checked = true, message = "suが利用できません")
                error("suが利用できません")
            }
        try {
            require(process.waitFor(7, TimeUnit.SECONDS)) { "Root許可または操作がタイムアウトしました" }
            val result = process.inputStream.bufferedReader().readText().trim().take(2048)
            require(process.exitValue() == 0) { "Root管理アプリまたはOSが拒否しました" }
            if (operation == Operation.IDENTITY) {
                require(result == "0") { "Root UIDではありません" }
                mutableStatus.value = RootAccessStatus(checked = true, granted = true, message = "UID 0を確認しました。端末やRoot管理側の状態が変わった場合は再確認してください")
                "root_uid_0"
            }
            else "force_stop_dispatched"
        } catch (e: Exception) {
            if (operation == Operation.IDENTITY) mutableStatus.value = RootAccessStatus(checked = true, message = "Rootの拒否・未対応・タイムアウト。再確認できます")
            throw e
        } finally { process.destroy() }
    }
}
