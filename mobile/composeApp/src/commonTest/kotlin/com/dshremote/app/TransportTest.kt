package com.dshremote.app

import com.dshremote.app.json.Json
import com.dshremote.app.json.get
import com.dshremote.app.json.objOrNull
import com.dshremote.app.transport.CookieStore
import com.dshremote.app.attachments.PickedImage
import com.dshremote.app.chat.promptSubagent
import com.dshremote.app.events.EventStreamListener
import com.dshremote.app.events.ApprovalPrompt
import com.dshremote.app.events.QuestionPrompt
import com.dshremote.app.events.openEvents
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.HttpResult
import com.dshremote.app.transport.BinaryHttpResult
import com.dshremote.app.transport.RemoteSessionsRepository
import com.dshremote.app.transport.RemoteTransport
import com.dshremote.app.transport.SocketHandle
import com.dshremote.app.transport.SocketListener
import com.dshremote.app.transport.pairingTarget
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

private class FakeTransport : RemoteTransport {
    var pairingCookie = "device-token"
    var responseStatus = 200
    var responseBody = """{"type":"server-response","result":{"ok":true,"value":{"items":[
      {"sessionId":"s1","cwd":"/work","running":false,"blank":true,
       "projections":{"values":{"title":null}}},
      {"sessionId":"s2","cwd":"/work","running":true,"blank":false,
       "projections":{"values":{"title":"Real session"}}}
    ]}}}"""
    var lastCookie: String? = null
    var lastUrl: String? = null
    var lastBody: String? = null
    var socketFrame: String? = null
    var socketUrl: String? = null
    var socketHeaders: Map<String, String> = emptyMap()
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): HttpResult = HttpResult(200, "paired", mapOf("Set-Cookie" to listOf("__dsh_device=$pairingCookie; Path=/; HttpOnly")))

    override suspend fun post(url: String, body: String, headers: Map<String, String>): HttpResult {
        lastCookie = headers["Cookie"]
        lastUrl = url
        lastBody = body
        return HttpResult(responseStatus, responseBody, emptyMap())
    }

    override suspend fun postBytes(url: String, body: String, headers: Map<String, String>): BinaryHttpResult =
        BinaryHttpResult(501, ByteArray(0), emptyMap())

    override suspend fun openSocket(
        url: String,
        headers: Map<String, String>,
        initialFrame: String,
        listener: SocketListener,
    ): SocketHandle {
        socketUrl = url
        socketHeaders = headers
        listener.onOpen()
        socketFrame?.let(listener::onMessage)
        return object : SocketHandle {
            override fun send(text: String) = Unit
            override fun close() = Unit
        }
    }
}

private class MemoryCookies : CookieStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun load(host: String): String? = values[host]
    override suspend fun save(host: String, cookie: String) { values[host] = cookie }
    override suspend fun clear(host: String) { values.remove(host) }
}

class TransportTest {
    @Test
    fun httpAndEventStreamsUseOnlyTheSelectedOriginsCookie() = runBlocking {
        val transport = FakeTransport()
        val cookies = MemoryCookies()
        val client = DshClient(transport, cookies)
        val origins = listOf("https://studio.local:8080", "http://studio.local:8080", "https://studio.local:9090")
        origins.forEachIndexed { index, origin ->
            transport.pairingCookie = "token-$index"
            client.pair("$origin/pair?code=test")
        }
        val listener = object : EventStreamListener {
            override fun onApproval(prompt: ApprovalPrompt) = Unit
            override fun onQuestion(nextQuestion: QuestionPrompt) = Unit
            override fun onCancelled(eventId: String) = Unit
            override fun onClosed(message: String) = Unit
        }
        // Return to the first instance after visiting the others.
        listOf(0, 1, 2, 0).forEach { index ->
            val target = pairingTarget(origins[index])
            client.listSessions(target)
            assertEquals("${target.origin}/api/session/list", transport.lastUrl)
            assertEquals("__dsh_device=token-$index", transport.lastCookie)
            client.openEvents(target, listener).close()
            assertEquals("${target.origin}/api/remote.mux", transport.socketUrl)
            assertEquals(target.origin, transport.socketHeaders["Origin"])
            assertEquals("__dsh_device=token-$index", transport.socketHeaders["Cookie"])
        }
    }

    @Test
    fun expiredInstanceDoesNotInvalidateAnotherOnTheSameHost() = runBlocking {
        val transport = FakeTransport()
        val cookies = MemoryCookies()
        val client = DshClient(transport, cookies)
        val first = client.pair("https://studio.local/pair?code=test")
        transport.pairingCookie = "other-token"
        val second = client.pair("http://studio.local/pair?code=test")
        transport.responseStatus = 401
        assertTrue(runCatching { client.listSessions(first) }.isFailure)
        assertNull(client.cookieFor(first))
        assertEquals("other-token", client.cookieFor(second))
        transport.responseStatus = 200
        client.listSessions(second)
        assertEquals("__dsh_device=other-token", transport.lastCookie)
    }

    @Test
    fun newOriginCannotReuseALegacyHostCookie() = runBlocking {
        val transport = FakeTransport()
        val cookies = MemoryCookies()
        cookies.save("studio.local", "legacy-token")
        val client = DshClient(transport, cookies)
        val target = pairingTarget("http://studio.local")
        assertNull(client.cookieFor(target))
        assertTrue(runCatching { client.listSessions(target) }.isFailure)
        assertNull(transport.lastUrl)
    }

    @Test
    fun parsesPairingTargetAndJson() {
        val target = pairingTarget("https://relay.example.com/pair?code=ABC")
        assertEquals("https://relay.example.com", target.origin)
        assertEquals("relay.example.com", target.host)
        val parsed = Json.parse("""{"a":null,"b":[1,true,"x"],"c":{"d":"e"}}""").objOrNull()!!
        assertEquals("e", (parsed["c"]?.objOrNull()?.get("d") as? com.dshremote.app.json.JValue.Str)?.value)
    }

    @Test
    fun filtersArchivedSessionsFromTheHomeList() = runBlocking {
        val transport = FakeTransport()
        val client = DshClient(transport, MemoryCookies())
        val target = client.pair("https://relay.example.com/pair?code=ABC")
        transport.socketFrame =
            """{"type":"item","value":{"type":"baseline","value":{"items":[{"workspaceId":"w1","title":"Current workspace","path":"/work","sessionIds":["s2"]}],"archivedSessionIds":["s1"],"pinnedSessionIds":[]}}}"""
        val rows = RemoteSessionsRepository(client, target).list()
        assertEquals(listOf("s2"), rows.map { it.id })
        assertEquals("w1", rows.single().workspaceId)
        assertEquals("Current workspace", rows.single().workspaceName)
        assertTrue(rows.single().groupedByWorkspace)
        assertTrue(transport.lastUrl.orEmpty().endsWith("/api/session/list"))
    }

    @Test
    fun mapsRelay502HtmlWithoutJsonParsing() = runBlocking {
        val transport = FakeTransport()
        val client = DshClient(transport, MemoryCookies())
        val target = client.pair("https://relay.example.com/pair?code=ABC")
        transport.responseStatus = 502
        transport.responseBody = "<!doctype html><title>502 Bad Gateway</title>"
        val error = runCatching { client.listSessions(target) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("502"), "was: ${error?.message}")
        assertTrue(error?.message.orEmpty().contains("relay"), "was: ${error?.message}")
    }

    @Test
    fun encodesSubagentContinuationPrompt() = runBlocking {
        val transport = FakeTransport()
        val cookies = MemoryCookies()
        val client = DshClient(transport, cookies)
        val target = client.pair("https://relay.example.com/pair?code=ABC")
        client.promptSubagent(
            target,
            parentSessionId = "parent-1",
            childSessionId = "child-1",
            text = "continue",
            delivery = "steer",
            image = PickedImage("image/png", "abc", "dot.png"),
        )
        assertTrue(transport.lastUrl.orEmpty().endsWith("/api/subagents/prompt"))
        val root = Json.parse(transport.lastBody.orEmpty()).objOrNull()!!
        val request = root["payload"]?.objOrNull()?.get("args")?.objOrNull()
            ?.get("request")?.objOrNull() ?: error("missing subagent request")
        assertEquals("parent-1", (request["parentSessionId"] as? com.dshremote.app.json.JValue.Str)?.value)
        assertEquals("child-1", (request["childSessionId"] as? com.dshremote.app.json.JValue.Str)?.value)
        assertEquals("continuable", (request["mode"] as? com.dshremote.app.json.JValue.Str)?.value)
        assertEquals("steer", (request["delivery"] as? com.dshremote.app.json.JValue.Str)?.value)
        val content = (request["content"] as? com.dshremote.app.json.JValue.Arr)?.values.orEmpty()
        assertEquals(2, content.size)
    }

    @Test
    fun pairsAndListsSessionsWithTheDeviceCookie() = runBlocking {
        val transport = FakeTransport()
        val cookies = MemoryCookies()
        val client = DshClient(transport, cookies)
        val target = client.pair("https://relay.example.com/pair?code=ABC")
        assertEquals("device-token", cookies.load(target.origin))
        val rows = client.listSessions(target)
        assertEquals(2, rows.size)
        assertEquals("新会话", rows[0].displayTitle)
        assertEquals("Real session", rows[1].displayTitle)
        assertTrue(rows[1].running)
        assertEquals("__dsh_device=device-token", transport.lastCookie)
    }
}
