package com.dshremote.app

import com.dshremote.app.files.readWorkspaceBytes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveBinaryFileTest {
    @Test
    fun readsAnImageThroughTheBinaryWorkspaceApi() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val session = client.createSession(target, liveTestWorkspace())
        try {
            val blob = client.readWorkspaceBytes(target, session.id, "docs/images/dsh-mobile-main.png")
            assertTrue(blob.bytes.size > 1_000, "expected image bytes, got ${blob.bytes.size}")
            assertTrue(!blob.mediaType.isNullOrBlank(), "media type was ${blob.mediaType}")
        } finally {
            runCatching { client.archive(target, session.id) }
        }
        Unit
    }
}
