package com.dshremote.app

import com.dshremote.app.sessions.SessionRow
import com.dshremote.app.sessions.groupSessions
import com.dshremote.app.sessions.visibleSessions
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionStaleTest {
    @Test
    fun hidesOnlyOldBlankSessions() {
        val now = 10_000_000L
        val rows = listOf(
            SessionRow("old", "", "/tmp", false, true, updatedAt = now - 11 * 60_000),
            SessionRow("new", "", "/tmp", false, true, updatedAt = now - 60_000),
            SessionRow("running", "", "/tmp", true, true, updatedAt = now - 11 * 60_000),
            SessionRow("titled", "Saved", "/tmp", false, true, updatedAt = now - 11 * 60_000),
            SessionRow("unmarked-child", "child", "/tmp", false, false, parentSessionId = "p"),
            SessionRow("working-title", "You are working in /tmp", "/tmp", false, false),
            SessionRow("test-live", "KMP live ab93b", "/tmp", false, false),
            SessionRow("test-question", "调用单选问题测试", "/tmp", false, false),
        )
        assertEquals(listOf("new", "running", "titled"), visibleSessions(rows, now).map { it.id })
        val grouped = groupSessions(
            listOf(
                SessionRow("in-ws", "In workspace", "/work", false, false, groupedByWorkspace = true, workspaceId = "w1", workspaceTitle = "Current"),
                SessionRow("no-ws", "Not in workspace", "/work", false, false, groupedByWorkspace = true),
            ),
        )
        assertEquals(listOf("Current", "未分组"), grouped.map { it.workspace })
    }
}
