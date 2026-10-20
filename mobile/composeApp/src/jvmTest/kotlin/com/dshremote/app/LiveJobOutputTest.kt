package com.dshremote.app

import com.dshremote.app.chat.sendPrompt
import com.dshremote.app.jobs.JobListListener
import com.dshremote.app.jobs.JobOutputListener
import com.dshremote.app.jobs.JobRow
import com.dshremote.app.jobs.killJob
import com.dshremote.app.jobs.openJobList
import com.dshremote.app.jobs.openJobOutput
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveJobOutputTest {
    @Test
    fun followsABackgroundJobOutput() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val session = client.createSession(target, liveTestWorkspace())
        val rows = CountDownLatch(1)
        val jobId = AtomicReference<String>()
        val listHandle = client.openJobList(target, session.id, object : JobListListener {
            override fun onRows(nextRows: List<JobRow>) {
                if (nextRows.isNotEmpty()) {
                    jobId.compareAndSet(null, nextRows.first().id)
                    rows.countDown()
                }
            }
            override fun onClosed(message: String) = Unit
        })
        var outputHandle: com.dshremote.app.transport.SocketHandle? = null
        try {
            client.sendPrompt(
                target,
                session.id,
                "请使用 bash 工具并设置 run_in_background=true 运行 for i in 1 2 3 4 5; do echo JOB_LINE_\$i; sleep 2; done，不要等待结束，直接回复已启动。",
            )
            assertTrue(rows.await(30, TimeUnit.SECONDS), "timed out waiting for a background job")
            val id = jobId.get() ?: error("missing job id")
            val output = CountDownLatch(1)
            var text = ""
            outputHandle = client.openJobOutput(target, session.id, id, object : JobOutputListener {
                override fun onOutput(next: String) {
                    text += next
                    if (text.contains("JOB_LINE")) output.countDown()
                }
                override fun onClosed(message: String) = Unit
            })
            assertTrue(output.await(20, TimeUnit.SECONDS), "timed out waiting for job output, got: $text")
            runCatching { client.killJob(target, session.id, id) }
        } finally {
            outputHandle?.close()
            listHandle.close()
            runCatching { client.archive(target, session.id) }
        }
        Unit
    }
}
