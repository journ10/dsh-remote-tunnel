package com.dshremote.app

import com.dshremote.app.composer.currentPermission
import com.dshremote.app.composer.executeCommand
import com.dshremote.app.composer.listWorkspace
import com.dshremote.app.composer.sessionMention
import com.dshremote.app.files.readWorkspaceFile
import com.dshremote.app.composer.listCommands
import com.dshremote.app.composer.modelCatalog
import com.dshremote.app.composer.permissionCatalog
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveComposerTest {
    @Test
    fun readsCatalogsAndExecutesAPermissionCommand() = runBlocking {
        val pairUrl = System.getenv("DSH_TEST_PAIR_URL").orEmpty()
        if (pairUrl.isBlank()) return@runBlocking
        val (client, target) = LiveRelay.target()
        val models = client.modelCatalog(target)
        assertTrue(models.options.isNotEmpty(), "model catalog should not be empty")
        val permissions = client.permissionCatalog(target)
        assertTrue(permissions.options.isNotEmpty(), "permission catalog should not be empty")
        val session = client.createSession(target, liveTestWorkspace())
        try {
            val commands = client.listCommands(target, session.id)
            assertTrue(commands.isNotEmpty(), "command catalog should not be empty")
            val targetPermission = permissions.options.first().value
            client.executeCommand(target, session.id, "/permission $targetPermission")
            assertEquals(targetPermission, client.currentPermission(target, session.id))
            val files = client.listWorkspace(target, session.id, ".")
            assertTrue(files.isNotEmpty(), "workspace root should list files")
            assertTrue(sessionMention("session-demo", "Demo").startsWith("@[Demo](dsh-session:"))
            val readme = files.firstOrNull { !it.isDirectory && it.name.endsWith(".md") }
            if (readme != null) {
                val text = client.readWorkspaceFile(target, session.id, readme.path, limit = 20)
                assertTrue(text.text.isNotBlank(), "file reader should return text")
            }
        } finally {
            runCatching { client.archive(target, session.id) }
        }
    }
}
