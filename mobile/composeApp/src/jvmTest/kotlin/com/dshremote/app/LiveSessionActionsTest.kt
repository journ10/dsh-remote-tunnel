package com.dshremote.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LiveSessionActionsTest {
    @Test
    fun createsRenamesAndArchivesAgainstTheRelay() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val created = client.createSession(target, liveTestWorkspace())
        val title = "KMP live ${created.id.takeLast(6)}"
        try {
            client.rename(target, created.id, title)
            val found = client.listSessions(target).firstOrNull { it.id == created.id }
            assertNotNull(found, "created session should be listed")
            assertEquals(title, found.displayTitle)
        } finally {
            runCatching { client.archive(target, created.id) }
        }
    }
}
