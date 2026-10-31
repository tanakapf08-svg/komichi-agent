package dev.komichi.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale

@Composable internal fun VoiceInputButton(enabled: Boolean, onText: (String) -> Unit, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val textCallback by rememberUpdatedState(onText); val noticeCallback by rememberUpdatedState(onNotice)
    var listening by remember { mutableStateOf(false) }
    var disclosure by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    fun cancel() { listening = false; val previous = recognizer; recognizer = null; previous?.cancel(); previous?.destroy() }
    fun start() {
        if(Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            noticeCallback("端末内音声認識が利用できません。Android 12以降と対応する端末内認識エンジンが必要です。クラウド認識には切り替えません"); return
        }
        cancel()
        runCatching {
            val instance = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = instance
            instance.setRecognitionListener(object: RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if(recognizer === instance) listening = true }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if(recognizer === instance && listening) noticeCallback("音声の認識結果を待っています") }
                override fun onError(error: Int) { if(recognizer !== instance || !listening) return; listening = false; noticeCallback("端末内音声認識を終了しました（コード$error）。自動送信は行っていません") }
                override fun onResults(results: Bundle?) { if(recognizer !== instance || !listening) return; listening = false; results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(textCallback) }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            listening = true
            instance.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag()).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false))
        }.onFailure { cancel(); noticeCallback("端末内音声認識を開始できませんでした") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if(it) start() else noticeCallback("マイクが許可されていません。文字入力は引き続き使えます") }
    LaunchedEffect(enabled) { if(!enabled) cancel() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _,event -> if(event == Lifecycle.Event.ON_PAUSE) cancel() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); cancel() }
    }
    TextButton(onClick = { if(listening) cancel() else disclosure = true },enabled = enabled) { Text(if(listening) "音声入力中 · 停止" else "音声入力") }
    if(disclosure) AlertDialog(onDismissRequest = { disclosure = false },title = { Text("端末内で音声入力") },text = { Text("対応端末の端末内認識だけを使用します。認識結果を入力欄に追加し、ChatGPTへは自動送信しません。非対応のエンジンやクラウド認識への切替は行いません。") },confirmButton = { TextButton(onClick = {
        disclosure = false
        if(Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) start()
        else if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }) { Text("音声入力を開始") } },dismissButton = { TextButton(onClick = { disclosure = false }) { Text("キャンセル") } })
}

internal class LocalVoiceReader(context: Context, private val notice: (String) -> Unit) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var ready = false
    private var utterance = ""
    private var engine: TextToSpeech? = TextToSpeech(context.applicationContext) { ready = it == TextToSpeech.SUCCESS }
    var speaking by mutableStateOf(false); private set
    init { engine?.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { handler.post { if(utteranceId == utterance && utterance.isNotEmpty()) speaking = true } }
        override fun onDone(utteranceId: String?) { handler.post { if(utteranceId == utterance) speaking = false } }
        @Deprecated("Android callback") override fun onError(utteranceId: String?) { handler.post { if(utteranceId == utterance && utterance.isNotEmpty()) { speaking = false; notice("端末内の読み上げに失敗しました") } } }
    }) }
    fun read(text: String) {
        val tts = engine
        if(!ready || tts == null) { notice("読み上げエンジンを準備しています。少し待って再度お試しください"); return }
        val language = Locale.getDefault().language
        val voice = tts.voices?.filter { !it.isNetworkConnectionRequired && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true && it.locale.language == language }?.maxByOrNull { it.quality }
        if(voice == null || tts.setVoice(voice) == TextToSpeech.ERROR) { notice("この言語の端末内音声がありません。OSの音声設定で用意してください。クラウド音声には切り替えません"); return }
        val cleaned = text.replace(Regex("(?s)```.*?```")," コードブロックは省略します。 ")
        val limit = minOf(4000,TextToSpeech.getMaxSpeechInputLength()-1)
        utterance = java.util.UUID.randomUUID().toString()
        if(tts.speak(cleaned.take(limit),TextToSpeech.QUEUE_FLUSH,null,utterance) == TextToSpeech.ERROR) { utterance = ""; speaking = false; notice("読み上げを開始できませんでした") }
        else if(cleaned.length > limit) notice("長い文章のため、先頭${limit}文字を読み上げます")
    }
    fun stop() { utterance = ""; engine?.stop(); speaking = false }
    fun close() { stop(); engine?.shutdown(); engine = null; handler.removeCallbacksAndMessages(null) }
}
