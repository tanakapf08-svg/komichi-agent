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
import dev.komichi.agent.agent.RunMonitor
import dev.komichi.agent.agent.RunProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import android.content.Context

class RunService: Service() {
    companion object {
        private val waiting = mutableMapOf<String,CompletableDeferred<Unit>>()
        private var live: RunService? = null
        /** Do not finish a fast task before Android has accepted its foreground notification. */
        suspend fun startAndAwait(context: Context, session: String) = withContext(Dispatchers.Main.immediate) {
            val ready = CompletableDeferred<Unit>(); waiting[session] = ready
            try {
                context.startForegroundService(Intent(context,RunService::class.java).putExtra("session",session))
                withTimeout(4500) { ready.await() }
            } finally { if(waiting[session] === ready) waiting.remove(session) }
        }
        fun stopAfterReady(session: String) {
            val service = live ?: return
            // A cancelled startup must still call startForeground in onStartCommand;
            // stopping it before that can crash the process several seconds later.
            if(service.foreground && service.session == session && !RuntimeControl.running) service.stopSelf()
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing = false
    private var foreground = false
    private var session = ""
    override fun onCreate() { super.onCreate(); live = this }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("agent", "端末操作の実行", NotificationManager.IMPORTANCE_LOW))
        startForeground(1,notification(RunMonitor.tracker.state.value))
        foreground = true
        session = RuntimeControl.sessionId
        intent?.getStringExtra("session")?.let { waiting[it]?.complete(Unit) }
        if (intent?.action == "stop") { RuntimeControl.emergencyStop(); stopSelf(); return START_NOT_STICKY }
        if(!RuntimeControl.running) { stopSelf(startId); return START_NOT_STICKY }
        if(!observing) {
            observing = true
            scope.launch { RunMonitor.tracker.state.collect { progress ->
                if(progress.active) { nm.notify(1,notification(progress)); AgentAccessibilityService.instance?.updateStopLabel(progress.phase.label) }
            } }
        }
        return START_NOT_STICKY
    }
    private fun notification(progress: RunProgress): Notification {
        val stop = PendingIntent.getService(this, 20, Intent(this, RunService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 21, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "agent").setSmallIcon(R.drawable.ic_compass).setContentTitle("Komichi Agent · " + if(progress.active) "動作中" else "終了処理")
            .setContentText(progress.phase.label + if(progress.step > 0) " · 操作 ${progress.step}/${progress.maximum}" else "")
            .setSubText("承認と停止はいつでも可能です").setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setWhen(progress.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis()).setUsesChronometer(true)
            .setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null, "緊急停止", stop).build()).setOngoing(true).build()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { RuntimeControl.emergencyStop(); stopSelf() }
    override fun onDestroy() {
        scope.cancel(); if(live === this) live = null
        if (RuntimeControl.running && RuntimeControl.sessionId == session) RuntimeControl.emergencyStop()
        super.onDestroy()
    }
}
