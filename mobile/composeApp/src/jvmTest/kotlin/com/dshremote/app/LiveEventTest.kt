package com.dshremote.app

import com.dshremote.app.events.EventStreamListener
import com.dshremote.app.events.ApprovalPrompt
import com.dshremote.app.events.QuestionDraft
import com.dshremote.app.events.QuestionPrompt
import com.dshremote.app.events.answerQuestion
import com.dshremote.app.events.openEvents
import com.dshremote.app.chat.sendPrompt
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveEventTest {
    @Test
    fun answersAUserQuestionOverTheEventStream() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val session = client.createSession(target, liveTestWorkspace())
        val received = CountDownLatch(1)
        var prompt: QuestionPrompt? = null
        val handle = client.openEvents(target, object : EventStreamListener {
            override fun onApproval(prompt: ApprovalPrompt) = Unit
            override fun onQuestion(nextQuestion: QuestionPrompt) {
                prompt = nextQuestion
                received.countDown()
            }
            override fun onCancelled(eventId: String) = Unit
            override fun onClosed(message: String) = Unit
        })
        try {
            client.sendPrompt(
                target,
                session.id,
                "请调用 ask_user_question 问我一个单选问题，问题为“选 A 还是 B？”，选项提供 A 和 B；收到回答后只回复 DONE。",
            )
            assertTrue(received.await(30, TimeUnit.SECONDS), "timed out waiting for user-questions/request")
            val question = prompt ?: error("missing prompt")
            assertTrue(question.questions.isNotEmpty())
            val first = question.questions.first()
            val label = first.options.firstOrNull()?.label ?: "A"
            client.answerQuestion(target, question, mapOf(first.id to QuestionDraft(selected = setOf(label))))
        } finally {
            handle.close()
            runCatching { client.archive(target, session.id) }
        }
    }
}
