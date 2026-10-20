package com.dshremote.app.transport

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.NSUserDefaults

private class IosTransport : RemoteTransport {
    private val client = newClient(followRedirects = true)
    private val noRedirectClient = newClient(followRedirects = false)

    private fun newClient(followRedirects: Boolean) = HttpClient(Darwin) {
        this.followRedirects = followRedirects
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
        }
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): HttpResult = (if (followRedirects) client else noRedirectClient).get(url) {
        headers.forEach { (name, value) -> header(name, value) }
    }.toResult()

    override suspend fun post(url: String, body: String, headers: Map<String, String>): HttpResult =
        client.post(url) {
            headers.forEach { (name, value) -> header(name, value) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }.toResult()

    override suspend fun postBytes(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): BinaryHttpResult {
        val response = client.post(url) {
            headers.forEach { (name, value) -> header(name, value) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return BinaryHttpResult(
            status = response.status.value,
            bytes = response.body(),
            headers = response.headers.entries().associate { it.key to it.value },
        )
    }

    override suspend fun openSocket(
        url: String,
        headers: Map<String, String>,
        initialFrame: String,
        listener: SocketListener,
    ): SocketHandle {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val outgoing = Channel<String>(Channel.BUFFERED)
        scope.launch {
            try {
                client.webSocket(
                    url,
                    request = {
                        headers.forEach { (name, value) -> header(name, value) }
                    },
                ) {
                    listener.onOpen()
                    send(Frame.Text(initialFrame))
                    val sender = launch {
                        for (message in outgoing) send(Frame.Text(message))
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) listener.onMessage(frame.readText())
                        }
                    } finally {
                        sender.cancel()
                    }
                }
                listener.onClosed("流已关闭")
            } catch (error: Throwable) {
                listener.onClosed(error.message ?: "流连接失败")
            }
        }
        return object : SocketHandle {
            override fun send(text: String) {
                outgoing.trySend(text)
            }

            override fun close() {
                outgoing.close()
                scope.cancel()
            }
        }
    }

    private suspend fun io.ktor.client.statement.HttpResponse.toResult(): HttpResult {
        val headerMap = mutableMapOf<String, MutableList<String>>()
        headers.entries().forEach { (name, values) ->
            headerMap.getOrPut(name) { mutableListOf() }.addAll(values)
        }
        return HttpResult(
            status = status.value,
            body = bodyAsText(),
            headers = headerMap,
        )
    }
}

private class UserDefaultsCookieStore : CookieStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun load(host: String): String? =
        defaults.stringForKey("dsh.kmp.cookie.$host")

    override suspend fun save(host: String, cookie: String) {
        defaults.setObject(cookie, forKey = "dsh.kmp.cookie.$host")
    }

    override suspend fun clear(host: String) {
        defaults.removeObjectForKey("dsh.kmp.cookie.$host")
    }
}

private fun userDefaultsConnectionStore(cookies: CookieStore): ConnectionStore {
    val defaults = NSUserDefaults.standardUserDefaults
    return StoredConnectionStore(
        cookies = cookies,
        read = { defaults.stringForKey("dsh.kmp.instances.v1") },
        write = { defaults.setObject(it, forKey = "dsh.kmp.instances.v1") },
        legacy = {
            val origin = defaults.stringForKey("dsh.kmp.origin")
            val host = defaults.stringForKey("dsh.kmp.host")
            if (origin != null && host != null) PairingTarget(origin, host) else null
        },
        clearLegacy = {
            defaults.removeObjectForKey("dsh.kmp.origin")
            defaults.removeObjectForKey("dsh.kmp.host")
        },
    )
}

actual fun platformServices(): PlatformServices = object : PlatformServices {
    override val transport: RemoteTransport = IosTransport()
    override val cookies: CookieStore = UserDefaultsCookieStore()
    override val connections: ConnectionStore = userDefaultsConnectionStore(cookies)
}
