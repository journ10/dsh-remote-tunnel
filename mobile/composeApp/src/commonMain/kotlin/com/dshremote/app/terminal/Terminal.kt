package com.dshremote.app.terminal

import com.dshremote.app.json.Json
import com.dshremote.app.json.get
import com.dshremote.app.json.longOrNull
import com.dshremote.app.json.objOrNull
import com.dshremote.app.json.strOrNull
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget
import com.dshremote.app.transport.SocketHandle
import com.dshremote.app.transport.SocketListener
import com.dshremote.app.transport.requestId

data class TerminalInfo(
    val id: String,
    val title: String,
    val shellName: String,
    val cwd: String,
    val state: String,
    val cols: Int,
    val rows: Int,
) {
    val running: Boolean get() = state == "running"
}

interface TerminalListener {
    fun onSnapshot(screen: String, info: TerminalInfo, writable: Boolean)
    fun onOutput(data: String)
    fun onState(info: TerminalInfo, writable: Boolean)
    fun onClosed(message: String)
}

suspend fun DshClient.createTerminal(
    target: PairingTarget,
    sessionId: String,
    id: String,
    cols: Int = 80,
    rows: Int = 24,
): TerminalInfo {
    val root = rpcValue(
        target,
        "terminal/create",
        Json.obj(
            "agentId" to Json.string(sessionId),
            "request" to Json.obj(
                "id" to Json.string(id),
                "cols" to cols.toString(),
                "rows" to rows.toString(),
            ),
        ),
    )
    val value = root.get("result")?.objOrNull()?.get("value")?.objOrNull()
        ?: error("终端创建返回格式错误")
    return parseTerminal(value)
}

suspend fun DshClient.closeTerminal(target: PairingTarget, sessionId: String, id: String) {
    rpcValue(
        target,
        "terminal/close",
        Json.obj("agentId" to Json.string(sessionId), "id" to Json.string(id)),
    )
}

class TerminalConnection internal constructor(
    private val handle: SocketHandle,
    private val streamId: String,
    var info: TerminalInfo,
    var writable: Boolean,
) {
    private var sequence = 0

    fun write(data: String) {
        sequence += 1
        handle.send(
            Json.obj(
                "type" to Json.string("item"),
                "streamId" to Json.string(streamId),
                "value" to Json.obj(
                    "type" to Json.string("write"),
                    "sequence" to sequence.toString(),
                    "data" to Json.string(data),
                ),
            ),
        )
    }

    fun sendLine(text: String) {
        write(if (text.endsWith("\n")) text else "$text\n")
    }

    fun sendControl() {
        write("\u0003")
    }

    fun resize(cols: Int, rows: Int) {
        sequence += 1
        handle.send(
            Json.obj(
                "type" to Json.string("item"),
                "streamId" to Json.string(streamId),
                "value" to Json.obj(
                    "type" to Json.string("resize"),
                    "sequence" to sequence.toString(),
                    "cols" to cols.toString(),
                    "rows" to rows.toString(),
                ),
            ),
        )
    }

    fun close() {
        handle.close()
    }
}

suspend fun DshClient.openTerminal(
    target: PairingTarget,
    sessionId: String,
    id: String,
    listener: TerminalListener,
): TerminalConnection {
    val cookie = cookieFor(target) ?: error("配对已失效，请重新配对")
    val streamId = requestId()
    val open = Json.obj(
        "type" to Json.string("open"),
        "streamId" to Json.string(streamId),
        "endpoint" to Json.string("terminal/attach"),
        "payload" to Json.obj(
            "args" to Json.obj(
                "agentId" to Json.string(sessionId),
                "id" to Json.string(id),
            ),
        ),
    )
    var info = TerminalInfo(id, "terminal", "shell", "", "running", 80, 24)
    var writable = true
    val handle = transport.openSocket(
        "${target.origin}/api/remote.mux",
        mapOf("Cookie" to "__dsh_device=$cookie", "Origin" to target.origin),
        open,
        object : SocketListener {
            override fun onOpen() = Unit
            override fun onMessage(text: String) {
                val root = runCatching { Json.parse(text) }.getOrNull()?.objOrNull() ?: return
                if (root.get("type")?.strOrNull() != "item") return
                val value = root.get("value")?.objOrNull() ?: return
                when (value.get("type")?.strOrNull()) {
                    "snapshot" -> {
                        val parsed = value.get("info")?.objOrNull()?.let(::parseTerminal) ?: return
                        info = parsed
                        writable = (value.get("writable") as? com.dshremote.app.json.JValue.Bool)?.value ?: true
                        listener.onSnapshot(value.get("screen")?.strOrNull().orEmpty(), parsed, writable)
                    }
                    "output" -> listener.onOutput(value.get("data")?.strOrNull().orEmpty())
                    "state" -> {
                        val parsed = value.get("info")?.objOrNull()?.let(::parseTerminal) ?: return
                        info = parsed
                        writable = (value.get("writable") as? com.dshremote.app.json.JValue.Bool)?.value ?: writable
                        listener.onState(parsed, writable)
                    }
                }
            }

            override fun onClosed(message: String) = listener.onClosed(message)
        },
    )
    return TerminalConnection(handle, streamId, info, writable)
}

private fun parseTerminal(value: com.dshremote.app.json.JValue.Obj): TerminalInfo {
    val shell = value.get("shell")?.objOrNull()
    return TerminalInfo(
        id = value.get("id")?.strOrNull().orEmpty(),
        title = value.get("title")?.strOrNull()?.takeUnless { it.isBlank() }
            ?: shell?.get("name")?.strOrNull() ?: "terminal",
        shellName = shell?.get("name")?.strOrNull()?.takeUnless { it.isBlank() } ?: "shell",
        cwd = value.get("cwd")?.strOrNull().orEmpty(),
        state = value.get("state")?.strOrNull()?.takeUnless { it.isBlank() } ?: "running",
        cols = value.get("cols")?.longOrNull()?.toInt() ?: 80,
        rows = value.get("rows")?.longOrNull()?.toInt() ?: 24,
    )
}

fun newTerminalId(): String = "term-" + requestId().replace("-", "").take(12)
