package dev.komichi.agent.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object RootBridge {
    enum class Operation { IDENTITY, FORCE_STOP }
    /** Only enum-selected fixed commands; a strictly validated package token is the only variable. */
    suspend fun run(operation: Operation, packageName: String? = null): String = withContext(Dispatchers.IO) {
        val command = when (operation) {
            Operation.IDENTITY -> "/system/bin/id -u"
            Operation.FORCE_STOP -> {
                require(packageName != null && packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
                require(packageName != "dev.komichi.agent" && !packageName.startsWith("com.android.") && !packageName.startsWith("moe.shizuku."))
                "/system/bin/am force-stop $packageName"
            }
        }
        val process = try { ProcessBuilder("su", "-c", command).redirectErrorStream(true).start() }
            catch (_: Exception) { error("suが利用できません") }
        try {
            require(process.waitFor(7, TimeUnit.SECONDS)) { "Root許可または操作がタイムアウトしました" }
            val result = process.inputStream.bufferedReader().readText().trim().take(2048)
            require(process.exitValue() == 0) { "Root管理アプリまたはOSが拒否しました" }
            if (operation == Operation.IDENTITY) { require(result == "0") { "Root UIDではありません" }; "root_uid_0" }
            else "force_stop_dispatched"
        } finally { process.destroy() }
    }
}
