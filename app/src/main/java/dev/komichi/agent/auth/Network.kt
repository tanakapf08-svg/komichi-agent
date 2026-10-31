package dev.komichi.agent.auth

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val secureHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
    .callTimeout(100, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) { _, resource, _ -> resource.close() } else response.close()
        }
    })
}

fun officialRequest(url: String): Request.Builder {
    val parsed = url.toHttpUrl()
    require(parsed.isHttps && parsed.host in setOf("auth.openai.com", "api.openai.com") && parsed.port == 443) {
        "公式以外の接続先は許可していません"
    }
    return Request.Builder().url(parsed).header("Accept", "application/json")
}
