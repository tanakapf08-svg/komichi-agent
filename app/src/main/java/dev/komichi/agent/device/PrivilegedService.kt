package dev.komichi.agent.device

import android.content.Context
import android.os.Binder
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

/** Shizuku creates this class. There is deliberately no shell(command) method. */
@Keep
class PrivilegedService(context: Context): IPrivilegedService.Stub() {
    private val appUid = context.applicationInfo.uid
    private fun authorize() { require(appUid >= 10_000 && Binder.getCallingUid() == appUid) { "Unauthorized caller" } }
    override fun diagnostics(): String {
        authorize()
        return "uid=${android.os.Process.myUid()}\nAndroid=${fixed(listOf("/system/bin/getprop", "ro.build.version.release"))}\nDisplay=${fixed(listOf("/system/bin/wm", "size"))}"
    }
    override fun forceStop(packageName: String): String {
        authorize(); require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
        require(packageName != "dev.komichi.agent" && packageName != "android" && !packageName.startsWith("com.android.") && !packageName.startsWith("moe.shizuku."))
        fixed(listOf("/system/bin/am", "force-stop", packageName))
        return "force_stop_dispatched"
    }
    private fun fixed(args: List<String>): String {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        try {
            require(process.waitFor(4, TimeUnit.SECONDS)) { "system_operation_timeout" }
            val result = process.inputStream.bufferedReader().readText().take(2048)
            require(process.exitValue() == 0) { "system_operation_denied" }
            return result.trim()
        } finally { process.destroy() }
    }
    override fun destroy() { authorize(); System.exit(0) }
}
