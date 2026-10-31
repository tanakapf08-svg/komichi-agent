package dev.komichi.agent

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.komichi.agent.data.ChatLine
import dev.komichi.agent.data.obj
import dev.komichi.agent.device.AgentAccessibilityService
import dev.komichi.agent.device.CaptureStore
import dev.komichi.agent.device.ScreenCaptureService
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity: ComponentActivity() {
    private val vm: AgentViewModel by viewModels()
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshStatus() }
    private val documents = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.take(20).forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); vm.preferences.addFile(uri.toString()) }
        }; vm.refreshStatus()
    }
    private val received = Shizuku.OnBinderReceivedListener { vm.refreshStatus() }
    private val dead = Shizuku.OnBinderDeadListener { vm.refreshStatus() }
    private val permission = Shizuku.OnRequestPermissionResultListener { _,_ -> vm.refreshStatus() }
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) startForegroundService(Intent(this,ScreenCaptureService::class.java).putExtra("consent",result.data))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        Shizuku.addBinderReceivedListenerSticky(received); Shizuku.addBinderDeadListener(dead); Shizuku.addRequestPermissionResultListener(permission)
        setContent { KomichiUi(vm, onPickFiles = { documents.launch(arrayOf("*/*")) }, onCapture = {
            capture.launch(getSystemService(android.media.projection.MediaProjectionManager::class.java).createScreenCaptureIntent())
        }, onNotifications = {
            if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }) }
    }
    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(dead); Shizuku.removeRequestPermissionResultListener(permission)
        super.onDestroy()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0) return false
        return super.dispatchTouchEvent(event)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomichiUi(vm: AgentViewModel, onPickFiles: () -> Unit, onNotifications: () -> Unit, onCapture: () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    var dark by rememberSaveable { mutableStateOf(systemDark) }
    val colors = if (dark) darkColorScheme(primary = Color(0xFF70D5B7), background = Color(0xFF111918), surface = Color(0xFF172220), surfaceVariant = Color(0xFF253531))
        else lightColorScheme(primary = Color(0xFF006B51), background = Color(0xFFF6FAF8), surface = Color(0xFFFFFFFF))
    MaterialTheme(colorScheme = colors) {
        val drawer = rememberDrawerState(DrawerValue.Closed); val scope = rememberCoroutineScope()
        var tab by rememberSaveable { mutableStateOf("chat") }
        var draft by rememberSaveable { mutableStateOf("") }
        val pending by vm.gate.pending.collectAsState()
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _,event -> if (event == Lifecycle.Event.ON_RESUME) vm.refreshStatus() }
            owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
        }
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            ModalDrawerSheet {
                Text("Komichi Agent", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { vm.newConversation(); tab = "chat"; scope.launch { drawer.close() } }, enabled = !vm.busy) { Text("＋ 新しい会話") }
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(vm.sessions, key = { it.id }) { conversation ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            TextButton(onClick = { vm.selectConversation(conversation.id); tab = "chat"; scope.launch { drawer.close() } }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text(conversation.title, maxLines = 2) }
                            TextButton(onClick = { vm.deleteConversation(conversation.id) }, enabled = !vm.busy) { Text("削除") }
                        }
                    }
                }
                Text("履歴は端末内で暗号化して保存します", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            }
        }) {
            Scaffold(topBar = {
                Column {
                    TopAppBar(title = { Text("Komichi Agent") }, navigationIcon = { TextButton(onClick = { scope.launch { drawer.open() } }) { Text("☰") } },
                        actions = { TextButton(onClick = { dark = !dark }) { Text(if (dark) "☀" else "☾") }; TextButton(onClick = { vm.newConversation() }, enabled = !vm.busy) { Text("＋") } })
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("chat" to "チャット", "settings" to "接続・権限", "logs" to "操作ログ").forEach { (value,label) ->
                            FilterChip(selected = tab == value, onClick = { tab = value }, label = { Text(label) })
                        }
                    }
                    Text(vm.status, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    if (vm.busy) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Button(onClick = vm::stop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("■ すべて停止") }
                    }
                }
            }, bottomBar = {
                if (tab == "chat") Surface(tonalElevation = 2.dp) {
                    Column(Modifier.imePadding().navigationBarsPadding().padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = draft, onValueChange = { draft = it.take(16_000) }, placeholder = { Text("端末への指示や質問を入力") },
                                enabled = !vm.busy, maxLines = 5, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f))
                            Button(onClick = { vm.send(draft); if (vm.model.isNotBlank()) draft = "" }, enabled = !vm.busy && draft.isNotBlank()) { Text("送信") }
                        }
                        Text("ChatGPT契約枠のみ使用 · API課金への自動切替なし", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when(tab) {
                        "settings" -> SettingsPane(vm, onPickFiles, onNotifications, onCapture)
                        "logs" -> LogsPane(vm)
                        else -> ChatPane(vm)
                    }
                }
            }
        }
        pending?.takeIf { !it.overlay }?.let { approval ->
            var review by remember(approval.id) { mutableStateOf(approval.detail) }
            AlertDialog(onDismissRequest = { vm.gate.resolve(approval.id, null) }, title = { Text(approval.title) },
                text = {
                    if (approval.editable) Column {
                        Text("機密情報を除いてください。確認済みの会話履歴とともにOpenAIへ送信します。拒否すると停止します。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = review, onValueChange = { review = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), minLines = 4)
                    } else SelectionContainer { Text(approval.detail, modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) }
                }, confirmButton = { Button(onClick = { vm.gate.resolve(approval.id, if (approval.editable) review else "approved") }) { Text(if (approval.editable) "この内容を送信" else "今回だけ許可") } },
                dismissButton = { TextButton(onClick = { vm.gate.resolve(approval.id, null) }) { Text("拒否") } })
        }
    }
}

@Composable private fun ChatPane(vm: AgentViewModel) {
    val lines = vm.current?.lines ?: emptyList(); val state = rememberLazyListState()
    LaunchedEffect(lines.size, vm.partial) { val total = lines.size + (if (vm.partial.isNotBlank()) 1 else 0); if (total > 0) state.scrollToItem(total - 1) }
    if (lines.isEmpty() && vm.partial.isBlank()) Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("あなたの端末に、\n確認できるアシスタント。", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text("「設定アプリを開いて」「使えるアプリを探して」\n\n接続・権限からChatGPT認証と対象アプリを設定してください。画面情報は送信前に確認でき、操作はいつでも停止できます。")
    } else LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(lines, key = { it.id }) { Message(it) }
        if (vm.partial.isNotBlank()) item { Message(ChatLine(id = "partial", role = "assistant", text = vm.partial)) }
    }
}

@Composable private fun Message(line: ChatLine) {
    val user = line.role == "user"
    Column(Modifier.fillMaxWidth().padding(start = if (user) 32.dp else 0.dp, end = if (user) 0.dp else 12.dp)
        .background(if (user) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(18.dp)).padding(12.dp)) {
        Text(when(line.role) { "user" -> "あなた"; "assistant" -> "Komichi"; "tool" -> "操作状況"; "local" -> "端末内テスト"; else -> "お知らせ" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        if (line.role == "assistant") MarkdownText(line.text) else SelectionContainer { Text(line.text, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable private fun MarkdownText(text: String) {
    val context = LocalContext.current
    val markwon = remember { Markwon.create(context) }
    val color = MaterialTheme.colorScheme.onSurface
    AndroidView(factory = { android.widget.TextView(it).apply { textSize = 16f; setTextIsSelectable(true); setPadding(0,0,0,0) } },
        update = { view -> view.setTextColor(android.graphics.Color.argb((color.alpha*255).toInt(),(color.red*255).toInt(),(color.green*255).toInt(),(color.blue*255).toInt())); markwon.setMarkdown(view,text) }, modifier = Modifier.fillMaxWidth())
}

@Composable private fun SettingsPane(vm: AgentViewModel, onPickFiles: () -> Unit, onNotifications: () -> Unit, onCapture: () -> Unit) {
    val context = LocalContext.current
    val version = vm.privilegeVersion
    var accountMenu by remember { mutableStateOf(false) }; var modelMenu by remember { mutableStateOf(false) }
    var disclosure by remember { mutableStateOf(false) }; var rootDisclosure by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf("") }
    val allowed = remember(version) { vm.preferences.allowed() }
    val apps = remember(query,version) { vm.broker.apps(query).take(100) }
    val shizuku = remember(version) { vm.shizuku.status() }
    val captured by CaptureStore.preview.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ChatGPTとの接続", style = MaterialTheme.typography.titleLarge)
        Text("公式の契約枠共有を使用します。アカウントや地域の条件で利用できない場合は停止します。契約枠は他の対応アプリと共有されます。", style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(onClick = { accountMenu = true }, enabled = !vm.busy) { Text(vm.activeAccount?.label ?: "保存済みアカウントを選択") }
            DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                vm.accounts.forEach { account -> DropdownMenuItem(text = { Text(account.label + if (!account.connected) "（再認証が必要）" else "") }, onClick = { vm.selectAccount(account.clientId); accountMenu = false }) }
            }
        }
        Button(onClick = { vm.login(vm.activeAccount) }, enabled = !vm.busy) { Text("Continue with ChatGPT") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.login(null) }, enabled = !vm.busy) { Text("別のアカウントを追加") }
            TextButton(onClick = vm::signOut, enabled = !vm.busy && vm.activeAccount != null) { Text("サインアウト") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::fetchModels, enabled = !vm.busy && vm.activeAccount?.connected == true) { Text("モデルを取得") }
            Box {
                OutlinedButton(onClick = { modelMenu = true }, enabled = !vm.busy && vm.models.isNotEmpty()) { Text(vm.models.firstOrNull { it.id == vm.model }?.name ?: "モデルを選択") }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    vm.models.forEach { model -> DropdownMenuItem(text = { Text(model.name) }, onClick = { vm.pickModel(model.id); modelMenu = false }) }
                }
            }
        }
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://chatgpt.com/settings/usage"))) }) { Text("ChatGPTで利用枠・アプリの接続を管理") }
        HorizontalDivider()
        Text("端末の権限", style = MaterialTheme.typography.titleLarge)
        Text("Accessibility: ${if (AgentAccessibilityService.instance != null) "接続中" else "無効"}")
        OutlinedButton(onClick = { disclosure = true }, enabled = !vm.busy) { Text("画面操作の説明・OS設定") }
        OutlinedButton(onClick = onNotifications, enabled = !vm.busy) { Text("停止通知の表示を許可") }
        Text("通常権限での画面取得", style = MaterialTheme.typography.titleMedium)
        Text("OSの共有ダイアログで許可した画面を1枚だけ取得します。選択後に対象画面へ移動してください。画像はメモリ内の確認用で、AIへは送信しません。", style = MaterialTheme.typography.bodySmall)
        Row {
            OutlinedButton(onClick = onCapture, enabled = !vm.busy && !captured.running) { Text("画面を選んで取得") }
            TextButton(onClick = { CaptureStore.cancel?.invoke(); CaptureStore.clear() }) { Text(if (captured.running) "取得を中止" else "画像を消去") }
        }
        Text(captured.message, style = MaterialTheme.typography.bodySmall)
        captured.bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "ユーザーが許可した画面の端末内プレビュー", modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) }
        Text("Shizuku: ${if (shizuku.optBoolean("connected")) "接続中" else "未接続"} / ${if (shizuku.optBoolean("permission")) "許可済み" else "未許可"}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { runCatching { vm.shizuku.requestPermission() }.onFailure { localError = it.message ?: "Shizukuが利用できません" } }, enabled = !vm.busy) { Text("Shizuku権限を要求") }
            TextButton(onClick = vm::refreshStatus) { Text("再確認") }
        }
        OutlinedButton(onClick = { vm.manualTool("system_diagnostics") }, enabled = !vm.busy && shizuku.optBoolean("permission")) { Text("Shizuku診断（端末内）") }
        Row {
            Checkbox(checked = remember(version) { vm.preferences.rootEnabled() }, onCheckedChange = { enabled -> if (enabled) rootDisclosure = true else { vm.preferences.setRoot(false); vm.refreshStatus() } }, enabled = !vm.busy)
            Text("Root連携を許可する", Modifier.padding(top = 12.dp))
        }
        OutlinedButton(onClick = { vm.manualTool("check_root_status") }, enabled = !vm.busy && vm.preferences.rootEnabled()) { Text("Root UIDを確認（端末内）") }
        if (localError.isNotBlank()) Text(localError, color = MaterialTheme.colorScheme.error)
        HorizontalDivider()
        Text("ファイルの参照", style = MaterialTheme.typography.titleLarge)
        Text("システムの選択画面で指定したファイルだけを参照します。AIには確認後にファイル情報のみを送れます。内容の自動アップロードは行いません。", style = MaterialTheme.typography.bodySmall)
        Row {
            OutlinedButton(onClick = onPickFiles, enabled = !vm.busy) { Text("ファイルを選択") }
            TextButton(onClick = {
                vm.preferences.files().forEach { raw -> runCatching { context.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(raw), Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                vm.preferences.clearFiles(); vm.refreshStatus()
            }, enabled = !vm.busy) { Text("選択を解除") }
        }
        Text("選択済み: ${remember(version) { vm.preferences.files().size }} 件")
        OutlinedButton(onClick = { vm.manualTool("list_files") }, enabled = !vm.busy) { Text("ファイル情報を確認（端末内）") }
        HorizontalDivider()
        Text("操作を許可するアプリ", style = MaterialTheme.typography.titleLarge)
        Text("初期状態はすべて不許可です。チェック後も操作ごとに確認します。設定・認証などの保護画面は操作できません。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("起動可能なアプリを検索") }, modifier = Modifier.fillMaxWidth())
        apps.forEach { item ->
            Row(Modifier.fillMaxWidth()) {
                Checkbox(checked = item.packageName in allowed, onCheckedChange = { vm.preferences.allow(item.packageName,it); vm.refreshStatus() }, enabled = !vm.busy && item.packageName != context.packageName)
                Column(Modifier.weight(1f).padding(top = 8.dp)) { Text(item.label); Text(item.packageName, style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { vm.manualTool("open_app",obj("package" to item.packageName)) }, enabled = !vm.busy && item.packageName in allowed) { Text("起動") }
            }
        }
        Text("表示は最大100件。検索で絞り込めます。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("画面操作へのアクセス") },
        text = { Text("有効にすると、Komichiは許可したアプリの画面構造を読み取り、承認後にタップ・スクロール・文字入力・戻る・ホーム操作を行えます。画面内の情報には機密情報が含まれる場合があります。AIへの送信前に内容を確認・編集できます。常時収集は行いません。\n\nOS設定でユーザー自身が有効化してください。停止ボタンは実行中に表示されます。") },
        confirmButton = { Button(onClick = { disclosure = false; context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("OS設定を開く") } },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text("閉じる") } })
    if (rootDisclosure) AlertDialog(onDismissRequest = { rootDisclosure = false }, title = { Text("Root機能を有効にする") },
        text = { Text("Root管理アプリへの許可要求が発生することがあります。機能は固定のUID確認と、許可済み一般アプリの強制停止に限定しています。操作ごとに対象と影響を確認します。Rootがなくても通常機能は利用できます。") },
        confirmButton = { Button(onClick = { vm.preferences.setRoot(true); vm.refreshStatus(); rootDisclosure = false }) { Text("有効にする") } },
        dismissButton = { TextButton(onClick = { rootDisclosure = false }) { Text("キャンセル") } })
}

@Composable private fun LogsPane(vm: AgentViewModel) {
    val logs by vm.audit.items.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("端末内の操作ログ", style = MaterialTheme.typography.titleLarge)
        Text("時刻・ツール名・状態だけを保存します。引数、画面情報、認証情報は記録しません。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { vm.audit.clear() }, enabled = !vm.busy) { Text("ログを消去") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) { items(logs.reversed()) { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
}
