package com.dshremote.app.transport

private class OhosStubTransport : RemoteTransport {
    override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResult =
        HttpResult(501, "HarmonyOS transport not implemented yet", emptyMap())

    override suspend fun post(url: String, body: String, headers: Map<String, String>): HttpResult =
        HttpResult(501, "HarmonyOS transport not implemented yet", emptyMap())

    override suspend fun postBytes(url: String, body: String, headers: Map<String, String>): BinaryHttpResult =
        BinaryHttpResult(501, ByteArray(0), emptyMap())

    override suspend fun openSocket(
        url: String,
        headers: Map<String, String>,
        initialFrame: String,
        listener: SocketListener,
    ): SocketHandle {
        listener.onClosed("HarmonyOS transport not implemented yet")
        return object : SocketHandle {
            override fun send(text: String) = Unit
            override fun close() = Unit
        }
    }
}

private class MemoryCookieStore : CookieStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun load(host: String): String? = values[host]
    override suspend fun save(host: String, cookie: String) { values[host] = cookie }
    override suspend fun clear(host: String) { values.remove(host) }
}


actual fun platformServices(): PlatformServices = object : PlatformServices {
    override val transport: RemoteTransport = OhosStubTransport()
    override val cookies: CookieStore = MemoryCookieStore()
    override val connections: ConnectionStore = memoryConnectionStore(cookies)
}
