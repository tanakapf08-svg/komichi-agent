package dev.komichi.agent.auth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import dev.komichi.agent.MainActivity
import dev.komichi.agent.R

class LoginService: Service() {
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { LoginControl.cancel?.invoke(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("login", "ChatGPT認証", NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(this, 10, Intent(this, LoginService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2, Notification.Builder(this, "login").setSmallIcon(R.drawable.ic_compass)
            .setContentTitle("ChatGPT認証を待っています").setContentText("ブラウザで完了後、アプリへ戻ってください")
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "認証を中止", cancel).build()).setOngoing(true).build())
        return START_NOT_STICKY
    }
}
