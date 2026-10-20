package com.dshremote.app.chat

import com.dshremote.app.json.JValue
import com.dshremote.app.json.Json
import com.dshremote.app.json.arrOrNull
import com.dshremote.app.json.get
import com.dshremote.app.json.longOrNull
import com.dshremote.app.json.objOrNull
import com.dshremote.app.json.strOrNull
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget
import com.dshremote.app.transport.SocketHandle
import com.dshremote.app.transport.SocketListener
import com.dshremote.app.transport.requestId

interface SessionStreamListener {
    fun onSnapshot(nextItems: List<ChatItem>)
    fun onItems(nextItems: List<ChatItem>)
    fun onStreamStart() = Unit
    fun onTextDelta(text: String) = Unit
    fun onStreamEnd() = Unit
    fun onTurnChanges(diffs: List<ChatItem.ToolDiff>) = Unit
    fun onClosed(message: String)
}

suspend fun DshClient.followSession(
    target: PairingTarget,
    sessionId: String,
    listener: SessionStreamListener,
    parentSessionId: String? = null,
    subagentMode: String? = null,
): SocketHandle {
    val cookie = cookieFor(target) ?: error("配对已失效，请重新配对")
    val streamId = requestId()
    val open = Json.obj(
        "type" to Json.string("open"),
        "streamId" to Json.string(streamId),
        "endpoint" to Json.string("session/follow"),
        "payload" to Json.obj(
            "args" to Json.obj(
                "request" to Json.obj(
                    "address" to if (parentSessionId == null) {
                        Json.obj("kind" to Json.string("session"), "sessionId" to Json.string(sessionId))
                    } else {
                        Json.obj(
                            "kind" to Json.string("subagent"),
                            "parentSessionId" to Json.string(parentSessionId),
                            "childSessionId" to Json.string(sessionId),
                            "mode" to Json.string(subagentMode ?: "unknown"),
                        )
                    },
                    "assistantStream" to "true",
                    "maxMessages" to "200",
                    "turnWindow" to Json.obj("minMessages" to "50", "minTurns" to "2"),
                ),
            ),
        ),
    )
    val turnDiffs = linkedMapOf<String, ChatItem.ToolDiff>()
    return transport.openSocket(
        "${target.origin}/api/remote.mux",
        mapOf(
            "Cookie" to "__dsh_device=$cookie",
            "Origin" to target.origin,
        ),
        open,
        object : SocketListener {
            override fun onOpen() = Unit

            override fun onMessage(text: String) {
                val root = runCatching { Json.parse(text) }.getOrNull()?.objOrNull() ?: return
                when (root.get("type")?.strOrNull()) {
                    "item" -> {
                        val value = root.get("value")?.objOrNull() ?: return
                        when (value.get("type")?.strOrNull()) {
                            "snapshot" -> {
                                val records = value.get("records")?.arrOrNull()?.values.orEmpty()
                                listener.onSnapshot(chatItems(records))
                            }
                            "event" -> {
                                val event = value.get("event")?.objOrNull() ?: return
                                val items = chatItems(event)
                                if (items.isNotEmpty()) listener.onItems(items)
                                for (diff in toolDiffs(event)) turnDiffs[diff.path] = diff
                                if (event.get("type")?.strOrNull() == "turn/end") {
                                    if (turnDiffs.isNotEmpty()) {
                                        listener.onTurnChanges(turnDiffs.values.toList())
                                        turnDiffs.clear()
                                    }
                                }
                            }
                            "assistant-stream" -> {
                                val frame = value.get("frame")?.objOrNull() ?: return
                                when (frame.get("type")?.strOrNull()) {
                                    "start" -> listener.onStreamStart()
                                    "chunk" -> {
                                        val chunk = frame.get("chunk")?.objOrNull() ?: return
                                        if (chunk.get("type")?.strOrNull() == "text-delta") {
                                            chunk.get("text")?.strOrNull()?.let(listener::onTextDelta)
                                        }
                                    }
                                    "end" -> listener.onStreamEnd()
                                }
                            }
                        }
                    }
                    "error" -> {
                        val message = root.get("error")?.objOrNull()?.get("message")?.strOrNull()
                        listener.onClosed(message ?: "会话流错误")
                    }
                    "end" -> listener.onClosed("会话流已结束")
                }
            }

            override fun onClosed(message: String) {
                listener.onClosed(message)
            }
        },
    )
}
