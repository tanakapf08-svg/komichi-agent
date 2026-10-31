package dev.komichi.agent.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import dev.komichi.agent.MainActivity
import dev.komichi.agent.R
import dev.komichi.agent.security.RuntimeControl

class RunService: Service() {
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { RuntimeControl.emergencyStop(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("agent", "端末操作の実行", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 20, Intent(this, RunService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 21, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "agent").setSmallIcon(R.drawable.ic_compass).setContentTitle("Komichi Agent 実行中")
            .setContentText("操作には確認が必要です。いつでも停止できます").setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "緊急停止", stop).build()).setOngoing(true).build())
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) { RuntimeControl.emergencyStop(); stopSelf() }
    override fun onDestroy() { if (RuntimeControl.running) RuntimeControl.emergencyStop(); super.onDestroy() }
}
