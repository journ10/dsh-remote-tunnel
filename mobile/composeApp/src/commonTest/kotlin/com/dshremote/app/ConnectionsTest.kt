package com.dshremote.app

import com.dshremote.app.transport.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.*

private class ConnectionCookies : CookieStore {
    val values = mutableMapOf<String, String>()
    override suspend fun load(host: String) = values[host]
    override suspend fun save(host: String, cookie: String) { values[host] = cookie }
    override suspend fun clear(host: String) { values.remove(host) }
}

private class ConnectionDisk {
    val cookies = ConnectionCookies()
    var json: String? = null
    var old: PairingTarget? = null
    fun open(): ConnectionStore = StoredConnectionStore(
        cookies,
        read = { json },
        write = { yield(); json = it },
        legacy = { old },
        clearLegacy = { old = null },
    )
}

class ConnectionsTest {
    private val first = pairingTarget("https://studio.local:8080")
    private val second = pairingTarget("https://laptop.local:8080")
    private val third = pairingTarget("http://studio.local:8080")

    @Test
    fun migratesExistingKmpPairAndCookieOnce() = runBlocking {
        val disk = ConnectionDisk()
        disk.old = first
        disk.cookies.save(first.host, "legacy-token")
        val store = disk.open()
        assertEquals(listOf(first), store.loadAll())
        assertEquals(first, store.load())
        assertEquals("legacy-token", disk.cookies.load(first.origin))
        assertNull(disk.cookies.load(first.host))
        assertNull(disk.old)
        assertEquals(first, disk.open().load())
        assertEquals(listOf(first), disk.open().loadAll())
    }

    @Test
    fun migrationPreservesAnAlreadyRefreshedCookie() = runBlocking {
        val disk = ConnectionDisk()
        disk.old = first
        disk.cookies.save(first.host, "old-token")
        disk.cookies.save(first.origin, "new-token")
        disk.open().load()
        assertEquals("new-token", disk.cookies.load(first.origin))
        assertNull(disk.cookies.load(first.host))
    }

    @Test
    fun additionsAndActiveSelectionSurviveRestart() = runBlocking {
        val disk = ConnectionDisk()
        val store = disk.open()
        store.save(first)
        store.save(second)
        store.save(third)
        assertEquals(third, disk.open().load())
        assertEquals(first, store.setActive(first.origin))
        assertEquals(first, disk.open().load())
        assertEquals(listOf(first, second, third), disk.open().loadAll())
        assertEquals(first, store.setActive("https://unknown.local"))
        assertEquals(first, disk.open().load())
    }

    @Test
    fun rePairUpdatesOneInstanceWithoutDuplicatesOrSavingPairCode() = runBlocking {
        val disk = ConnectionDisk()
        val store = disk.open()
        store.save(first)
        store.save(second)
        store.save(PairingTarget("${first.origin}/pair?code=one-time-secret", "untrusted-label"))
        assertEquals(listOf(first, second), store.loadAll())
        assertEquals(first, disk.open().load())
        assertFalse(disk.json.orEmpty().contains("one-time-secret"))
        assertFalse(disk.json.orEmpty().contains("untrusted-label"))
    }

    @Test
    fun removingInactiveInstanceKeepsActiveInstanceAndItsCookie() = runBlocking {
        val disk = ConnectionDisk()
        val store = disk.open()
        store.save(first)
        store.save(second)
        disk.cookies.save(first.origin, "first")
        disk.cookies.save(second.origin, "second")
        assertEquals(second, store.remove(first.origin))
        assertEquals(listOf(second), disk.open().loadAll())
        assertEquals(second, disk.open().load())
        assertNull(disk.cookies.load(first.origin))
        assertEquals("second", disk.cookies.load(second.origin))
    }

    @Test
    fun removingActiveFallsBackAndRemovingLastReturnsToPairing() = runBlocking {
        val disk = ConnectionDisk()
        disk.old = first
        disk.cookies.save(first.host, "old")
        val store = disk.open()
        store.save(second)
        assertEquals(first, store.remove(second.origin))
        assertEquals(first, disk.open().load())
        assertNull(store.remove(first.origin))
        assertNull(disk.open().load())
        assertTrue(disk.open().loadAll().isEmpty())
        assertTrue(disk.cookies.values.isEmpty())
    }

    @Test
    fun clearDoesNotRestoreLegacyPairOnNextLaunch() = runBlocking {
        val disk = ConnectionDisk()
        disk.old = first
        disk.cookies.save(first.host, "old")
        val store = disk.open()
        store.save(second)
        disk.cookies.save(second.origin, "second")
        store.clear()
        assertTrue(disk.open().loadAll().isEmpty())
        assertNull(disk.open().load())
        assertTrue(disk.cookies.values.isEmpty())
    }

    @Test
    fun registrySkipsInvalidEntriesAndFallsBackFromMissingActive() = runBlocking {
        val disk = ConnectionDisk()
        disk.json = """{"instances":["https://studio.local:8080",null,"file:///tmp","https://studio.local:8080/",42,"https://laptop.local:8080"],"active":"https://missing.local"}"""
        assertEquals(listOf(first, second), disk.open().loadAll())
        assertEquals(first, disk.open().load())
        disk.json = "broken JSON"
        assertTrue(disk.open().loadAll().isEmpty())
        assertNull(disk.open().load())
    }

    @Test
    fun existingEmptyRegistryDoesNotResurrectAnOldPair() = runBlocking {
        val disk = ConnectionDisk()
        disk.old = first
        disk.json = """{"instances":[],"active":null}"""
        assertNull(disk.open().load())
    }

    @Test
    fun overlappingSavesDoNotLoseInstances() = runBlocking {
        val disk = ConnectionDisk()
        val store = disk.open()
        coroutineScope {
            listOf(first, second, third).forEach { instance -> launch { store.save(instance) } }
        }
        assertEquals(setOf(first, second, third), disk.open().loadAll().toSet())
        assertTrue(disk.open().load() in listOf(first, second, third))
    }
}
