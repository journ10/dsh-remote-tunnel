package com.dshremote.app

import com.dshremote.app.sessions.SessionRow
import com.dshremote.app.sessions.groupSessions
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionModelTest {
    @Test
    fun nullTitlesFallBackWithoutDisplayingTheWordNull() {
        assertEquals("新会话", SessionRow("a", "null", null, running = false, blank = true).displayTitle)
        assertEquals("未命名会话", SessionRow("b", "", null, running = false, blank = false).displayTitle)
    }

    @Test
    fun subagentsAreExcludedFromTheHomeGrouping() {
        val rows = listOf(
            SessionRow("parent", "Parent", "/work", running = false, blank = false),
            SessionRow("child", "Child", "/work", running = false, blank = false, isSubagent = true),
        )
        val groups = groupSessions(rows)
        assertEquals(1, groups.size)
        assertEquals(listOf("parent"), groups.single().sessions.map { it.id })
    }
}
