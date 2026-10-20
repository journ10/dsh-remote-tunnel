package com.dshremote.app

import com.dshremote.app.chat.sendPrompt
import com.dshremote.app.composer.executeCommand
import com.dshremote.app.composer.permissionCatalog
import com.dshremote.app.events.ApprovalPrompt
import com.dshremote.app.events.EventStreamListener
import com.dshremote.app.events.QuestionPrompt
import com.dshremote.app.events.answerApproval
import com.dshremote.app.events.openEvents
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveApprovalTest {
    @Test
    fun allowsAReadOnlyBashApproval() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val session = client.createSession(target, liveTestWorkspace())
        val approvals = CountDownLatch(1)
        var approval: ApprovalPrompt? = null
        val handle = client.openEvents(target, object : EventStreamListener {
            override fun onApproval(prompt: ApprovalPrompt) {
                approval = prompt
                approvals.countDown()
            }
            override fun onQuestion(nextQuestion: QuestionPrompt) = Unit
            override fun onCancelled(eventId: String) = Unit
            override fun onClosed(message: String) = Unit
        })
        try {
            val presets = client.permissionCatalog(target)
            val readOnly = presets.options.firstOrNull { it.value.contains("read", ignoreCase = true) }?.value
                ?: presets.options.first().value
            client.executeCommand(target, session.id, "/permission $readOnly")
            client.sendPrompt(
                target,
                session.id,
                "请使用 bash 工具运行 echo KMP_APPROVAL > /tmp/kmp-approval-test，然后只回复 done。",
            )
            assertTrue(approvals.await(30, TimeUnit.SECONDS), "timed out waiting for approval/request")
            val prompt = approval ?: error("missing approval prompt")
            assertTrue(prompt.eventId.isNotBlank())
            client.answerApproval(target, prompt, allowed = true)
        } finally {
            handle.close()
            runCatching { client.archive(target, session.id) }
        }
    }
}
