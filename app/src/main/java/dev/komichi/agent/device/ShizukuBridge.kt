package dev.komichi.agent.device

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.komichi.agent.BuildConfig
import dev.komichi.agent.data.obj
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import rikka.shizuku.Shizuku

class ShizukuBridge(private val context: Context) {
    fun status(): JSONObject = runCatching {
        val alive = Shizuku.pingBinder()
        obj("connected" to alive, "permission" to (alive && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED),
            "uid" to if (alive && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) Shizuku.getUid() else JSONObject.NULL)
    }.getOrElse { obj("connected" to false, "permission" to false) }
    fun requestPermission() {
        require(Shizuku.pingBinder()) { "Shizukuを起動してください" }
        require(!Shizuku.isPreV11()) { "古いShizukuには対応していません" }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            require(!Shizuku.shouldShowRequestPermissionRationale()) { "ShizukuアプリでKomichiの許可を設定してください" }
            Shizuku.requestPermission(101)
        }
    }
    suspend fun run(packageName: String? = null): String {
        require(status().optBoolean("permission")) { "Shizukuの接続と権限が必要です" }
        val args = Shizuku.UserServiceArgs(ComponentName(context, PrivilegedService::class.java))
            .daemon(false).processNameSuffix("komichi_privileged").debuggable(BuildConfig.DEBUG).version(1)
        val ready = CompletableDeferred<IPrivilegedService>()
        val connection = object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) ready.complete(IPrivilegedService.Stub.asInterface(binder))
                else ready.completeExceptionally(IllegalStateException("Shizuku binderが失われました"))
            }
            override fun onServiceDisconnected(name: ComponentName?) { ready.completeExceptionally(IllegalStateException("Shizukuの接続が切れました")) }
            override fun onBindingDied(name: ComponentName?) { ready.completeExceptionally(IllegalStateException("Shizukuが終了しました")) }
        }
        try {
            withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
            val service = withTimeout(5000) { ready.await() }
            return withContext(Dispatchers.IO) { if (packageName == null) service.diagnostics() else service.forceStop(packageName) }
        } finally { withContext(NonCancellable + Dispatchers.Main) { runCatching { Shizuku.unbindUserService(args, connection, true) } } }
    }
}
