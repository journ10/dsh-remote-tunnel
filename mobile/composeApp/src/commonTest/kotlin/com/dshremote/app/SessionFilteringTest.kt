package com.dshremote.app

import com.dshremote.app.sessions.SessionRow
import com.dshremote.app.sessions.filterSessionGroups
import com.dshremote.app.sessions.groupSessions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SessionFilteringTest {
    private fun row(id: String, workspace: String, running: Boolean = false) = SessionRow(
        id, "Review $id", "/work/$workspace", running, false,
        workspaceId = workspace, workspaceTitle = "same-name", groupedByWorkspace = true,
    )

    @Test fun sameNamedWorkspacesKeepDistinctCollapseKeys() {
        val groups = groupSessions(listOf(row("a", "first"), row("b", "second")))
        assertEquals(groups[0].workspace, groups[1].workspace)
        assertNotEquals(groups[0].key, groups[1].key)
    }

    @Test fun searchAndRunningFilterIntersectWithoutRegrouping() {
        val rows = listOf(row("a", "first", true), row("b", "first"), row("c", "second", true))
        val all = groupSessions(rows)
        val filtered = filterSessionGroups(rows, "  REVIEW A  ", true)
        assertEquals(listOf("a"), filtered.single().sessions.map { it.id })
        assertEquals(all.first().key, filtered.single().key)
        assertEquals(2, filterSessionGroups(rows, "same-name", true).sumOf { it.sessions.size })
        assertEquals(emptyList(), filterSessionGroups(rows, "missing", false))
    }

    @Test fun childSessionsNeverLeakIntoRunningCountsOrSearch() {
        val rows = listOf(row("parent", "work"), row("child", "work", true).copy(isSubagent = true))
        assertEquals(emptyList(), filterSessionGroups(rows, "", true))
        assertEquals(emptyList(), filterSessionGroups(rows, "child", false))
    }
}
