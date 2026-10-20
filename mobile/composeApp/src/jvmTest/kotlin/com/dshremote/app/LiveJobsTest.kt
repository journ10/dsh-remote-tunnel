package com.dshremote.app

import com.dshremote.app.jobs.JobListListener
import com.dshremote.app.jobs.JobRow
import com.dshremote.app.jobs.openJobList
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveJobsTest {
    @Test
    fun opensTheJobRosterStream() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val session = client.createSession(target, liveTestWorkspace())
        val rows = CountDownLatch(1)
        val handle = client.openJobList(target, session.id, object : JobListListener {
            override fun onRows(nextRows: List<JobRow>) {
                rows.countDown()
            }
            override fun onClosed(message: String) = Unit
        })
        try {
            assertTrue(rows.await(15, TimeUnit.SECONDS), "timed out waiting for job rows")
        } finally {
            handle.close()
            runCatching { client.archive(target, session.id) }
        }
    }
}
