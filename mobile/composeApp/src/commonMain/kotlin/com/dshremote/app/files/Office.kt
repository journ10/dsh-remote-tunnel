package com.dshremote.app.files

import com.dshremote.app.json.Json
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget
import com.dshremote.app.transport.requestId

suspend fun DshClient.renderOfficePdf(
    target: PairingTarget,
    sessionId: String,
    path: String,
): WorkspaceBlob {
    val cookie = cookieFor(target) ?: error("配对已失效，请重新配对")
    val args = Json.obj(
        "workspaceFileScopeId" to Json.string(sessionId),
        "path" to Json.string(path),
        "priority" to Json.string("foreground"),
    )
    val envelope = Json.obj(
        "type" to Json.string("client-request"),
        "rpcId" to Json.string(requestId()),
        "method" to Json.string("officeToPdf/render"),
        "payload" to Json.obj("args" to args),
    )
    val result = transport.postBytes(
        "${target.origin}/api/officeToPdf/render",
        envelope,
        mapOf(
            "Cookie" to "__dsh_device=$cookie",
            "Origin" to target.origin,
            "Content-Type" to "application/json; charset=utf-8",
        ),
    )
    if (result.status == 401 || result.status == 403) error("设备配对已失效")
    if (result.status !in 200..299) error("Office 转换失败：HTTP ${result.status}")
    val contentType = result.headers.entries
        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
        ?.value?.firstOrNull().orEmpty()
    val boundary = contentType.substringAfter("boundary=", "").trim().trim('"')
    if (contentType.startsWith("multipart/", ignoreCase = true) && boundary.isNotEmpty()) {
        return parseWorkspaceBlob(result.bytes, boundary)
    }
    return WorkspaceBlob(result.bytes, "application/pdf", 0, true, result.bytes.size.toLong())
}
