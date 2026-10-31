package dev.komichi.agent.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import dev.komichi.agent.KomichiApp
import dev.komichi.agent.data.array
import dev.komichi.agent.data.obj
import dev.komichi.agent.security.Approval
import dev.komichi.agent.security.ApprovalGate
import dev.komichi.agent.security.Privacy
import dev.komichi.agent.security.RuntimeControl
import dev.komichi.agent.security.AppAccessPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

data class ScreenNode(val id: String, val path: List<Int>, val label: String, val klass: String,
    val editable: Boolean, val password: Boolean, val bounds: Rect)
data class ScreenSnapshot(val id: String, val packageName: String, val fingerprint: String,
    val nodes: List<ScreenNode>, val sensitive: Boolean, val sessionId: String, val capturedAt: Long)

class AgentAccessibilityService: AccessibilityService() {
    companion object {
        @Volatile private var reference = WeakReference<AgentAccessibilityService>(null)
        val instance: AgentAccessibilityService? get() = reference.get()
    }
    private var snapshot: ScreenSnapshot? = null
    private var dialog: AlertDialog? = null
    private var stopButton: Button? = null
    private val handler = Handler(Looper.getMainLooper())
    private val protectedAction = Regex("(?i)購入|送金|支払|決済|アンインストール|インストール|許可|パスワード|認証|\\b(pay|buy|purchase|uninstall|install|allow|password|verify)\\b")
    private val prefs get() = (application as KomichiApp).preferences
    override fun onServiceConnected() { reference = WeakReference(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* No passive capture, logging or upload. */ }
    override fun onInterrupt() { RuntimeControl.emergencyStop(); snapshot = null }
    override fun onDestroy() { if (RuntimeControl.running) RuntimeControl.emergencyStop(); dismissApproval(); showStop(false); reference.clear(); super.onDestroy() }

    private fun root(): AccessibilityNodeInfo {
        RuntimeControl.requireRunning()
        val node = rootInActiveWindow ?: error("画面情報を取得できません")
        val pkg = node.packageName?.toString() ?: error("対象アプリを特定できません")
        require(AppAccessPolicy.allows(pkg, packageName, prefs.fullAccessEnabled(), prefs.allowed()) && !protectedPackage(pkg)) { "この画面は操作許可対象ではありません" }
        return node
    }
    fun requireAllowedForeground() { root().recycle() }
    private fun protectedPackage(pkg: String) = pkg == "android" || listOf("com.android.systemui", "com.android.settings", "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller").any { pkg == it || pkg.startsWith("$it.") }
    private fun scan(): ScreenSnapshot {
        val nodes = ArrayList<ScreenNode>(); val raw = StringBuilder(); var sensitive = false; var visited = 0
        val root = root()
        val pkg = root.packageName.toString()
        fun walk(node: AccessibilityNodeInfo, path: List<Int>, depth: Int) {
            if (++visited > 300 || depth > 20) return
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ").take(1000)
            raw.append(path).append(node.viewIdResourceName).append(text).append(bounds).append(node.isEnabled).append(node.isEditable)
            if (node.isPassword || (!node.isEditable && (Privacy.sensitive(text) || protectedAction.containsMatchIn(text)))) sensitive = true
            if (node.isVisibleToUser && !bounds.isEmpty && nodes.size < 60 && (text.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable)) {
                val label = if (node.isPassword) "[保護された入力欄]" else if (node.isEditable) "[入力欄・内容非表示]" else Privacy.mask(text).take(160)
                nodes.add(ScreenNode("n${nodes.size}", path, label, node.className?.toString() ?: "", node.isEditable, node.isPassword, bounds))
            }
            for (i in 0 until node.childCount.coerceAtMost(100)) {
                val child = node.getChild(i) ?: continue
                try { walk(child, path + i, depth + 1) } finally { child.recycle() }
            }
        }
        try { raw.append(root.windowId); walk(root, emptyList(), 0) } finally { root.recycle() }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(raw.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        return ScreenSnapshot(UUID.randomUUID().toString(), pkg, fingerprint, nodes, sensitive, RuntimeControl.sessionId, android.os.SystemClock.elapsedRealtime())
    }
    suspend fun readScreen(): JSONObject = withContext(Dispatchers.Main) {
        val value = scan(); snapshot = value
        obj("untrusted_observation" to true, "snapshot_id" to value.id, "package" to value.packageName,
            "sensitive_screen" to value.sensitive, "elements" to array(value.nodes.map {
                obj("element_id" to it.id, "label" to it.label, "class" to it.klass, "editable" to it.editable,
                    "bounds" to array(listOf(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom)))
            }))
    }
    private fun unchanged(id: String): ScreenSnapshot {
        val old = snapshot ?: error("先に画面情報を取得してください")
        require(old.id == id) { "画面IDが古いため中止しました" }
        require(old.sessionId == RuntimeControl.sessionId && android.os.SystemClock.elapsedRealtime() - old.capturedAt <= 120_000) { "前の実行または期限切れの画面IDです。再取得してください" }
        val fresh = scan()
        require(old.packageName == fresh.packageName && old.fingerprint == fresh.fingerprint) { "画面が変化したため中止しました。画面情報を再取得してください" }
        require(!fresh.sensitive) { "機密情報を含む可能性がある画面です。操作は手動で行ってください" }
        return old
    }
    suspend fun describeOperation(name: String, arguments: JSONObject): String = withContext(Dispatchers.Main) {
        if (!arguments.has("snapshot_id")) {
            val active = root(); try { "対象アプリ: ${active.packageName}" } finally { active.recycle() }
        } else {
            val screen = unchanged(arguments.getString("snapshot_id"))
            val element = arguments.optString("element_id").takeIf { it.isNotBlank() }?.let { id -> screen.nodes.firstOrNull { it.id == id } ?: error("要素がありません") }
            "対象アプリ: ${screen.packageName}\n操作: $name" + (element?.let { "\n要素: ${it.label.ifBlank { "（ラベルなし）" }}\n種類: ${it.klass}\n位置: ${it.bounds.toShortString()}" } ?: "")
        }
    }
    private fun nodeAt(path: List<Int>): AccessibilityNodeInfo {
        var node = root()
        path.forEach { index -> val next = node.getChild(index); node.recycle(); node = next ?: error("UI要素が消失しました") }
        return node
    }
    suspend fun operate(name: String, arguments: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        // Let the approval overlay close, then validate the actual underlying app again.
        delay(250); RuntimeControl.requireRunning()
        if (name == "navigate_back" || name == "navigate_home") {
            requireAllowedForeground(); val action = if (name == "navigate_home") GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK
            require(performGlobalAction(action)) { "Androidがナビゲーションを拒否しました" }
        } else {
            val current = unchanged(arguments.getString("snapshot_id"))
            when (name) {
                "tap_element", "input_text" -> {
                    val element = current.nodes.firstOrNull { it.id == arguments.getString("element_id") } ?: error("要素がありません")
                    require(!element.password && !Regex("(?i)購入|送金|支払|決済|削除|アンインストール|インストール|許可|パスワード|認証|\\b(pay|buy|purchase|delete|uninstall|install|allow|password|verify)\\b").containsMatchIn(element.label)) { "保護操作は手動で行ってください" }
                    nodeAt(element.path).let { node ->
                        try {
                            require(node.isEnabled && node.isVisibleToUser && !node.isPassword)
                            if (name == "input_text") {
                                require(node.isEditable)
                                val text = arguments.getString("text"); require(!Privacy.sensitive(text))
                                require(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })) { "テキスト入力が拒否されました" }
                            } else {
                                if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) gesture(element.bounds.centerX().toFloat(), element.bounds.centerY().toFloat(), element.bounds.centerX().toFloat(), element.bounds.centerY().toFloat(), 60)
                            }
                        } finally { node.recycle() }
                    }
                }
                "scroll_screen" -> {
                    val root = root()
                    fun scroll(node: AccessibilityNodeInfo): Boolean {
                        val action = if (arguments.getString("direction") == "down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        if (node.isScrollable && node.performAction(action)) return true
                        for (i in 0 until node.childCount) {
                            val child = node.getChild(i) ?: continue
                            val done = try { scroll(child) } finally { child.recycle() }; if (done) return true
                        }
                        return false
                    }
                    try { require(scroll(root)) { "スクロール可能なUIがありません" } } finally { root.recycle() }
                }
                "swipe_screen" -> {
                    val metrics = resources.displayMetrics
                    val x = metrics.widthPixels * .5f; val y = metrics.heightPixels * .5f
                    val dx = metrics.widthPixels * .22f; val dy = metrics.heightPixels * .22f
                    when(arguments.getString("direction")) {
                        "up" -> gesture(x,y+dy,x,y-dy,350)
                        "down" -> gesture(x,y-dy,x,y+dy,350)
                        "left" -> gesture(x+dx,y,x-dx,y,350)
                        "right" -> gesture(x-dx,y,x+dx,y,350)
                    }
                }
            }
        }
        snapshot = null
        obj("status" to "dispatched", "note" to "Android accepted the action. Read a fresh screen to verify the outcome.")
    }
    private suspend fun gesture(x1: Float,y1: Float,x2: Float,y2: Float,duration: Long) = suspendCancellableCoroutine<Unit> { continuation ->
        RuntimeControl.requireRunning()
        val path = Path().apply { moveTo(x1,y1); if (x1 != x2 || y1 != y2) lineTo(x2,y2) }
        val accepted = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,duration)).build(), object: GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(Unit) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.cancel() }
        }, handler)
        if (!accepted) continuation.cancel()
    }
    fun showApproval(item: Approval, gate: ApprovalGate) {
        dismissApproval()
        val builder = AlertDialog.Builder(this).setTitle(item.title)
        val editor = if (item.editable) EditText(this).apply { setText(item.detail); minLines = 4; maxLines = 12; setSelectAllOnFocus(false); importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS } else null
        if (editor != null) builder.setView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24,12,24,12)
            addView(android.widget.TextView(this@AgentAccessibilityService).apply { text = "内容を編集して機密情報を除いてください。確認済みの会話履歴とともにOpenAIへ送信します。" })
            addView(editor)
        }) else builder.setMessage(item.detail)
        val created = builder.setPositiveButton(if (item.editable) "この内容を送信" else "今回だけ許可") { _,_ -> gate.resolve(item.id, editor?.text?.toString() ?: "approved") }
            .setNegativeButton("拒否") { _,_ -> gate.resolve(item.id, null) }
            .setNeutralButton("すべて停止") { _,_ -> gate.resolve(item.id, null); RuntimeControl.emergencyStop() }
            .setOnCancelListener { gate.resolve(item.id, null) }.create()
        created.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        created.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog = created
        try { created.show(); created.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true }
        catch (_: Exception) { dialog = null; gate.resolve(item.id, null) }
    }
    fun dismissApproval() { dialog?.dismiss(); dialog = null }
    fun updateStopLabel(label: String) { stopButton?.text = "動作中 · $label\n■ 停止" }
    fun showStop(visible: Boolean) {
        val wm = getSystemService(WindowManager::class.java)
        stopButton?.let { runCatching { wm.removeView(it) } }; stopButton = null
        if (!visible) return
        val button = Button(this).apply { text = "動作中\n■ 停止"; filterTouchesWhenObscured = true; setOnClickListener { RuntimeControl.emergencyStop() } }
        val layout = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; y = 80 }
        runCatching { wm.addView(button, layout); stopButton = button }
    }
}
