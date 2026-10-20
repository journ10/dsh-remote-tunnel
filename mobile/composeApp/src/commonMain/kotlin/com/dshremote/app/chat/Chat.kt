package com.dshremote.app.chat

import com.dshremote.app.json.JValue
import com.dshremote.app.json.Json
import com.dshremote.app.json.arrOrNull
import com.dshremote.app.json.boolOrNull
import com.dshremote.app.json.get
import com.dshremote.app.json.longOrNull
import com.dshremote.app.json.objOrNull
import com.dshremote.app.json.strOrNull
import com.dshremote.app.attachments.PickedImage
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget
import com.dshremote.app.transport.requestId

sealed interface ChatItem {
    val id: String

    data class Message(
        override val id: String,
        val role: Role,
        val text: String,
    ) : ChatItem {
        enum class Role { User, Assistant }
    }

    data class ToolDiff(val path: String, val oldText: String?, val newText: String)

    data class Tool(
        override val id: String,
        val callId: String,
        val name: String,
        val arguments: String,
        val result: String?,
        val isError: Boolean,
        val diffs: List<ToolDiff> = emptyList(),
    ) : ChatItem

    data class DeliverableFile(val path: String, val description: String?)

    data class Deliverables(
        override val id: String,
        val files: List<DeliverableFile>,
    ) : ChatItem

    data class Changes(
        override val id: String,
        val diffs: List<ToolDiff>,
    ) : ChatItem
}

data class ChatPage(
    val items: List<ChatItem>,
    val hasMore: Boolean,
)

suspend fun DshClient.loadChat(target: PairingTarget, sessionId: String, maxMessages: Int = 120): ChatPage {
    val projections = rpcValue(
        target,
        "session/projections",
        """{"request":{"sessionId":${Json.string(sessionId)}}}""",
    )
    val asOf = projections.get("result")?.objOrNull()?.get("value")?.objOrNull()?.get("asOfSeq")?.longOrNull()
        ?: 0L
    val value = rpcValue(
        target,
        "session/page",
        """{"request":{"address":{"kind":"session","sessionId":${Json.string(sessionId)}},"throughSeq":$asOf,"maxMessages":$maxMessages}}""",
    )
    val page = value.get("result")?.objOrNull()?.get("value")?.objOrNull() ?: return ChatPage(emptyList(), false)
    val records = page.get("records")?.arrOrNull()?.values ?: return ChatPage(emptyList(), false)
    return ChatPage(chatItems(records), page.get("hasMore")?.boolOrNull() ?: false)
}

suspend fun DshClient.sendPrompt(
    target: PairingTarget,
    sessionId: String,
    text: String,
    mode: String = "queue",
    image: PickedImage? = null,
    fileReceipt: String? = null,
) {
    val parts = buildList {
        if (text.isNotBlank()) add("""{"type":"text","text":${Json.string(text)}}""")
        image?.let {
            val name = it.name?.let { value -> ""","name":${Json.string(value)}""" }.orEmpty()
            add("""{"type":"image","mediaType":${Json.string(it.mediaType)},"data":${Json.string(it.base64)}$name}""")
        }
        fileReceipt?.let {
            add("""{"type":"file","receiptId":${Json.string(it)}}""")
        }
    }
    rpcValue(
        target,
        "session/prompt",
        """{"request":{"requestId":${Json.string(requestId())},"sessionId":${Json.string(sessionId)},"mode":${Json.string(mode)},"content":[${parts.joinToString(",")}],"clientTimeZone":"Asia/Shanghai"}}""",
    )
}

internal fun chatItems(records: List<JValue>): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    val indexes = mutableMapOf<String, Int>()
    fun upsert(item: ChatItem) {
        val index = indexes[item.id]
        if (index == null) {
            indexes[item.id] = items.size
            items.add(item)
        } else {
            val old = items[index]
            items[index] = if (item is ChatItem.Tool && old is ChatItem.Tool) {
                item.copy(
                    name = if (item.name == "tool") old.name else item.name,
                    arguments = old.arguments.ifBlank { item.arguments },
                    diffs = item.diffs.ifEmpty { old.diffs },
                )
            } else {
                item
            }
        }
    }
    val turnDiffs = linkedMapOf<String, ChatItem.ToolDiff>()
    for (record in records) {
        val event = record.objOrNull()?.get("event")?.objOrNull() ?: continue
        val seq = event.get("seq")?.longOrNull() ?: 0L
        when (event.get("type")?.strOrNull()) {
            "turn/start" -> turnDiffs.clear()
            "turn/end" -> {
                if (turnDiffs.isNotEmpty()) {
                    upsert(ChatItem.Changes("changes-hist-$seq", turnDiffs.values.toList()))
                    turnDiffs.clear()
                }
            }
            else -> {
                for (item in chatItems(event)) upsert(item)
                for (diff in toolDiffs(event)) turnDiffs[diff.path] = diff
            }
        }
    }
    return items
}

internal fun chatItems(event: JValue): List<ChatItem> {
    val obj = event.objOrNull() ?: return emptyList()
    val seq = obj.get("seq")?.longOrNull() ?: 0L
    return when (obj.get("type")?.strOrNull()) {
        "user/message" -> {
            val data = obj.get("data")?.objOrNull() ?: return emptyList()
            if (data.get("source")?.objOrNull()?.get("kind")?.strOrNull() != "user") return emptyList()
            val content = data.get("content")?.arrOrNull()?.values.orEmpty()
            val text = textOf(content)
            val imageCount = content.count { it.objOrNull()?.get("type")?.strOrNull() == "image" }
            val fileCount = content.count { it.objOrNull()?.get("type")?.strOrNull() == "file" }
            val marker = listOfNotNull(
                when {
                    imageCount == 0 -> null
                    imageCount == 1 -> "[图片]"
                    else -> "[${imageCount} 张图片]"
                },
                when {
                    fileCount == 0 -> null
                    fileCount == 1 -> "[文件]"
                    else -> "[${fileCount} 个文件]"
                },
            ).joinToString(" ")
            val display = listOf(text, marker).filter { it.isNotBlank() }.joinToString("\n")
            if (display.isBlank()) emptyList() else listOf(
                ChatItem.Message("u-$seq", ChatItem.Message.Role.User, display),
            )
        }
        "assistant/message" -> {
            val data = obj.get("data")?.objOrNull() ?: return emptyList()
            val message = data.get("message")?.objOrNull() ?: return emptyList()
            val content = message.get("content")?.arrOrNull()?.values.orEmpty()
            val result = mutableListOf<ChatItem>()
            val text = textOf(content)
            if (text.isNotBlank()) {
                result.add(ChatItem.Message("a-$seq", ChatItem.Message.Role.Assistant, text))
            }
            for (block in content) {
                val blockObj = block.objOrNull() ?: continue
                if (blockObj.get("type")?.strOrNull() != "tool-call") continue
                val callId = blockObj.get("id")?.strOrNull() ?: continue
                result.add(
                    ChatItem.Tool(
                        id = "tool-$callId",
                        callId = callId,
                        name = blockObj.get("name")?.strOrNull() ?: "tool",
                        arguments = blockObj.get("arguments")?.strOrNull().orEmpty(),
                        result = null,
                        isError = false,
                    ),
                )
            }
            result
        }
        "deliverables/presented" -> {
            val data = obj.get("data")?.objOrNull() ?: return emptyList()
            val callId = data.get("callId")?.strOrNull().orEmpty()
            val files = data.get("files")?.arrOrNull()?.values.orEmpty().mapNotNull { element ->
                val item = element.objOrNull() ?: return@mapNotNull null
                val path = item.get("path")?.strOrNull()?.takeUnless { it.isBlank() } ?: return@mapNotNull null
                ChatItem.DeliverableFile(path, item.get("description")?.strOrNull()?.takeUnless { it.isBlank() })
            }
            if (files.isEmpty()) emptyList() else listOf(
                ChatItem.Deliverables("deliverables-${callId.ifBlank { seq.toString() }}", files),
            )
        }
        "tool/result" -> {
            val data = obj.get("data")?.objOrNull() ?: return emptyList()
            val message = data.get("message")?.objOrNull() ?: return emptyList()
            val callId = message.get("toolCallId")?.strOrNull() ?: return emptyList()
            val text = textOf(message.get("content")?.arrOrNull()?.values.orEmpty())
            listOf(
                ChatItem.Tool(
                    id = "tool-$callId",
                    callId = callId,
                    name = "tool",
                    arguments = "",
                    result = text,
                    isError = message.get("isError")?.boolOrNull() ?: false,
                    diffs = parseDiffs(data.get("meta")),
                ),
            )
        }
        else -> emptyList()
    }
}

private fun textOf(content: List<JValue>): String = content.mapNotNull { part ->
    val obj = part.objOrNull() ?: return@mapNotNull null
    if (obj.get("type")?.strOrNull() == "text") obj.get("text")?.strOrNull() else null
}.joinToString("\n").trim()


suspend fun DshClient.uploadFile(
    target: PairingTarget,
    sessionId: String,
    base64: String,
    name: String,
): String {
    val root = rpcValue(
        target,
        "fileUploads/upload",
        Json.obj(
            "agentId" to Json.string(sessionId),
            "request" to Json.obj(
                "data" to Json.string(base64),
                "name" to Json.string(name),
            ),
        ),
    )
    return root.get("result")?.objOrNull()?.get("value")?.objOrNull()
        ?.get("receiptId")?.strOrNull()
        ?: error("上传没有返回 receiptId")
}


internal fun toolDiffs(event: JValue): List<ChatItem.ToolDiff> {
    val data = event.objOrNull()?.get("data")?.objOrNull() ?: return emptyList()
    return parseDiffs(data.get("meta"))
}

private fun parseDiffs(meta: com.dshremote.app.json.JValue?): List<ChatItem.ToolDiff> {
    val array = meta?.objOrNull()?.get("diffs")?.arrOrNull()?.values ?: return emptyList()
    return array.mapNotNull { element ->
        val item = element.objOrNull() ?: return@mapNotNull null
        val path = item.get("path")?.strOrNull()?.takeUnless { it.isBlank() } ?: return@mapNotNull null
        ChatItem.ToolDiff(
            path = path,
            oldText = if ((item.get("oldText") as? com.dshremote.app.json.JValue.Null) != null) null
            else item.get("oldText")?.strOrNull(),
            newText = item.get("newText")?.strOrNull().orEmpty(),
        )
    }
}


suspend fun DshClient.promptSubagent(
    target: PairingTarget,
    parentSessionId: String,
    childSessionId: String,
    text: String,
    delivery: String = "queue",
    image: PickedImage? = null,
) {
    val parts = buildList {
        if (text.isNotBlank()) add("""{"type":"text","text":${Json.string(text)}}""")
        image?.let {
            val name = it.name?.let { value -> ""","name":${Json.string(value)}""" }.orEmpty()
            add("""{"type":"image","mediaType":${Json.string(it.mediaType)},"data":${Json.string(it.base64)}$name}""")
        }
    }
    val request = Json.obj(
        "requestId" to Json.string(requestId()),
        "parentSessionId" to Json.string(parentSessionId),
        "childSessionId" to Json.string(childSessionId),
        "mode" to Json.string("continuable"),
        "delivery" to Json.string(delivery),
        "content" to "[${parts.joinToString(",")}]",
        "clientTimeZone" to Json.string("Asia/Shanghai"),
    )
    rpcValue(target, "subagents/prompt", """{"request":$request}""")
}
