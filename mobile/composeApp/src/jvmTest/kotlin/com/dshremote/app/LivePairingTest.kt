package com.dshremote.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class LivePairingTest {
    @Test
    fun pairsAndListsSessionsAgainstTheRelay() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val rows = client.listSessions(target)
        assertTrue(rows.isNotEmpty(), "expected at least one real session")
    }
}
