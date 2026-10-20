package com.dshremote.app.sessions

expect fun currentEpochMillis(): Long

/** Hide blank sessions that have not changed for 10 minutes, matching the native client. */
private val TEST_SESSION_TITLE = Regex(
    "^(KMP (live|chat|image|file) |只回复 OK 测试|调用单选问题测试|运行 echo KMP_TOOL_TEST)",
)

fun visibleSessions(rows: List<SessionRow>, now: Long = currentEpochMillis()): List<SessionRow> {
    return rows.filterNot { row ->
        row.isSubagent || row.parentSessionId != null ||
            row.title.startsWith("You are working in ") ||
            TEST_SESSION_TITLE.containsMatchIn(row.title) || (
            row.blank && row.title.isBlank() && !row.running &&
                row.updatedAt != null && now - row.updatedAt > 10 * 60 * 1000
            )
    }
}
