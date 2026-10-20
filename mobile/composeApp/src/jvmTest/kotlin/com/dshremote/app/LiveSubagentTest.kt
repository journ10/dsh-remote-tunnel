package com.dshremote.app

import com.dshremote.app.chat.ChatItem
import com.dshremote.app.chat.SessionStreamListener
import com.dshremote.app.chat.followSession
import com.dshremote.app.composer.subagentModes
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LiveSubagentTest {
    @Test
    fun opensAChildSessionWithTheSubagentAddress() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val children = client.listSessions(target).filter { it.parentSessionId != null }
        val parent = children.firstNotNullOfOrNull { child ->
            val modes = runCatching { client.subagentModes(target, child.parentSessionId!!) }.getOrDefault(emptyMap())
            val mode = modes[child.id] ?: return@firstNotNullOfOrNull null
            child.copy(subagentMode = mode)
        }
        assertNotNull(parent, "expected a child session with a subagent mode")
        val child = parent
        val snapshot = CountDownLatch(1)
        val handle = client.followSession(target, child.id, object : SessionStreamListener {
            override fun onSnapshot(nextItems: List<ChatItem>) {
                snapshot.countDown()
            }
            override fun onItems(nextItems: List<ChatItem>) = Unit
            override fun onClosed(message: String) = Unit
        }, parentSessionId = child.parentSessionId, subagentMode = child.subagentMode)
        try {
            assertTrue(snapshot.await(20, TimeUnit.SECONDS), "timed out waiting for subagent snapshot")
        } finally {
            handle.close()
        }
        Unit
    }
}
