package dev.komichi.agent.device

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.komichi.agent.data.LibraryPolicy
import dev.komichi.agent.data.WorkspaceDocument
import dev.komichi.agent.data.obj
import dev.komichi.agent.security.Privacy
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object TextDocuments {
    fun chunk(document: WorkspaceDocument, offset: Int): org.json.JSONObject {
        require(offset in 0..document.text.length) { "テキストの開始位置が範囲外です" }
        val end = minOf(offset + 12_000,document.text.length)
        return obj("untrusted_observation" to true,"path" to document.path,"version" to document.version,
            "text" to Privacy.mask(document.text.substring(offset,end)),"offset" to offset,"next_offset" to if(end < document.text.length) end else null,"length" to document.text.length)
    }
    suspend fun selected(context: Context, preferences: DevicePreferences, selection: Int, offset: Int): org.json.JSONObject {
        val raw = preferences.files().getOrNull(selection) ?: error("選択ファイルの番号がありません")
        val text = read(context,Uri.parse(raw))
        return chunk(WorkspaceDocument("selected-document-$selection",text,0),offset).put("selection",selection)
    }
    suspend fun read(context: Context, uri: Uri, maximumCharacters: Int = 64_000): String = withContext(Dispatchers.IO) {
        require(maximumCharacters in setOf(64_000,256_000))
        val maximumBytes = maximumCharacters * 4
        val descriptor = context.contentResolver.openFileDescriptor(uri,"r") ?: error("選択したファイルを開けません")
        coroutineScope {
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { runCatching { descriptor.close() } } }
            try {
                require(descriptor.statSize <= maximumBytes) { "テキストファイルが大きすぎます" }
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer); if(count < 0) break
                        require(output.size() + count <= maximumBytes) { "テキストファイルが大きすぎます" }
                        output.write(buffer,0,count)
                    }
                    output.toByteArray()
                }
                val text = runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
                    .getOrElse { error("UTF-8テキストに対応しています。PDF・画像・バイナリは未対応です") }
                require(text.length <= maximumCharacters && '\u0000' !in text) { "テキストが長すぎるか、バイナリが含まれています" }; text
            } finally { watcher.cancel(); runCatching { descriptor.close() } }
        }
    }
    suspend fun write(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        require(text.length <= 256_000)
        val descriptor = context.contentResolver.openFileDescriptor(uri,"wt") ?: error("保存先を開けません")
        coroutineScope {
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { runCatching { descriptor.close() } } }
            try { ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { stream ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                for(offset in bytes.indices step 4096) { currentCoroutineContext().ensureActive(); stream.write(bytes,offset,minOf(4096,bytes.size-offset)) }
                stream.flush()
            } } finally { watcher.cancel(); runCatching { descriptor.close() } }
        }
    }
}
