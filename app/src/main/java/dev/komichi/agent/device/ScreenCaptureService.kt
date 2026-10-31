package dev.komichi.agent.device

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import dev.komichi.agent.R
import kotlinx.coroutines.flow.MutableStateFlow

data class CapturePreview(val bitmap: Bitmap? = null, val message: String = "未取得", val running: Boolean = false)
object CaptureStore {
    val preview = MutableStateFlow(CapturePreview())
    var cancel: (() -> Unit)? = null
    fun clear() { preview.value = CapturePreview() }
}

/** One user-consented local frame. Never written to disk or sent over the network. */
class ScreenCaptureService: Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var finished = false
    private val handler = Handler(Looper.getMainLooper())
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { finishCapture("画面取得を中止しました"); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("capture", "画面情報の取得", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 30, Intent(this, ScreenCaptureService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(3, Notification.Builder(this,"capture").setSmallIcon(R.drawable.ic_compass).setContentTitle("画面情報を1枚だけ取得します")
            .setContentText("対象画面へ移動してください。画像は端末内のプレビューのみです")
            .addAction(Notification.Action.Builder(null,"取得を中止",stop).build()).setOngoing(true).build())
        CaptureStore.cancel = { finishCapture("画面取得を中止しました") }
        CaptureStore.preview.value = CapturePreview(message = "対象画面へ移動してください。取得は最大15秒で終了します",running = true)
        try {
            val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("consent",Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra("consent")
            require(data != null) { "画面取得の許可がありません" }
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(Activity.RESULT_OK,data)
            projection!!.registerCallback(object: MediaProjection.Callback() {
                override fun onStop() { finishCapture("OSが画面取得を終了しました") }
            },handler)
            val metrics = resources.displayMetrics
            val bounds = if (Build.VERSION.SDK_INT >= 30) getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds else android.graphics.Rect(0,0,metrics.widthPixels,metrics.heightPixels)
            require(bounds.width().toLong() * bounds.height() <= 24_000_000)
            reader = ImageReader.newInstance(bounds.width(),bounds.height(),android.graphics.PixelFormat.RGBA_8888,2)
            val readyAt = SystemClock.elapsedRealtime() + 2500
            reader!!.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                image.use {
                    if (finished || SystemClock.elapsedRealtime() < readyAt) return@setOnImageAvailableListener
                    try {
                        val plane = image.planes[0]
                        val paddedWidth = plane.rowStride / plane.pixelStride
                        val padded = Bitmap.createBitmap(paddedWidth,image.height,Bitmap.Config.ARGB_8888)
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val cropped = Bitmap.createBitmap(padded,0,0,image.width,image.height)
                        val ratio = minOf(1f,1280f/maxOf(cropped.width,cropped.height))
                        val thumbnail = if (ratio < 1f) Bitmap.createScaledBitmap(cropped,(cropped.width*ratio).toInt(),(cropped.height*ratio).toInt(),true) else cropped
                        CaptureStore.preview.value = CapturePreview(thumbnail,"画面を取得しました。画像は端末内のみです。保護画面は黒くなる場合があります")
                        finishCapture(null)
                    } catch (_: Exception) { finishCapture("画面画像を取得できませんでした") }
                }
            },handler)
            display = projection!!.createVirtualDisplay("KomichiLocalPreview",bounds.width(),bounds.height(),metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
            handler.postDelayed({ finishCapture("取得できる画面がありませんでした") },15_000)
        } catch (_: Exception) { finishCapture("画面取得が許可されないか、この端末では利用できません") }
        return START_NOT_STICKY
    }
    private fun finishCapture(message: String?) {
        if (finished) return
        finished = true; handler.removeCallbacksAndMessages(null)
        if (message != null) CaptureStore.preview.value = CapturePreview(message = message)
        display?.release(); reader?.close(); projection?.stop(); CaptureStore.cancel = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { if (!finished) finishCapture("画面取得を終了しました"); super.onDestroy() }
}
