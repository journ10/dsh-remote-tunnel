package com.dshremote.app

import com.dshremote.app.chat.ChatItem
import com.dshremote.app.chat.loadChat
import com.dshremote.app.chat.sendPrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveChatTest {
    @Test
    fun sendsAndReadsBackAPromptThroughSessionPage() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val session = client.createSession(target, liveTestWorkspace())
        val marker = "KMP chat ${session.id.takeLast(6)}"
        try {
            client.sendPrompt(target, session.id, marker)
            var found = false
            repeat(10) {
                delay(500)
                val page = client.loadChat(target, session.id)
                if (page.items.filterIsInstance<ChatItem.Message>().any { it.role == ChatItem.Message.Role.User && it.text.contains(marker) }) {
                    found = true
                    return@repeat
                }
            }
            assertTrue(found, "prompt should appear in session page history")
        } finally {
            runCatching { client.archive(target, session.id) }
        }
    }
}
