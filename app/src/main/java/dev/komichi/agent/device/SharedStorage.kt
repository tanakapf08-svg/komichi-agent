package dev.komichi.agent.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import dev.komichi.agent.data.array
import dev.komichi.agent.data.obj
import dev.komichi.agent.security.Privacy
import java.io.File

object SharedStorage {
    fun granted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    /** Canonical resolution also prevents symlink and prefix-sibling escapes. */
    fun resolve(root: File, path: String): File {
        require(path.length <= 512 && '\u0000' !in path) { "パスが不正です" }
        val base = root.canonicalFile
        val candidate = (if (File(path).isAbsolute) File(path) else File(base,path)).canonicalFile
        require(candidate == base || candidate.path.startsWith(base.path + File.separator)) { "共有ストレージの外にはアクセスできません" }
        val relative = candidate.relativeTo(base).invariantSeparatorsPath
        require(relative != "Android/data" && !relative.startsWith("Android/data/") && relative != "Android/obb" && !relative.startsWith("Android/obb/")) { "他アプリの保護領域にはアクセスできません" }
        return candidate
    }
    fun list(context: Context, preferences: DevicePreferences, path: String): org.json.JSONObject {
        require(preferences.fullAccessEnabled()) { "全アクセスモードが必要です" }
        require(granted(context)) { "OSのファイルアクセス権限が必要です" }
        @Suppress("DEPRECATION") val root = Environment.getExternalStorageDirectory().canonicalFile
        val directory = resolve(root,path)
        require(directory.isDirectory && directory.canRead()) { "ディレクトリを参照できません" }
        val children = directory.listFiles() ?: error("OSが参照を拒否しました")
        val visible = children.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }).take(60)
        return obj("untrusted_observation" to true, "directory" to Privacy.mask(directory.relativeTo(root).invariantSeparatorsPath),
            "has_more" to (children.size > visible.size), "entries" to array(visible.map {
                obj("name" to Privacy.mask(it.name).take(160), "directory" to it.isDirectory, "size" to if (it.isFile) it.length() else null)
            }))
    }
}
