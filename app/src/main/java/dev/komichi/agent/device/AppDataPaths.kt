package dev.komichi.agent.device

data class AppDataSource(val packageName: String, val root: String, val relative: String, val canonicalRoots: List<String>) {
    val path get() = if (relative.isEmpty()) root else "$root/$relative"
}
data class AppDataDestination(val storageRoot: String, val parent: String, val name: String) {
    val path get() = listOf(storageRoot,parent,name).filter { it.isNotEmpty() }.joinToString("/")
}
data class AppDataCopyPlan(val source: AppDataSource, val destination: AppDataDestination, val format: String, val jobId: String)

/** Pure path policy. Private paths are resolved again under root before any read. */
object AppDataPaths {
    fun packageName(value: String) {
        require(value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "パッケージ名が不正です" }
    }
    private fun clean(value: String): String {
        require(value.isNotBlank() && value.length <= 1024 && value.none { it.isISOControl() }) { "パスが空・長すぎる・制御文字を含みます" }
        require(value.split('/').none { it == ".." }) { "上位ディレクトリへの移動は許可していません" }
        return (if (value.startsWith('/')) "/" else "") + value.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }
    fun source(packageName: String, installedRoots: List<String>, requested: String): AppDataSource {
        AppDataPaths.packageName(packageName)
        val roots = installedRoots.distinct().map { clean(it).trimEnd('/') }
        require(roots.isNotEmpty()) { "このアプリの内部データパスを取得できません" }
        roots.forEach { root ->
            require(root.matches(Regex("/data/(?:user|user_de)/[0-9]+/" + Regex.escape(packageName))) || root == "/data/data/$packageName") { "対応していないアプリデータ領域です" }
        }
        val request = clean(requested)
        val aliases = roots.map { it to it }.toMutableList()
        if ("/data/user/0/$packageName" in roots) aliases += "/data/data/$packageName" to "/data/user/0/$packageName"
        if ("/data/data/$packageName" in roots) aliases += "/data/user/0/$packageName" to "/data/data/$packageName"
        val match = if (request.startsWith('/')) aliases.firstOrNull { (alias,_) -> request == alias || request.startsWith("$alias/") }
            ?: error("指定したアプリの内部データ以外にはアクセスできません") else null
        val root = match?.second ?: roots.first()
        val relative = if (match != null) request.removePrefix(match.first).trimStart('/') else request
        require(relative.split('/').size <= 24) { "パスが深すぎます" }
        val canonicalRoots = mutableListOf(root)
        if (root == "/data/user/0/$packageName") canonicalRoots += "/data/data/$packageName"
        if (root == "/data/data/$packageName") canonicalRoots += "/data/user/0/$packageName"
        return AppDataSource(packageName,root,relative,canonicalRoots)
    }
    fun destination(storageRoot: String, requested: String, format: String): AppDataDestination {
        require(format in setOf("directory","tar")) { "コピー形式が不正です" }
        val base = clean(storageRoot).trimEnd('/')
        require(base.startsWith("/storage/") || base.startsWith("/mnt/")) { "共有ストレージのパスが不正です" }
        var request = clean(requested)
        for (alias in listOf("/sdcard","/storage/self/primary")) {
            if (request == alias || request.startsWith("$alias/")) request = base + request.removePrefix(alias)
        }
        val relative = if (request.startsWith('/')) {
            require(request.startsWith("$base/")) { "保存先は共有ストレージ内の新しいパスを指定してください" }
            request.removePrefix("$base/")
        } else request
        val segments = relative.split('/').filter { it.isNotEmpty() }
        require(segments.isNotEmpty() && segments.size <= 24 && segments.all { it.toByteArray().size <= 240 }) { "保存先名が不正です" }
        require(segments.take(2).joinToString("/") !in setOf("Android/data","Android/obb")) { "保存先に他アプリの保護領域は指定できません" }
        require(format != "tar" || segments.last().endsWith(".tar",true)) { "TAR形式では保存先の末尾を.tarにしてください" }
        return AppDataDestination(base,segments.dropLast(1).joinToString("/"),segments.last())
    }
}
