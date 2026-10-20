package com.dshremote.app.composer

import com.dshremote.app.json.Json
import com.dshremote.app.json.arrOrNull
import com.dshremote.app.json.get
import com.dshremote.app.json.objOrNull
import com.dshremote.app.json.strOrNull
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget

data class WorkspaceEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long?,
)

data class ReferenceSession(val id: String, val title: String, val workspace: String)

suspend fun DshClient.listWorkspace(target: PairingTarget, sessionId: String, path: String): List<WorkspaceEntry> {
    val root = rpcValue(
        target,
        "workspaceFiles/list",
        Json.obj(
            "workspaceFileScopeId" to Json.string(sessionId),
            "path" to Json.string(path.ifBlank { "." }),
        ),
    )
    val value = root.get("result")?.objOrNull()?.get("value")?.objOrNull() ?: return emptyList()
    val base = value.get("path")?.strOrNull().orEmpty().trimEnd('/')
    val parent = path.trimEnd('/').removePrefix("./")
    return value.get("entries")?.arrOrNull()?.values.orEmpty().mapNotNull { element ->
        val obj = element.objOrNull() ?: return@mapNotNull null
        val name = obj.get("name")?.strOrNull() ?: return@mapNotNull null
        val full = when {
            base.isNotEmpty() -> "$base/$name"
            parent.isNotEmpty() && parent != "." -> "$parent/$name"
            else -> name
        }
        WorkspaceEntry(
            name = name,
            path = full,
            isDirectory = obj.get("type")?.strOrNull() == "directory",
            size = null,
        )
    }
}

fun sessionMention(sessionId: String, label: String): String {
    val quoted = Json.string(sessionId)
    val payload = base64Url(quoted.encodeToByteArray())
    val safeLabel = label.replace("\\", "\\\\").replace("]", "\\]")
    return "@[$safeLabel](dsh-session:$payload)"
}

private fun base64Url(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val out = StringBuilder()
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xff
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
        out.append(alphabet[b0 shr 2])
        out.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
        if (i + 1 < bytes.size) out.append(alphabet[((b1 and 0x0f) shl 2) or (b2 shr 6)])
        if (i + 2 < bytes.size) out.append(alphabet[b2 and 0x3f])
        i += 3
    }
    return out.toString()
}
