package com.dshremote.app

import com.dshremote.app.attachments.PickedImage
import com.dshremote.app.chat.ChatItem
import com.dshremote.app.chat.loadChat
import com.dshremote.app.chat.sendPrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveImageTest {
    @Test
    fun sendsAnInlineImagePrompt() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val session = client.createSession(target, liveTestWorkspace())
        val marker = "KMP image ${session.id.takeLast(6)}"
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="
        try {
            client.sendPrompt(target, session.id, marker, "queue", PickedImage("image/png", png, "dot.png"))
            var found = false
            repeat(10) {
                delay(500)
                val page = client.loadChat(target, session.id)
                if (page.items.filterIsInstance<ChatItem.Message>().any { it.text.contains(marker) }) {
                    found = true
                    return@repeat
                }
            }
            assertTrue(found, "image prompt should appear in history")
        } finally {
            runCatching { client.archive(target, session.id) }
        }
    }
}
