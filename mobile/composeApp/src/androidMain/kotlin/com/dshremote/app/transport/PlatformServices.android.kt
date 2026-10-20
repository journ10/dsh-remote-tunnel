package com.dshremote.app.transport

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Set by MainActivity before App(). */
object AndroidPlatform {
    lateinit var appContext: Context
}

private class AndroidTransport : RemoteTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): HttpResult = withContext(Dispatchers.IO) {
        val callClient = if (followRedirects) client else client.newBuilder().followRedirects(false).build()
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
            .get()
            .build()
        callClient.newCall(request).execute().use { response ->
            HttpResult(
                status = response.code,
                body = response.body?.string().orEmpty(),
                headers = response.headers.toMultimap(),
            )
        }
    }

    override suspend fun post(url: String, body: String, headers: Map<String, String>): HttpResult =
        withContext(Dispatchers.IO) {
            execute(
                Request.Builder()
                    .url(url)
                    .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build(),
            )
        }

    override suspend fun postBytes(url: String, body: String, headers: Map<String, String>): BinaryHttpResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                BinaryHttpResult(
                    status = response.code,
                    bytes = response.body?.bytes() ?: ByteArray(0),
                    headers = response.headers.toMultimap(),
                )
            }
        }

    override suspend fun openSocket(
        url: String,
        headers: Map<String, String>,
        initialFrame: String,
        listener: SocketListener,
    ): SocketHandle {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
            .build()
        val socket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            private var ws: okhttp3.WebSocket? = null

            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                ws = webSocket
                webSocket.send(initialFrame)
                listener.onOpen()
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                listener.onMessage(text)
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                listener.onClosed(reason.ifBlank { "流已关闭" })
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                listener.onClosed(t.message ?: "流连接失败")
            }
        })
        return object : SocketHandle {
            override fun send(text: String) {
                socket.send(text)
            }

            override fun close() {
                socket.close(1000, "leave")
            }
        }
    }

    private fun execute(request: Request): HttpResult {
        client.newCall(request).execute().use { response ->
            return HttpResult(
                status = response.code,
                body = response.body?.string().orEmpty(),
                headers = response.headers.toMultimap(),
            )
        }
    }
}

private class PrefsCookieStore(context: Context) : CookieStore {
    private val prefs = context.getSharedPreferences("dsh_kmp_cookies", Context.MODE_PRIVATE)

    override suspend fun load(host: String): String? = prefs.getString(host, null)

    override suspend fun save(host: String, cookie: String) {
        prefs.edit().putString(host, cookie).apply()
    }

    override suspend fun clear(host: String) {
        prefs.edit().remove(host).apply()
    }
}

private fun prefsConnectionStore(context: Context, cookies: CookieStore): ConnectionStore {
    val prefs = context.getSharedPreferences("dsh_kmp_connection", Context.MODE_PRIVATE)
    return StoredConnectionStore(
        cookies = cookies,
        read = { prefs.getString("instances_v1", null) },
        write = { prefs.edit().putString("instances_v1", it).apply() },
        legacy = {
            val origin = prefs.getString("origin", null)
            val host = prefs.getString("host", null)
            if (origin != null && host != null) PairingTarget(origin, host) else null
        },
        clearLegacy = { prefs.edit().remove("origin").remove("host").apply() },
    )
}

actual fun platformServices(): PlatformServices {
    val context = AndroidPlatform.appContext
    return object : PlatformServices {
        override val transport: RemoteTransport = AndroidTransport()
        override val cookies: CookieStore = PrefsCookieStore(context)
        override val connections: ConnectionStore = prefsConnectionStore(context, cookies)
    }
}
