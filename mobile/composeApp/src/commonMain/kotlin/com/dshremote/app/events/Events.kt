package com.dshremote.app.events

import com.dshremote.app.json.JValue
import com.dshremote.app.json.Json
import com.dshremote.app.json.arrOrNull
import com.dshremote.app.json.boolOrNull
import com.dshremote.app.json.get
import com.dshremote.app.json.objOrNull
import com.dshremote.app.json.strOrNull
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget
import com.dshremote.app.transport.SocketHandle
import com.dshremote.app.transport.SocketListener
import com.dshremote.app.transport.requestId

data class ApprovalPrompt(
    val clientId: String,
    val eventId: String,
    val toolName: String,
    val reason: String,
)

data class QuestionOption(val label: String, val description: String?)

data class UserQuestionItem(
    val id: String,
    val header: String?,
    val question: String,
    val detail: String?,
    val options: List<QuestionOption>,
    val multiSelect: Boolean,
)

data class QuestionPrompt(
    val clientId: String,
    val eventId: String,
    val questions: List<UserQuestionItem>,
)

data class QuestionDraft(val selected: Set<String> = emptySet(), val custom: String = "")

interface EventStreamListener {
    fun onApproval(prompt: ApprovalPrompt)
    fun onQuestion(nextQuestion: QuestionPrompt)
    fun onCancelled(eventId: String)
    fun onClosed(message: String)
}

suspend fun DshClient.openEvents(target: PairingTarget, listener: EventStreamListener): SocketHandle {
    val cookie = cookieFor(target) ?: error("配对已失效，请重新配对")
    val streamId = requestId()
    val open = Json.obj(
        "type" to Json.string("open"),
        "streamId" to Json.string(streamId),
        "endpoint" to Json.string("\$events"),
        "payload" to Json.obj("args" to "{}"),
    )
    var clientId: String? = null
    return transport.openSocket(
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
                    "ready" -> clientId = value.get("clientId")?.strOrNull()
                    "waterfall" -> {
                        val cid = clientId ?: return
                        val eventId = value.get("eventId")?.strOrNull() ?: return
                        val request = value.get("request")?.objOrNull()
                        when (value.get("event")?.strOrNull()) {
                            "approval/request" -> listener.onApproval(
                                ApprovalPrompt(
                                    clientId = cid,
                                    eventId = eventId,
                                    toolName = request?.get("toolName")?.strOrNull()
                                        ?.takeUnless { it.isBlank() } ?: "工具操作",
                                    reason = request?.get("reason")?.strOrNull()
                                        ?.takeUnless { it.isBlank() }
                                        ?: request?.get("displayReason")?.strOrNull().orEmpty(),
                                ),
                            )
                            "user-questions/request" -> listener.onQuestion(
                                QuestionPrompt(cid, eventId, parseQuestions(request)),
                            )
                        }
                    }
                    "cancel" -> value.get("eventId")?.strOrNull()?.let(listener::onCancelled)
                }
            }

            override fun onClosed(message: String) = listener.onClosed(message)
        },
    )
}

suspend fun DshClient.answerApproval(target: PairingTarget, prompt: ApprovalPrompt, allowed: Boolean) {
    eventResult(
        target,
        prompt.clientId,
        prompt.eventId,
        """{"kind":"result","value":${Json.string(if (allowed) "allowed-once" else "rejected")}}""",
    )
}

suspend fun DshClient.answerQuestion(
    target: PairingTarget,
    prompt: QuestionPrompt,
    answers: Map<String, QuestionDraft>,
) {
    val parts = mutableListOf<String>()
    for ((id, draft) in answers) {
        val selected = draft.selected.joinToString(",", "[", "]") { Json.string(it) }
        val custom = draft.custom.trim().takeIf { it.isNotEmpty() }
        parts.add(
            Json.obj(
                "id" to Json.string(id),
                "selected" to selected,
                *if (custom != null) arrayOf("custom" to Json.string(custom)) else emptyArray(),
            ),
        )
    }
    val value = """{"answers":[${parts.joinToString(",")}]}"""
    eventResult(target, prompt.clientId, prompt.eventId, """{"kind":"result","value":$value}""")
}

suspend fun DshClient.rejectQuestion(target: PairingTarget, prompt: QuestionPrompt) {
    val error = Json.obj(
        "name" to Json.string("UserQuestionError"),
        "code" to Json.string("ASK_CANCELLED"),
        "message" to Json.string("the user cancelled ask_user_question"),
    )
    eventResult(target, prompt.clientId, prompt.eventId, """{"kind":"rejected","error":$error}""")
}

private suspend fun DshClient.eventResult(target: PairingTarget, clientId: String, eventId: String, outcome: String) {
    rpcValue(
        target,
        "\$events/result",
        Json.obj(
            "clientId" to Json.string(clientId),
            "eventId" to Json.string(eventId),
            "outcome" to outcome,
        ),
    )
}

private fun parseQuestions(request: JValue.Obj?): List<UserQuestionItem> {
    val array = request?.get("questions")?.arrOrNull()?.values ?: return emptyList()
    return array.mapNotNull { element ->
        val item = element.objOrNull() ?: return@mapNotNull null
        val id = item.get("id")?.strOrNull() ?: return@mapNotNull null
        val options = item.get("options")?.arrOrNull()?.values.orEmpty().mapNotNull { option ->
            val obj = option.objOrNull() ?: return@mapNotNull null
            val label = obj.get("label")?.strOrNull() ?: return@mapNotNull null
            QuestionOption(label, obj.get("description")?.strOrNull()?.takeUnless { it.isBlank() })
        }
        UserQuestionItem(
            id = id,
            header = item.get("header")?.strOrNull()?.takeUnless { it.isBlank() },
            question = item.get("question")?.strOrNull()?.ifBlank { "请选择" } ?: "请选择",
            detail = item.get("detail")?.strOrNull()?.takeUnless { it.isBlank() },
            options = options,
            multiSelect = item.get("multiSelect")?.boolOrNull() ?: false,
        )
    }
}
