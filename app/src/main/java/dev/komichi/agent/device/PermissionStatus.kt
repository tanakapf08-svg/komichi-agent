package dev.komichi.agent.device

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager

data class PermissionStatus(val name: String, val state: String, val detail: String)

object PermissionStatuses {
    fun read(context: Context, preferences: DevicePreferences, shizuku: ShizukuBridge): List<PermissionStatus> {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val accessibilityEnabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName && it.resolveInfo.serviceInfo.name == AgentAccessibilityService::class.java.name }
        val notifications = context.getSystemService(NotificationManager::class.java)
        val runtimeNotifications = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channel = notifications.getNotificationChannel("agent")
        val notificationEnabled = runtimeNotifications && notifications.areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
        val shizukuState = shizuku.status()
        val readableDocuments = context.contentResolver.persistedUriPermissions.count { it.isReadPermission && it.uri.toString() in preferences.files() }
        val root = RootBridge.status.value
        val foregroundGranted = (Build.VERSION.SDK_INT < 28 || context.checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE) == PackageManager.PERMISSION_GRANTED) &&
            (Build.VERSION.SDK_INT < 34 || context.checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE) == PackageManager.PERMISSION_GRANTED)
        return listOf(
            PermissionStatus("ネットワーク接続", if (context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED) "有効" else "未許可", "ChatGPTへの通信に使用"),
            PermissionStatus("実行中のサービス", if (foregroundGranted) "許可済み" else "未許可", "ユーザーが開始した操作と停止通知の維持に使用"),
            PermissionStatus("実行中の停止通知", if (notificationEnabled) "有効" else "無効・未許可", "OSの通知許可と停止通知チャンネルを確認"),
            PermissionStatus("画面操作（Accessibility）", if (AgentAccessibilityService.instance != null) "有効・接続中" else if (accessibilityEnabled) "有効・接続待ち" else "無効", "本人がOS設定で有効化する必要があります"),
            PermissionStatus("画面画像の取得", if (CaptureStore.preview.value.running) "取得中" else "毎回確認", "永続的な許可ではありません。OSが取得ごとに確認します"),
            PermissionStatus("音声入力のマイク",if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "許可済み" else "未許可","音声入力ボタンから本人が開始した時だけ使用。Android 12以降の端末内認識のみ対応"),
            PermissionStatus("アプリへのアクセス", if (preferences.fullAccessEnabled()) "全アプリ対象" else "選択したアプリのみ", "全モードでも認証・権限などの保護画面と操作承認は維持"),
            PermissionStatus("共有ストレージ", if (SharedStorage.granted(context)) "OS許可済み" else "未許可", if (preferences.fullAccessEnabled()) "OSの許可範囲でディレクトリ一覧を参照できます" else "全アクセスモードを有効にしたときだけ使用"),
            PermissionStatus("選択ファイル（SAF）", if (readableDocuments > 0) "$readableDocuments 件許可済み" else "未選択・許可なし", "本人が選択したファイルの読み取り許可"),
            PermissionStatus("Shizukuの接続", if (shizukuState.optBoolean("connected")) "接続中" else "未接続", "Shizuku側の起動が必要"),
            PermissionStatus("Shizukuの権限", if (shizukuState.optBoolean("permission")) "許可済み（UID ${shizukuState.optInt("uid") }）" else "未許可", "接続と権限は別々に確認します"),
            PermissionStatus("Root連携の設定", if (preferences.rootEnabled()) "有効" else "無効", "この設定だけではRoot権限は付与されません"),
            PermissionStatus("Rootの実利用", if (root.checking) "確認中" else if (!root.checked) "未確認" else if (root.granted) "前回確認：利用可能" else "前回確認：利用不可", root.message)
        )
    }
}
