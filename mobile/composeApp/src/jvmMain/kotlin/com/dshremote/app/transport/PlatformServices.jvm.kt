package com.dshremote.app.transport

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.HttpURLConnection
import java.net.URL

private class JvmTransport : RemoteTransport {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): HttpResult {
        val result = request("GET", url, null, headers, followRedirects)
        return result
    }

    override suspend fun post(url: String, body: String, headers: Map<String, String>): HttpResult =
        request("POST", url, body, headers)

    override suspend fun postBytes(url: String, body: String, headers: Map<String, String>): BinaryHttpResult {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            return BinaryHttpResult(
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
        val client = OkHttpClient()
        val socket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
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
            override fun send(text: String) { socket.send(text) }
            override fun close() { socket.close(1000, "leave") }
        }
    }

    private fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        followRedirects: Boolean = true,
    ): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = followRedirects
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        val responseHeaders = connection.headerFields
            .filterKeys { it != null }
            .mapValues { it.value ?: emptyList() }
        connection.disconnect()
        return HttpResult(status, text, responseHeaders)
    }
}

private class MemoryCookieStore : CookieStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun load(host: String): String? = values[host]
    override suspend fun save(host: String, cookie: String) { values[host] = cookie }
    override suspend fun clear(host: String) { values.remove(host) }
}


actual fun platformServices(): PlatformServices = object : PlatformServices {
    override val transport: RemoteTransport = JvmTransport()
    override val cookies: CookieStore = MemoryCookieStore()
    override val connections: ConnectionStore = memoryConnectionStore(cookies)
}
