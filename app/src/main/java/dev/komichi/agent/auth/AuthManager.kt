package dev.komichi.agent.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.nimbusds.jose.jwk.JWKSet
import dev.komichi.agent.data.Vault
import dev.komichi.agent.data.array
import dev.komichi.agent.data.obj
import dev.komichi.agent.data.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

data class Account(val clientId: String, val subject: String, val label: String, val connected: Boolean)
object LoginControl { var cancel: (() -> Unit)? = null }

class AuthManager(private val context: Context, private val vault: Vault) {
    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
        const val TOKEN = "$ISSUER/api/accounts/oauth/token"
        const val RESOURCE = "https://api.openai.com/v1"
        const val DIRECT = "chatgpt.tokens.use.direct"
        const val SCOPES = "openid profile email offline_access resource.invoke $DIRECT"
    }
    private val refreshMutex = Mutex()
    private var state = vault.read("accounts") ?: obj("host" to "urn:uuid:${UUID.randomUUID()}", "accounts" to JSONArray())
    init { save() }
    @Synchronized private fun save() { vault.write("accounts", state) }
    @Synchronized fun accounts(): List<Account> = state.getJSONArray("accounts").objects().map {
        Account(it.getString("client_id"), it.getString("sub"), it.getString("label"), it.optString("access_token").isNotBlank())
    }
    @Synchronized fun active(): Account? = accounts().find { it.clientId == state.optString("active") }
    @Synchronized fun select(clientId: String) {
        require(accounts().any { it.clientId == clientId }); state.put("active", clientId); save()
    }
    @Synchronized private fun record(id: String): JSONObject = state.getJSONArray("accounts").objects().first { it.getString("client_id") == id }
    @Synchronized private fun commit(record: JSONObject, activate: Boolean = true) {
        val others = state.getJSONArray("accounts").objects().filter { it.getString("client_id") != record.getString("client_id") }
        state.put("accounts", array(others + record)); if (activate) state.put("active", record.getString("client_id")); save()
    }
    private fun random(): String = Base64.encodeToString(ByteArray(32).apply { SecureRandom().nextBytes(this) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun challenge(verifier: String) = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    suspend fun login(account: Account? = null) = withContext(Dispatchers.IO) {
        val old = account?.let { synchronized(this@AuthManager) { JSONObject(record(it.clientId).toString()) } }
        val verifier = random(); val oauthState = random(); val nonce = random()
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4)
            server.soTimeout = 1000
            val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
            val uri = Uri.parse(AUTHORIZE).buildUpon()
                .appendQueryParameter("client_id", account?.clientId ?: "dynamic_agent_client")
                .appendQueryParameter("ext_agent_host_id", state.getString("host"))
                .appendQueryParameter("redirect_uri", redirect).appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", SCOPES).appendQueryParameter("resource", RESOURCE)
                .appendQueryParameter("state", oauthState).appendQueryParameter("nonce", nonce)
                .appendQueryParameter("code_challenge", challenge(verifier)).appendQueryParameter("code_challenge_method", "S256")
            if (account == null) uri.appendQueryParameter("agent_name_hint", "Komichi Agent")
            else old?.optString("id_token")?.takeIf { it.isNotBlank() }?.let { uri.appendQueryParameter("id_token_hint", it) }
            // User invokes login. No embedded WebView and no browser/session cookie access.
            var loginSession = ""
            try {
                withContext(Dispatchers.Main) {
                    loginSession = LoginService.startAndAwait(context)
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri.build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                val deadline = System.nanoTime() + 180_000_000_000L
                var callback: Uri? = null
                while (callback == null && System.nanoTime() < deadline) {
                    currentCoroutineContext().ensureActive()
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 1000
                            val input = socket.getInputStream()
                            val bytes = ArrayList<Byte>()
                            while (bytes.size < 8192) {
                                val byte = input.read(); if (byte == -1 || byte == 10) break; bytes.add(byte.toByte())
                            }
                            val first = bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
                            val parts = first.split(' ')
                            val candidate = if (parts.size == 3 && parts[0] == "GET" && parts[1].startsWith("/auth/callback?")) Uri.parse(redirect.substringBefore("/auth/callback") + parts[1]) else null
                            val valid = candidate?.path == "/auth/callback" && candidate.getQueryParameter("state") == oauthState
                            val body = if (valid) "Komichi Agent: browser step received. Return to the app for validation." else "Invalid callback."
                            socket.getOutputStream().write(("HTTP/1.1 ${if (valid) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray())
                            if (valid) callback = candidate
                        }
                    } catch (_: SocketTimeoutException) { /* bounded polling for coroutine cancellation */ }
                }
                val cb = callback ?: error("認証の待機時間を超えました。再度ログインしてください")
                require(cb.getQueryParameter("error") == null) { "ブラウザで認証が許可されませんでした" }
                val clientId = cb.getQueryParameter("client_id") ?: account?.clientId ?: error("アプリ登録IDが返されませんでした")
                require(clientId != "dynamic_agent_client" && clientId.length in 5..256) { "登録IDが不正です" }
                require(account == null || account.clientId == clientId) { "選択した登録とは異なる登録が返されました" }
                val code = cb.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: error("認可コードがありません")
                val token = exchange(mapOf("grant_type" to "authorization_code", "client_id" to clientId,
                    "code" to code, "code_verifier" to verifier, "redirect_uri" to redirect, "resource" to RESOURCE))
                val identity = validateIdentity(token.getString("id_token"), clientId, nonce)
                require(account == null || identity.getString("sub") == account.subject) { "選択したアカウントと認証結果が異なります" }
                val rec = tokenRecord(token, clientId, identity, old)
                commit(rec)
            } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { LoginService.finish(loginSession) } }
        }
    }

    private suspend fun getJson(url: String): JSONObject = secureHttp.newCall(officialRequest(url).get().build()).awaitResponse().use { response ->
        require(response.isSuccessful) { "認証情報の検証に失敗しました (HTTP ${response.code})" }
        JSONObject(response.body?.string() ?: error("空の応答"))
    }
    private suspend fun discovery(): JSONObject {
        val d = getJson("$ISSUER/.well-known/openid-configuration")
        require(d.getString("issuer") == ISSUER) { "認証issuerが不正です" }
        return d
    }
    private suspend fun validateIdentity(raw: String, clientId: String, nonce: String?): JSONObject {
        val jwksUrl = discovery().getString("jwks_uri")
        require(Uri.parse(jwksUrl).host == "auth.openai.com") { "JWKS接続先が不正です" }
        val jwks = JWKSet.parse(getJson(jwksUrl).toString())
        return IdentityVerifier.verify(raw,clientId,nonce,jwks)
    }
    private suspend fun exchange(values: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { values.forEach { (k,v) -> add(k,v) } }.build()
        return secureHttp.newCall(officialRequest(TOKEN).post(body).build()).awaitResponse().use { response ->
            val data = runCatching { JSONObject(response.body?.string() ?: "{}") }.getOrDefault(JSONObject())
            if (!response.isSuccessful) {
                val code = data.optString("error").takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "oauth_error"
                throw AuthFailure(code, response.code)
            }
            data
        }
    }
    private fun tokenRecord(tokens: JSONObject, clientId: String, identity: JSONObject, old: JSONObject?): JSONObject {
        require(tokens.getString("access_token").isNotBlank())
        require(tokens.optString("token_type", "Bearer").equals("Bearer", true))
        val scope = tokens.optString("scope", old?.optString("scope") ?: "")
        val expiry = tokens.getLong("expires_in"); require(expiry in 1..31_536_000)
        return obj("client_id" to clientId, "sub" to identity.getString("sub"),
            "label" to (old?.optString("label") ?: "${identity.getString("email")} · ${clientId.takeLast(6)}"),
            "access_token" to tokens.getString("access_token"),
            "refresh_token" to tokens.optString("refresh_token", old?.optString("refresh_token") ?: ""),
            "id_token" to tokens.optString("id_token", old?.optString("id_token") ?: ""),
            "scope" to scope, "expires_at" to System.currentTimeMillis() + expiry * 1000)
    }
    suspend fun accessToken(): String = refreshMutex.withLock {
        val active = active() ?: error("Continue with ChatGPTから認証してください")
        var rec = synchronized(this) { JSONObject(record(active.clientId).toString()) }
        require(rec.optString("access_token").isNotBlank()) { "再ログインしてください" }
        require(DIRECT in rec.optString("scope").split(' ')) { "この登録では契約枠共有が許可されていません。別の接続や再認証を行ってください" }
        if (rec.getLong("expires_at") <= System.currentTimeMillis() + 180_000) {
            val tokens = try {
                exchange(mapOf("grant_type" to "refresh_token", "client_id" to active.clientId,
                    "refresh_token" to rec.getString("refresh_token"), "resource" to RESOURCE))
            } catch (e: AuthFailure) {
                if (e.code in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")) clearTokens(active.clientId)
                throw e
            }
            val identity = if (tokens.has("id_token")) validateIdentity(tokens.getString("id_token"), active.clientId, null)
                else obj("sub" to active.subject, "email" to active.label)
            require(identity.getString("sub") == active.subject)
            rec = tokenRecord(tokens, active.clientId, identity, rec); commit(rec, activate = false)
        }
        require(DIRECT in rec.optString("scope").split(' ')) { "契約枠共有の許可が失われました" }
        rec.getString("access_token")
    }
    suspend fun signOut(): Boolean = refreshMutex.withLock {
        val active = active() ?: return@withLock true
        val rec = synchronized(this) { JSONObject(record(active.clientId).toString()) }
        var confirmed = false
        try {
            val endpoint = discovery().getString("revocation_endpoint")
            require(Uri.parse(endpoint).host == "auth.openai.com")
            val body = FormBody.Builder().add("token", rec.optString("refresh_token")).add("token_type_hint", "refresh_token").add("client_id", active.clientId).build()
            confirmed = secureHttp.newCall(officialRequest(endpoint).post(body).build()).awaitResponse().use { it.code == 200 }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (_: Exception) { /* show unconfirmed remote revocation to the user */ }
        finally { clearTokens(active.clientId) }
        confirmed
    }
    @Synchronized private fun clearTokens(id: String) {
        val rec = record(id)
        listOf("access_token", "refresh_token", "id_token").forEach { rec.remove(it) }; save()
    }
}

class AuthFailure(val code: String, status: Int): Exception("ChatGPT認証エラー: $code (HTTP $status)。有料APIには切り替えません")
