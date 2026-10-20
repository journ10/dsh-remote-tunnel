package com.dshremote.app

import com.dshremote.app.attachments.PickedImage
import com.dshremote.app.chat.ChatItem
import com.dshremote.app.chat.loadChat
import com.dshremote.app.chat.sendPrompt
import com.dshremote.app.chat.uploadFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveAttachmentOnlyTest {
    @Test
    fun sendsImageOnlyAndFileOnlyPrompts() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="
        val imageSession = client.createSession(target, liveTestWorkspace())
        try {
            client.sendPrompt(target, imageSession.id, "", "queue", PickedImage("image/png", png, "dot.png"))
            var found = false
            repeat(20) {
                delay(500)
                val page = client.loadChat(target, imageSession.id)
                if (page.items.filterIsInstance<ChatItem.Message>().any { it.text.contains("[图片]") }) {
                    found = true
                    return@repeat
                }
            }
            assertTrue(found, "image-only prompt did not appear in history")
        } finally {
            runCatching { client.archive(target, imageSession.id) }
        }

        val fileSession = client.createSession(target, liveTestWorkspace())
        try {
            val receipt = client.uploadFile(
                target,
                fileSession.id,
                Base64.getEncoder().encodeToString("hello\n".toByteArray()),
                "hello.txt",
            )
            client.sendPrompt(target, fileSession.id, "", "queue", null, receipt)
            var found = false
            repeat(20) {
                delay(500)
                val page = client.loadChat(target, fileSession.id)
                if (page.items.filterIsInstance<ChatItem.Message>().any { it.text.contains("[文件]") }) {
                    found = true
                    return@repeat
                }
            }
            assertTrue(found, "file-only prompt did not appear in history")
        } finally {
            runCatching { client.archive(target, fileSession.id) }
        }
        Unit
    }
}
