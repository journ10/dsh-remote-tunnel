package com.dshremote.app.files

import com.dshremote.app.json.Json
import com.dshremote.app.json.get
import com.dshremote.app.json.longOrNull
import com.dshremote.app.json.objOrNull
import com.dshremote.app.json.strOrNull
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget

data class WorkspaceText(
    val text: String,
    val lines: Int,
    val eof: Boolean,
    val bytes: Long?,
)

suspend fun DshClient.readWorkspaceFile(
    target: PairingTarget,
    sessionId: String,
    path: String,
    offset: Int = 1,
    limit: Int = 2000,
): WorkspaceText {
    val root = rpcValue(
        target,
        "workspaceFiles/read",
        Json.obj(
            "workspaceFileScopeId" to Json.string(sessionId),
            "path" to Json.string(path),
            "range" to Json.obj("offset" to offset.toString(), "limit" to limit.toString()),
        ),
    )
    val value = root.get("result")?.objOrNull()?.get("value")?.objOrNull()
        ?: error("文件读取返回格式错误")
    return WorkspaceText(
        text = value.get("text")?.strOrNull().orEmpty(),
        lines = value.get("lines")?.longOrNull()?.toInt() ?: 0,
        eof = (value.get("eof") as? com.dshremote.app.json.JValue.Bool)?.value ?: false,
        bytes = value.get("bytes")?.longOrNull(),
    )
}
