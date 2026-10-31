package dev.komichi.agent.device

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import dev.komichi.agent.agent.ToolCall
import dev.komichi.agent.agent.ToolCatalog
import dev.komichi.agent.data.AuditLog
import dev.komichi.agent.data.array
import dev.komichi.agent.data.obj
import dev.komichi.agent.security.ApprovalGate
import dev.komichi.agent.security.Privacy
import dev.komichi.agent.security.RuntimeControl
import dev.komichi.agent.security.AppAccessPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class LaunchableApp(val packageName: String, val label: String, val system: Boolean, val launchable: Boolean = true)
class UserDenied: Exception("ユーザーが拒否したため停止しました")

class DeviceBroker(private val context: Context, private val preferences: DevicePreferences,
    private val gate: ApprovalGate, private val audit: AuditLog, val shizuku: ShizukuBridge) {
    private val rootFiles = RootFileOperations(context,preferences)
    fun apps(query: String = ""): List<LaunchableApp> {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.activityInfo.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) }
            .distinctBy { it.packageName }
        val apps = if (preferences.fullAccessEnabled()) {
            val launchableIds = launchable.map { it.packageName }.toSet()
            pm.getInstalledApplications(0).map { LaunchableApp(it.packageName, pm.getApplicationLabel(it).toString(),
                it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0, it.packageName in launchableIds) }
        } else launchable
        return apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }.sortedBy { it.label }
    }
    private fun allowedPackage(pkg: String): LaunchableApp {
        require(AppAccessPolicy.allows(pkg, context.packageName, preferences.fullAccessEnabled(), preferences.allowed())) { "対象アプリを選択するか、全アクセスモードを有効にしてください" }
        return apps().firstOrNull { it.packageName == pkg } ?: error("対象アプリがありません")
    }
    private fun accessibility() = AgentAccessibilityService.instance ?: error("Accessibilityをユーザーが有効にする必要があります")
    suspend fun execute(call: ToolCall, shareResult: Boolean = true): String {
        ToolCatalog.validate(call); RuntimeControl.requireRunning(); currentCoroutineContext().ensureActive()
        audit.add(call.name, "requested")
        try {
            val a = call.arguments
            val mutating = call.name in setOf("open_app", "open_settings", "tap_element", "swipe_screen", "scroll_screen", "input_text", "navigate_back", "navigate_home", "check_root_status", "system_diagnostics", "force_stop_app", "list_app_data", "copy_app_data")
            if (a.has("package")) allowedPackage(a.getString("package"))
            val copyPlan = if (call.name == "copy_app_data") rootFiles.plan(a.getString("package"),a.getString("source_path"),a.getString("destination"),a.getString("format")) else null
            if (call.name in setOf("list_app_data","copy_app_data")) require(preferences.rootEnabled()) { "内部データ操作には「接続・権限」でRoot連携を有効にしてください" }
            if (call.name == "force_stop_app") require(!allowedPackage(a.getString("package")).system) { "システムアプリは停止できません" }
            if (mutating) {
                val effect = if (copyPlan != null) rootFiles.describe(copyPlan) else if (call.name == "list_app_data") "対象アプリの内部データ領域をRootで一覧取得します。ファイルの中身は読み取り・送信しません。" else if (call.name == "force_stop_app") "対象アプリを強制停止します。未保存データが失われる可能性があります。" else "指定されたAndroid操作を1回実行します。"
                val screenTarget = if (call.name in setOf("tap_element", "swipe_screen", "scroll_screen", "input_text", "navigate_back", "navigate_home")) accessibility().describeOperation(call.name,a) else ""
                if (gate.ask("操作を確認: ${call.name}", "$effect\n$screenTarget\n\n対象と引数:\n${a.toString(2)}") == null) throw UserDenied()
            }
            RuntimeControl.requireRunning(); currentCoroutineContext().ensureActive()
            val timeout = if (call.name == "copy_app_data") 190_000L else if (call.name == "list_app_data") 30_000L else 10_000L
            val result = withTimeout(timeout) {
                when (call.name) {
                    "search_apps" -> withContext(Dispatchers.IO) { obj("untrusted_observation" to true, "apps" to array(apps(a.getString("query")).take(100).map { obj("package" to it.packageName, "label" to Privacy.mask(it.label), "launchable" to it.launchable) })) }
                    "open_app" -> withContext(Dispatchers.Main) {
                        allowedPackage(a.getString("package"))
                        val intent = context.packageManager.getLaunchIntentForPackage(a.getString("package")) ?: error("アプリを起動できません")
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); obj("status" to "launch_requested")
                    }
                    "open_settings" -> withContext(Dispatchers.Main) {
                        val actions = mapOf("general" to Settings.ACTION_SETTINGS, "wifi" to Settings.ACTION_WIFI_SETTINGS, "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
                            "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS, "display" to Settings.ACTION_DISPLAY_SETTINGS, "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS)
                        context.startActivity(Intent(actions.getValue(a.getString("page"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); obj("status" to "settings_requested")
                    }
                    "list_files" -> withContext(Dispatchers.IO) { obj("untrusted_observation" to true, "files" to array(preferences.files().mapIndexed { index, raw ->
                        val uri = Uri.parse(raw)
                        var name = "document"; var size: Long? = null
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) { name = cursor.getString(0); if (!cursor.isNull(1)) size = cursor.getLong(1) }
                        }
                        obj("selection" to index, "name" to Privacy.mask(name), "size" to size, "mime" to context.contentResolver.getType(uri))
                    })) }
                    "read_screen" -> accessibility().readScreen()
                    "list_directory" -> withContext(Dispatchers.IO) { SharedStorage.list(context,preferences,a.getString("path")) }
                    "list_app_data", "copy_app_data" -> {
                        try {
                            if (copyPlan != null) rootFiles.copy(copyPlan) else rootFiles.list(a.getString("package"),a.getString("path"))
                        } catch (e: RootFileFailure) { obj("status" to "failed","code" to e.code,"detail" to e.diagnostic,"contents_sent_to_ai" to false) }
                    }
                    "tap_element", "swipe_screen", "scroll_screen", "input_text", "navigate_back", "navigate_home" -> accessibility().operate(call.name,a)
                    "check_shizuku_status" -> shizuku.status()
                    "system_diagnostics" -> obj("untrusted_observation" to true, "diagnostics" to shizuku.run())
                    "check_root_status" -> {
                        require(preferences.rootEnabled()) { "Root連携は初期状態では無効です" }
                        obj("root" to true, "status" to RootBridge.run(RootBridge.Operation.IDENTITY))
                    }
                    "force_stop_app" -> {
                        val app = allowedPackage(a.getString("package")); require(!app.system)
                        if (a.getString("backend") == "root") {
                            require(preferences.rootEnabled()); RootBridge.run(RootBridge.Operation.IDENTITY)
                            obj("status" to RootBridge.run(RootBridge.Operation.FORCE_STOP, app.packageName))
                        } else obj("status" to shizuku.run(app.packageName))
                    }
                    else -> error("許可されていないツール")
                }
            }
            currentCoroutineContext().ensureActive(); RuntimeControl.requireRunning()
            audit.add(call.name, if (result.optString("status") == "failed") "failed" else "executed")
            // Untrusted labels and filenames are masked at collection time. Do not
            // redact structural snapshot/element identifiers or the next call cannot use them.
            val masked = result.toString(2)
            if (!shareResult) return masked
            val reviewed = gate.ask("AIへ送る結果を確認", masked, editable = true) ?: throw UserDenied()
            require(reviewed.length <= 32_000) { "送信結果が大きすぎます。確認画面で不要な内容を減らしてください" }
            RuntimeControl.requireRunning(); currentCoroutineContext().ensureActive()
            // Encode the reviewed text as data, never promote it to a user/developer message.
            audit.add(call.name, "result_shared")
            return obj("untrusted_observation" to true, "reviewed_result" to reviewed).toString()
        } catch (e: CancellationException) { audit.add(call.name, "cancelled"); throw e }
          catch (e: Exception) { audit.add(call.name, if (e is UserDenied) "denied" else "failed"); throw e }
    }
}
