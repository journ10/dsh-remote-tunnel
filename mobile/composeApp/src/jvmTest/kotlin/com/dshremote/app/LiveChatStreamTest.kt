package com.dshremote.app

import com.dshremote.app.chat.ChatItem
import com.dshremote.app.chat.SessionStreamListener
import com.dshremote.app.chat.followSession
import com.dshremote.app.chat.sendPrompt
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveChatStreamTest {
    @Test
    fun followsALiveSessionAndReceivesThePrompt() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val session = client.createSession(target, liveTestWorkspace())
        val snapshot = CountDownLatch(1)
        val received = CountDownLatch(1)
        val streamed = CountDownLatch(1)
        val stream = StringBuilder()
        var marker = ""
        val handle = client.followSession(target, session.id, object : SessionStreamListener {
            override fun onSnapshot(nextItems: List<ChatItem>) {
                snapshot.countDown()
            }

            override fun onItems(nextItems: List<ChatItem>) {
                val messages = nextItems.filterIsInstance<ChatItem.Message>()
                if (messages.any { it.role == ChatItem.Message.Role.User && it.text.contains(marker) }) {
                    received.countDown()
                }
            }

            override fun onTextDelta(text: String) {
                stream.append(text)
                if (stream.contains("OK")) streamed.countDown()
            }

            override fun onClosed(message: String) = Unit
        })
        try {
            assertTrue(snapshot.await(15, TimeUnit.SECONDS), "timed out waiting for follow snapshot")
            marker = "只回复 OK（KMP ${session.id.takeLast(6)}）"
            client.sendPrompt(target, session.id, marker)
            assertTrue(received.await(15, TimeUnit.SECONDS), "timed out waiting for live user message")
            assertTrue(streamed.await(30, TimeUnit.SECONDS), "timed out waiting for assistant stream")
        } finally {
            handle.close()
            runCatching { client.archive(target, session.id) }
        }
    }
}
