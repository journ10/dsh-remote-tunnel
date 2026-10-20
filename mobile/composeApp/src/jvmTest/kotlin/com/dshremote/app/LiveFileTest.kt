package com.dshremote.app

import com.dshremote.app.chat.ChatItem
import com.dshremote.app.chat.loadChat
import com.dshremote.app.chat.sendPrompt
import com.dshremote.app.chat.uploadFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveFileTest {
    @Test
    fun uploadsAReceiptAndSendsAFilePart() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val session = client.createSession(target, liveTestWorkspace())
        val marker = "KMP file ${session.id.takeLast(6)}"
        try {
            val bytes = "hello from KMP\n".toByteArray()
            val receipt = client.uploadFile(target, session.id, Base64.getEncoder().encodeToString(bytes), "kmp-note.txt")
            assertTrue(receipt.isNotBlank())
            client.sendPrompt(target, session.id, marker, "queue", null, receipt)
            var found = false
            repeat(10) {
                delay(500)
                val page = client.loadChat(target, session.id)
                if (page.items.filterIsInstance<ChatItem.Message>().any { it.text.contains(marker) }) {
                    found = true
                    return@repeat
                }
            }
            assertTrue(found, "file prompt should appear in history")
        } finally {
            runCatching { client.archive(target, session.id) }
        }
    }
}
