package com.dshremote.app

import com.dshremote.app.terminal.TerminalInfo
import com.dshremote.app.terminal.TerminalListener
import com.dshremote.app.terminal.closeTerminal
import com.dshremote.app.terminal.createTerminal
import com.dshremote.app.terminal.newTerminalId
import com.dshremote.app.terminal.openTerminal
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveTerminalTest {
    @Test
    fun echoesThroughATerminal() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val session = client.createSession(target, liveTestWorkspace())
        val id = newTerminalId()
        val snapshot = CountDownLatch(1)
        val echoed = CountDownLatch(1)
        var connection: com.dshremote.app.terminal.TerminalConnection? = null
        try {
            client.createTerminal(target, session.id, id)
            connection = client.openTerminal(target, session.id, id, object : TerminalListener {
                override fun onSnapshot(screen: String, info: TerminalInfo, writable: Boolean) {
                    snapshot.countDown()
                }

                override fun onOutput(data: String) {
                    if (data.contains("KMP_TERM")) echoed.countDown()
                }

                override fun onState(info: TerminalInfo, writable: Boolean) = Unit
                override fun onClosed(message: String) = Unit
            })
            assertTrue(snapshot.await(15, TimeUnit.SECONDS), "timed out waiting for terminal snapshot")
            connection.write("echo KMP_TERM\n")
            assertTrue(echoed.await(15, TimeUnit.SECONDS), "timed out waiting for terminal echo")
        } finally {
            connection?.close()
            runCatching { client.closeTerminal(target, session.id, id) }
            runCatching { client.archive(target, session.id) }
        }
    }
}
