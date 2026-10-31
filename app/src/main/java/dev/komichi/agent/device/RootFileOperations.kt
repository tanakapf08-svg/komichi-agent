package dev.komichi.agent.device

import android.content.Context
import android.os.Environment
import dev.komichi.agent.data.array
import dev.komichi.agent.data.obj
import dev.komichi.agent.security.Privacy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RootFileFailure(val code: String, val diagnostic: String): Exception("$code: $diagnostic")
private data class RootOutput(val stdout: ByteArray, val stderr: String, val exit: Int)

class RootFileOperations(private val context: Context, private val preferences: DevicePreferences) {
    private fun source(packageName: String, path: String): AppDataSource {
        val info = context.packageManager.getApplicationInfo(packageName,0)
        return AppDataPaths.source(packageName,listOfNotNull(info.dataDir,info.deviceProtectedDataDir).filter { it.isNotBlank() },path)
    }
    fun plan(packageName: String, sourcePath: String, destination: String, format: String): AppDataCopyPlan {
        @Suppress("DEPRECATION") val storageRoot = Environment.getExternalStorageDirectory().canonicalPath
        return AppDataCopyPlan(source(packageName,sourcePath),AppDataPaths.destination(storageRoot,destination,format),format,UUID.randomUUID().toString().replace("-",""))
    }
    fun describe(plan: AppDataCopyPlan): String = "対象アプリ: ${plan.source.packageName}\nコピー元: ${plan.source.path}\n保存先: ${plan.destination.path}\n形式: ${if (plan.format == "tar") "TARアーカイブ" else "ファイル／フォルダをそのままコピー"}\n\n内部データにはログイン状態や個人情報が含まれる場合があります。端末内へコピーし、内容はAIへ送信しません。コピー元は変更せず、既存の保存先は上書きしません。実行中のデータベースは不整合になる場合があるため、必要なら先に対象アプリを終了してください。失敗・停止時は不完全なコピーと.incompleteマーカーが残る場合があります。"
    suspend fun list(packageName: String, path: String): JSONObject {
        require(preferences.rootEnabled()) { "Root連携を有効にしてください" }
        val src = source(packageName,path); val job = UUID.randomUUID().toString().replace("-","")
        requireRoot()
        val output = execute({ lease -> RootFileScripts.list(src,job,lease) },job,20_000)
        checkOutput(output,job)
        val raw = output.stdout.toString(Charsets.UTF_8)
        val data = raw.substringAfter("KOMICHI_LIST\n", "").substringBefore("\nKOMICHI_OK $job listed")
        val fields = data.split('\u0000')
        val entries = (0 until (fields.size - 1) / 2).map { index -> obj("type" to fields[index*2], "name" to Privacy.mask(fields[index*2+1]).take(200)) }
        return obj("untrusted_observation" to true,"package" to packageName,"data_root" to src.root,"path" to src.path,"entries" to array(entries),"limit" to 60)
    }
    suspend fun copy(plan: AppDataCopyPlan): JSONObject {
        require(preferences.rootEnabled()) { "Root連携を有効にしてください" }
        requireRoot()
        val result = execute({ lease -> RootFileScripts.copy(plan,lease) },plan.jobId,180_000)
        checkOutput(result,plan.jobId)
        return obj("status" to "copied","package" to plan.source.packageName,"source" to plan.source.path,
            "destination" to plan.destination.path,"format" to plan.format,"contents_sent_to_ai" to false,"source_modified" to false)
    }
    private suspend fun requireRoot() {
        try { RootBridge.run(RootBridge.Operation.IDENTITY) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw RootFileFailure("ROOT_NOT_GRANTED",Privacy.mask(e.message ?: "Root権限を取得できません")) }
    }
    private fun checkOutput(output: RootOutput, jobId: String) {
        val text = output.stdout.toString(Charsets.UTF_8)
        if (output.exit != 0 || !Regex("KOMICHI_OK $jobId [a-z]+\\s*$").containsMatchIn(text)) {
            val code = Regex("KOMICHI_ERROR ([A-Z_]+)").find(output.stderr)?.groupValues?.get(1) ?: "ROOT_OPERATION_FAILED"
            val messages = mapOf("ROOT_REQUIRED" to "UID 0が必要です", "SOURCE_ROOT_UNAVAILABLE" to "内部データのルートを読めません。端末のロック解除・Root権限・SELinuxやマウント状態を確認してください",
                "SOURCE_UNAVAILABLE" to "コピー元が存在しないか読み取れません", "SOURCE_SCOPE" to "対象アプリの範囲外へ移動するパスです",
                "DESTINATION_EXISTS" to "保存先が既に存在します。新しい名前を指定してください", "DESTINATION_SYMLINK" to "保存先の途中にシンボリックリンクがあります",
                "DESTINATION_SCOPE" to "保存先が共有ストレージの範囲外です", "DESTINATION_UNAVAILABLE" to "保存先を利用できません", "DESTINATION_UNWRITABLE" to "保存先へ書き込めません",
                "NOT_DIRECTORY" to "一覧を取得するパスがディレクトリではありません", "CANCELLED" to "実行許可が失効しました。操作は停止しました",
                "COPY_FAILED" to "コピーに失敗しました。容量不足・実行中のファイル・共有ストレージでのsymlink非対応等を確認してください。TAR形式ではリンクをアーカイブに保存できます。不完全な保存先が残る場合があります")
            throw RootFileFailure(code,(messages[code] ?: "Root実行が拒否または失敗しました") + "\n" + Privacy.mask(output.stderr.takeLast(1200)))
        }
    }
    private suspend fun execute(script: (String) -> String, jobId: String, timeoutMillis: Long): RootOutput = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir,"root-file-jobs").apply { mkdirs() }
        val lease = File(directory,"$jobId.permit")
        check(lease.createNewFile()) { "Root操作IDが重複しています" }; lease.writeText(jobId)
        val process = try { ProcessBuilder("su","-c",script(lease.absolutePath)).start() } catch (_: Exception) { lease.delete(); throw RootFileFailure("SU_UNAVAILABLE","suを起動できません") }
        val pid = AtomicInteger(0)
        val stdout = ByteArrayOutputStream(); val stderr = ByteArrayOutputStream()
        coroutineScope {
            val outJob = launch(Dispatchers.IO) {
                val buffer = ByteArray(4096)
                try { while (true) {
                    val read = process.inputStream.read(buffer); if (read < 0) break
                    if (stdout.size() < 131_072) stdout.write(buffer,0,minOf(read,131_072-stdout.size()))
                    if (pid.get() == 0) Regex("KOMICHI_PID $jobId ([0-9]+)").find(stdout.toString("UTF-8"))?.groupValues?.get(1)?.toIntOrNull()?.let { pid.set(it) }
                } } catch (e: IOException) { if (process.isAlive) currentCoroutineContext().ensureActive() }
            }
            val errorJob = launch(Dispatchers.IO) {
                val buffer = ByteArray(4096)
                try { while (true) { val read = process.errorStream.read(buffer); if (read < 0) break; if (stderr.size() < 16_384) stderr.write(buffer,0,minOf(read,16_384-stderr.size())) } }
                catch (e: IOException) { if (process.isAlive) currentCoroutineContext().ensureActive() }
            }
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            try {
                while (!process.waitFor(100,TimeUnit.MILLISECONDS)) {
                    currentCoroutineContext().ensureActive()
                    if (System.nanoTime() >= deadline) throw RootFileFailure("TIMEOUT","Rootファイル操作が時間上限を超えました。不完全な保存先が残る場合があります")
                }
                outJob.join(); errorJob.join(); currentCoroutineContext().ensureActive()
                RootOutput(stdout.toByteArray(),stderr.toString("UTF-8"),process.exitValue())
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    lease.delete()
                    if (process.isAlive && pid.get() > 1) runCatching {
                        val cleanup = ProcessBuilder("su","-c",RootFileScripts.cancel(pid.get(),jobId)).start()
                        if (!cleanup.waitFor(3,TimeUnit.SECONDS)) cleanup.destroy()
                    }
                    process.destroy(); runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }; runCatching { process.outputStream.close() }
                    outJob.cancel(); errorJob.cancel()
                }
            }
        }
    }
}
