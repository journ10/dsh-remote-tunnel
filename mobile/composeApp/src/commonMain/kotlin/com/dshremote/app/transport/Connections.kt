package com.dshremote.app.transport

import com.dshremote.app.json.Json
import com.dshremote.app.json.arrOrNull
import com.dshremote.app.json.get
import com.dshremote.app.json.objOrNull
import com.dshremote.app.json.strOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared multi-instance persistence. Platform adapters only store one JSON value.
 * Device cookies are keyed by origin (scheme + authority, including port).
 * Pairing URLs and one-time codes are never included in the saved registry.
 */
class StoredConnectionStore(
    private val cookies: CookieStore,
    private val read: suspend () -> String?,
    private val write: suspend (String) -> Unit,
    private val legacy: suspend () -> PairingTarget? = { null },
    private val clearLegacy: suspend () -> Unit = {},
) : ConnectionStore {
    private val mutex = Mutex()

    override suspend fun loadAll(): List<PairingTarget> = mutex.withLock { state().instances }

    override suspend fun load(): PairingTarget? = mutex.withLock { state().active }

    override suspend fun save(target: PairingTarget) = mutex.withLock {
        val clean = pairingTarget(target.origin)
        val current = state()
        val instances = if (current.instances.any { it.origin == clean.origin }) {
            current.instances.map { if (it.origin == clean.origin) clean else it }
        } else current.instances + clean
        persist(ConnectionState(instances, clean.origin))
    }

    override suspend fun setActive(origin: String): PairingTarget? = mutex.withLock {
        val current = state()
        val selected = current.instances.firstOrNull { it.origin == origin }
        if (selected != null) persist(current.copy(activeOrigin = selected.origin))
        selected ?: current.active
    }

    override suspend fun remove(origin: String): PairingTarget? = mutex.withLock {
        val current = state()
        val remaining = current.instances.filterNot { it.origin == origin }
        val next = ConnectionState(remaining, current.activeOrigin)
        persist(next)
        cookies.clear(origin)
        next.active
    }

    override suspend fun clear() = mutex.withLock {
        val current = state()
        persist(ConnectionState(emptyList(), null))
        current.instances.forEach { cookies.clear(it.origin) }
        clearLegacy()
    }

    private suspend fun state(): ConnectionState {
        read()?.let { return decode(it) }
        val previous = legacy()
        val clean = previous?.let { runCatching { pairingTarget(it.origin) }.getOrNull() }
        if (clean != null && cookies.load(clean.origin) == null) {
            // Only the saved legacy target may receive the old host-keyed cookie.
            // No fallback during requests: a new instance must have its own token.
            cookies.load(previous.host)?.let { cookies.save(clean.origin, it) }
        }
        val migrated = ConnectionState(listOfNotNull(clean), clean?.origin)
        persist(migrated)
        clearLegacy()
        previous?.let { cookies.clear(it.host) }
        return migrated
    }

    private suspend fun persist(state: ConnectionState) {
        write(Json.obj(
            "instances" to Json.arr(*state.instances.map { Json.string(it.origin) }.toTypedArray()),
            "active" to (state.active?.origin?.let(Json::string) ?: "null"),
        ))
    }

    private fun decode(raw: String): ConnectionState {
        val root = runCatching { Json.parse(raw).objOrNull() }.getOrNull()
        val instances = root?.get("instances")?.arrOrNull()?.values.orEmpty().mapNotNull {
            it.strOrNull()?.let { origin -> runCatching { pairingTarget(origin) }.getOrNull() }
        }.distinctBy { it.origin }
        return ConnectionState(instances, root?.get("active")?.strOrNull())
    }
}

private data class ConnectionState(val instances: List<PairingTarget>, val activeOrigin: String?) {
    val active: PairingTarget?
        get() = instances.firstOrNull { it.origin == activeOrigin } ?: instances.firstOrNull()
}

/** Used by the JVM verification host and the not-yet-connected OHOS adapter. */
fun memoryConnectionStore(cookies: CookieStore): ConnectionStore {
    var stored: String? = null
    return StoredConnectionStore(cookies, read = { stored }, write = { stored = it })
}
