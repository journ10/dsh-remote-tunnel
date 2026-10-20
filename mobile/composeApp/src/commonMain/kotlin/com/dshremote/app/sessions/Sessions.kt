package com.dshremote.app.sessions

/** Platform-neutral session row used by the shared home screen. */
data class SessionRow(
    val id: String,
    val title: String,
    val cwd: String?,
    val running: Boolean,
    val blank: Boolean,
    val isSubagent: Boolean = false,
    val updatedAt: Long? = null,
    val parentSessionId: String? = null,
    val subagentMode: String? = null,
    val workspaceId: String? = null,
    val workspaceTitle: String? = null,
    val groupedByWorkspace: Boolean = false,
) {
    val continuableSubagent: Boolean
        get() = isSubagent && subagentMode == "continuable"
    val displayTitle: String
        get() {
            val clean = title.trim().takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
            return when {
                clean != null -> clean
                blank -> "新会话"
                else -> "未命名会话"
            }
        }

    val workspaceName: String
        get() = when {
            groupedByWorkspace && workspaceId == null -> "未分组"
            !workspaceTitle.isNullOrBlank() -> workspaceTitle!!
            else -> cwd?.trimEnd('/')?.substringAfterLast('/')?.takeUnless { it.isBlank() } ?: "默认工作区"
        }
}

data class SessionGroup(val workspace: String, val sessions: List<SessionRow>, val key: String = workspace)

interface SessionsRepository {
    suspend fun list(): List<SessionRow>
    suspend fun create(cwd: String?): SessionRow
    suspend fun rename(id: String, title: String)
    suspend fun fork(id: String): SessionRow
    suspend fun archive(id: String)
}

fun groupSessions(rows: List<SessionRow>, now: Long = currentEpochMillis()): List<SessionGroup> {
    val visible = visibleSessions(rows, now)
    if (visible.none { it.groupedByWorkspace }) {
        return visible.groupBy { it.cwd ?: "" }
            .map { (cwd, sessions) -> SessionGroup(cwd, sessions) }
    }
    val workspaceGroups = visible.filter { it.workspaceId != null }
        .groupBy { it.workspaceId!! }
        .map { (id, sessions) ->
            val label = sessions.firstOrNull()?.workspaceTitle?.takeUnless { it.isBlank() }
                ?: sessions.firstOrNull()?.workspaceName
                ?: id
            SessionGroup(label, sessions, "workspace:$id")
        }
    val ungrouped = visible.filter { it.workspaceId == null }
    return if (ungrouped.isEmpty()) workspaceGroups else workspaceGroups + SessionGroup("未分组", ungrouped, "ungrouped")
}

/** Filter within groups so search cannot change their identity or membership. */
fun filterSessionGroups(
    rows: List<SessionRow>,
    query: String,
    runningOnly: Boolean,
    now: Long = currentEpochMillis(),
): List<SessionGroup> {
    val needle = query.trim()
    return groupSessions(rows, now).mapNotNull { group ->
        val matches = group.sessions.filter { session ->
            (!runningOnly || session.running) &&
                (needle.isBlank() || session.displayTitle.contains(needle, ignoreCase = true) ||
                    session.workspaceName.contains(needle, ignoreCase = true))
        }
        group.copy(sessions = matches).takeIf { matches.isNotEmpty() }
    }
}
