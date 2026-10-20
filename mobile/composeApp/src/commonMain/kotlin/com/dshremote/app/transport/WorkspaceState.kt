package com.dshremote.app.transport

import com.dshremote.app.json.Json
import com.dshremote.app.json.arrOrNull
import com.dshremote.app.json.get
import com.dshremote.app.json.objOrNull
import com.dshremote.app.json.strOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

data class RemoteWorkspace(
    val id: String,
    val title: String,
    val path: String,
    val sessionIds: List<String>,
)

data class RemoteWorkspaceState(
    val workspaces: List<RemoteWorkspace>,
    val archivedSessionIds: Set<String>,
) {
    fun workspaceFor(sessionId: String): RemoteWorkspace? =
        workspaces.firstOrNull { sessionId in it.sessionIds }
}

/** Reads the Web-equivalent workspace membership + archived-session set. */
suspend fun DshClient.workspaceState(target: PairingTarget): RemoteWorkspaceState? {
    val cookie = cookieFor(target) ?: error("配对已失效，请重新扫码")
    val result = CompletableDeferred<RemoteWorkspaceState>()
    var handle: SocketHandle? = null
    val open = Json.obj(
        "type" to Json.string("open"),
        "streamId" to Json.string(requestId()),
        "endpoint" to Json.string("workspace/follow"),
        "payload" to Json.obj("args" to "{}"),
    )
    handle = transport.openSocket(
        "${target.origin}/api/remote.mux",
        mapOf("Cookie" to "__dsh_device=$cookie", "Origin" to target.origin),
        open,
        object : SocketListener {
            override fun onOpen() = Unit
            override fun onMessage(text: String) {
                val root = runCatching { Json.parse(text) }.getOrNull()?.objOrNull() ?: return
                if (root.get("type")?.strOrNull() != "item") return
                val frame = root.get("value")?.objOrNull() ?: return
                val type = frame.get("type")?.strOrNull()
                val baseline = if (type == "baseline" || type == "replace") {
                    frame.get("value")?.objOrNull()
                } else {
                    frame
                } ?: return
                val ids = baseline.get("archivedSessionIds")?.arrOrNull()?.values.orEmpty()
                    .mapNotNull { it.strOrNull() }
                    .toSet()
                val workspaces = baseline.get("items")?.arrOrNull()?.values.orEmpty().mapNotNull { element ->
                    val item = element.objOrNull() ?: return@mapNotNull null
                    val id = item.get("workspaceId")?.strOrNull() ?: return@mapNotNull null
                    RemoteWorkspace(
                        id = id,
                        title = item.get("title")?.strOrNull()?.takeUnless { it.isBlank() }
                            ?: item.get("path")?.strOrNull()?.trimEnd('/')?.substringAfterLast('/')
                            ?: id,
                        path = item.get("path")?.strOrNull().orEmpty(),
                        sessionIds = item.get("sessionIds")?.arrOrNull()?.values.orEmpty()
                            .mapNotNull { it.strOrNull() },
                    )
                }
                result.complete(RemoteWorkspaceState(workspaces, ids))
            }

            override fun onClosed(message: String) {
                if (!result.isCompleted) result.complete(RemoteWorkspaceState(emptyList(), emptySet()))
            }
        },
    )
    return try {
        withTimeoutOrNull(15_000) { result.await() }
    } finally {
        handle?.close()
    }
}

suspend fun DshClient.archivedSessionIds(target: PairingTarget): Set<String> =
    workspaceState(target)?.archivedSessionIds ?: emptySet()
