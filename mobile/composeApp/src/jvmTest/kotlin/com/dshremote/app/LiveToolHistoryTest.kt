package com.dshremote.app

import com.dshremote.app.chat.ChatItem
import com.dshremote.app.chat.loadChat
import com.dshremote.app.chat.sendPrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveToolHistoryTest {
    @Test
    fun readsToolCardsAfterAToolCall() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val session = client.createSession(target, liveTestWorkspace())
        try {
            client.sendPrompt(
                target,
                session.id,
                "请使用 bash 工具运行 echo KMP_TOOL_TEST，然后只回复输出内容。",
            )
            var found = false
            repeat(30) {
                delay(1_000)
                val page = client.loadChat(target, session.id, maxMessages = 100)
                if (page.items.any { it is ChatItem.Tool && it.result != null }) {
                    found = true
                    return@repeat
                }
            }
            assertTrue(found, "expected a completed tool card in the session page")
        } finally {
            runCatching { client.archive(target, session.id) }
        }
    }
}
