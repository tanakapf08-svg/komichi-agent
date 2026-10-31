package dev.komichi.agent.auth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import dev.komichi.agent.MainActivity
import dev.komichi.agent.R

class LoginService: Service() {
    companion object {
        private var desiredSession = ""
        private var live: LoginService? = null
        private val waiting = mutableMapOf<String,CompletableDeferred<Unit>>()
        suspend fun startAndAwait(context: Context): String = withContext(Dispatchers.Main.immediate) {
            val session = UUID.randomUUID().toString(); val ready = CompletableDeferred<Unit>()
            desiredSession = session; waiting[session] = ready
            try {
                context.startForegroundService(Intent(context,LoginService::class.java).putExtra("session",session))
                withTimeout(4500) { ready.await() }; session
            } catch(e: Exception) { finish(session); throw e }
            finally { if(waiting[session] === ready) waiting.remove(session) }
        }
        fun finish(session: String) {
            if(session.isBlank() || desiredSession != session) return
            desiredSession = ""
            live?.takeIf { it.foreground && it.session == session }?.stopSelf()
        }
    }
    private var foreground = false
    private var session = ""
    override fun onCreate() { super.onCreate(); live = this }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("login", "ChatGPT認証", NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(this, 10, Intent(this, LoginService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2, Notification.Builder(this, "login").setSmallIcon(R.drawable.ic_compass)
            .setContentTitle("ChatGPT認証を待っています").setContentText("ブラウザで完了後、アプリへ戻ってください")
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "認証を中止", cancel).build()).setOngoing(true).build())
        foreground = true; session = desiredSession
        intent?.getStringExtra("session")?.let { waiting[it]?.complete(Unit) }
        if (intent?.action == "cancel") { LoginControl.cancel?.invoke(); finish(session); stopSelf(); return START_NOT_STICKY }
        if(desiredSession.isBlank()) stopSelf(startId)
        return START_NOT_STICKY
    }
    override fun onDestroy() { if(live === this) live = null; super.onDestroy() }
}
