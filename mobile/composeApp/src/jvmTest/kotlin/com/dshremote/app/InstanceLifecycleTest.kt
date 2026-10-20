package com.dshremote.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import com.dshremote.app.transport.*
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.test.*

/** Mounts the real instance subtree and verifies disposal without a live relay. */
class InstanceLifecycleTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun switchingAndRePairingCloseOldEventStreams() {
        val first = pairingTarget("https://first.local")
        val second = pairingTarget("https://second.local")
        val transport = LifecycleTransport()
        val cookies = object : CookieStore {
            override suspend fun load(host: String) = "test-cookie"
            override suspend fun save(host: String, cookie: String) = Unit
            override suspend fun clear(host: String) = Unit
        }
        val services = object : PlatformServices {
            override val transport = transport
            override val cookies = cookies
            override val connections = memoryConnectionStore(cookies)
        }
        val selected = mutableStateOf(first)
        val revision = mutableStateOf(0)
        lateinit var scene: ImageComposeScene
        var frame = 0L
        fun render() = SwingUtilities.invokeAndWait {
            scene.render(frame++ * 16_666_667L).close()
        }
        fun awaitStreams(count: Int) {
            repeat(100) {
                render()
                if (transport.events.size == count) return
                Thread.sleep(10)
            }
            assertEquals(count, transport.events.size, "event subscription count")
        }
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(375, 812) {
                DSHRemoteTheme {
                    InstanceWorkspace(services, selected.value, revision.value, {}, {})
                }
            }
        }
        try {
            awaitStreams(1)
            assertEquals("${first.origin}/api/remote.mux", transport.events[0].url)
            SwingUtilities.invokeAndWait { selected.value = second }
            awaitStreams(2)
            assertTrue(transport.events[0].closed, "switching must close the old socket")
            assertFalse(transport.events[1].closed)
            assertEquals("${second.origin}/api/remote.mux", transport.events[1].url)

            // A late callback must not restart the disposed instance.
            transport.events[0].listener.onClosed("late failure")
            repeat(3) { render() }
            assertEquals(2, transport.events.size)

            SwingUtilities.invokeAndWait { selected.value = first }
            awaitStreams(3)
            assertTrue(transport.events[1].closed)
            assertFalse(transport.events[2].closed)
            SwingUtilities.invokeAndWait { revision.value++ }
            awaitStreams(4)
            assertTrue(transport.events[2].closed, "re-pairing the same origin must reconnect")
            assertFalse(transport.events[3].closed)
        } finally {
            SwingUtilities.invokeAndWait { scene.close() }
        }
        assertTrue(transport.events.all { it.closed }, "leaving the app must close every socket")
    }
}

private class LifecycleSocket(val url: String, val listener: SocketListener) : SocketHandle {
    @Volatile var closed = false
    override fun send(text: String) = Unit
    override fun close() { closed = true }
}

private class LifecycleTransport : RemoteTransport {
    val events = CopyOnWriteArrayList<LifecycleSocket>()
    override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean) =
        HttpResult(200, "", emptyMap())
    override suspend fun post(url: String, body: String, headers: Map<String, String>) =
        HttpResult(200, """{"result":{"ok":true,"value":{"items":[]}}}""", emptyMap())
    override suspend fun postBytes(url: String, body: String, headers: Map<String, String>) =
        BinaryHttpResult(200, byteArrayOf(), emptyMap())
    override suspend fun openSocket(url: String, headers: Map<String, String>, initialFrame: String, listener: SocketListener): SocketHandle {
        val socket = LifecycleSocket(url, listener)
        if (initialFrame.contains("\$events")) events.add(socket)
        else listener.onMessage("""{"type":"item","value":{"type":"baseline","value":{"items":[],"archivedSessionIds":[],"pinnedSessionIds":[]}}}""")
        return socket
    }
}
