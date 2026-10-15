package io.github.korenkrita.simulsub.models

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 异步连接并流式读取，取消下载同时取消连接与阻塞中的响应体读取。 */
internal class OkHttpModelSource(private val client: OkHttpClient = defaultClient) : ModelDownloadSource {
    /** 在独立 IO 取消监听中关闭底层 Call，避免等待读取超时才响应取消。 */
    override suspend fun transfer(url: String, receive: suspend (InputStream, Long) -> Unit) = coroutineScope {
        val call = client.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build())
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    /** 连接失败交还协程，由下载核心决定回退或系统重试。 */
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    /** 交付响应，并在交付前后发生取消时确保响应连接关闭。 */
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, rejected, _ -> rejected.close() }
                    }
                })
            }
            response.use {
                if (!it.isSuccessful) throw IOException("下载请求失败，HTTP 状态：${it.code}")
                if (!it.request.url.isHttps) throw IOException("下载源重定向到不安全地址")
                if (it.code != 200) throw ModelIntegrityException("下载源返回了非完整响应，HTTP 状态：${it.code}")
                val body = it.body ?: throw IOException("下载源未返回文件内容")
                body.byteStream().use { input -> receive(input, body.contentLength()) }
            }
        } finally {
            cancellation.cancel()
        }
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.MINUTES)
            .followSslRedirects(false)
            .build()
    }
}
