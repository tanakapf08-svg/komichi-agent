package dev.komichi.agent.device

import android.text.Html
import dev.komichi.agent.data.obj
import dev.komichi.agent.security.Privacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.Proxy
import java.util.concurrent.TimeUnit

object PublicWebPolicy {
    fun url(raw: String): HttpUrl {
        require(raw.length <= 2048 && raw.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) { "URLが不正です" }
        val url = raw.toHttpUrl()
        require(url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()) { "認証情報を含まない標準HTTPS URLだけを使用できます" }
        require(url.host != "localhost" && !url.host.endsWith(".localhost") && !url.host.endsWith(".local") && ('.' in url.host || ':' in url.host)) { "公開WebサイトのURLを指定してください" }
        require(url.queryParameterNames.none { it.lowercase() in setOf("password","token","access_token","refresh_token","api_key","apikey","secret","authorization","code") }) { "認証情報を含むURLは使用できません" }
        return url
    }
    fun publicAddress(address: InetAddress): Boolean {
        if(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        if(bytes.size == 4) {
            if(bytes[0] in setOf(0,10,127) || bytes[0] >= 224 || bytes[0] == 169 && bytes[1] == 254 || bytes[0] == 172 && bytes[1] in 16..31 || bytes[0] == 192 && bytes[1] == 168) return false
            if(bytes[0] == 100 && bytes[1] in 64..127 || bytes[0] == 198 && bytes[1] in 18..19) return false
        }
        if(bytes.size == 16 && (bytes[0] and 0xfe) == 0xfc) return false
        if(bytes.size == 16 && bytes.take(4) == listOf(0,100,255,155)) return false // NAT64 well-known prefix
        return true
    }
    fun checkedAddresses(addresses: List<InetAddress>): List<InetAddress> {
        if(addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw UnknownHostException("ローカル・プライベート宛先へのアクセスは許可されていません")
        return addresses
    }
}

/** Public pages only. No cookies, JS, credentials, redirects or LAN access. */
object PublicWeb {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8,TimeUnit.SECONDS).callTimeout(9,TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY).dns(object: Dns { override fun lookup(hostname: String): List<InetAddress> = PublicWebPolicy.checkedAddresses(Dns.SYSTEM.lookup(hostname)) }).build()
    suspend fun fetch(raw: String): org.json.JSONObject = withContext(Dispatchers.IO) {
        val url = PublicWebPolicy.url(raw)
        val call = client.newCall(Request.Builder().url(url).header("Accept","text/html,text/plain,application/json").header("User-Agent","Komichi-Agent/0.4").build())
        coroutineScope {
            val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
            try { call.execute().use { response ->
                if(response.code in 300..399) return@use obj("status" to "redirect_not_followed","url" to url.toString(),"location" to response.header("Location"),"untrusted_observation" to true)
                require(response.isSuccessful) { "Web取得に失敗しました（HTTP ${response.code}）" }
                val body = response.body ?: error("本文がありません")
                val type = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: ""
                require(type in setOf("text/html","text/plain","application/xhtml+xml","application/json")) { "HTML・テキスト・JSONのみ取得できます" }
                require(body.contentLength() <= 512_000) { "Webページが大きすぎます" }
                val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                body.byteStream().use { stream -> while(true) {
                    val count = stream.read(buffer); if(count < 0) break
                    require(output.size()+count <= 512_000) { "Webページが大きすぎます" }; output.write(buffer,0,count)
                } }
                val rawText = output.toByteArray().toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                val text = if(type.contains("html")) Html.fromHtml(rawText.replace(Regex("(?is)<(script|style|noscript)\\b[^>]*>.*?</\\1\\s*>"),""),Html.FROM_HTML_MODE_LEGACY).toString() else rawText
                obj("status" to "fetched","untrusted_observation" to true,"url" to url.toString(),"text" to Privacy.mask(text.take(12_000)),"truncated" to (text.length > 12_000))
            } } finally { watcher.cancel() }
        }
    }
}
