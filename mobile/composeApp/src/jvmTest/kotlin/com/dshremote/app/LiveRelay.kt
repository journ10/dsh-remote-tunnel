package com.dshremote.app

import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget
import com.dshremote.app.transport.platformServices

/** Absolute path on the DSH host; use a disposable test checkout. */
fun liveTestWorkspace(): String =
    System.getenv("DSH_TEST_WORKSPACE")?.takeIf { it.isNotBlank() }
        ?: error("DSH_TEST_WORKSPACE is required for live tests")

/** One pairing for all live JVM tests in a run; pairing URLs are single-use. */
object LiveRelay {
    private val services by lazy { platformServices() }
    val client: DshClient by lazy { DshClient(services.transport, services.cookies) }
    private var paired: PairingTarget? = null

    suspend fun target(): Pair<DshClient, PairingTarget> {
        val existing = paired
        if (existing != null) return client to existing
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        check(pairUrl.isNotBlank()) { "DSH_TEST_PAIR_URL is required for live tests" }
        val target = client.pair(pairUrl)
        paired = target
        return client to target
    }
}
