package com.dshremote.app.jobs

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

data class JobRow(
    val id: String,
    val label: String,
    val kind: String,
    val status: String,
    val progress: String?,
    val outputBytes: Long,
) {
    val running: Boolean get() = status == "running" || status == "stopping"
}

interface JobListListener {
    fun onRows(rows: List<JobRow>)
    fun onClosed(message: String)
}

suspend fun DshClient.openJobList(
    target: PairingTarget,
    sessionId: String,
    listener: JobListListener,
): SocketHandle {
    val cookie = cookieFor(target) ?: error("配对已失效，请重新配对")
    val streamId = requestId()
    val open = Json.obj(
        "type" to Json.string("open"),
        "streamId" to Json.string(streamId),
        "endpoint" to Json.string("job/list"),
        "payload" to Json.obj(
            "args" to Json.obj("request" to Json.obj("sessionId" to Json.string(sessionId))),
        ),
    )
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
                if (value.get("type")?.strOrNull() != "rows") return
                val rows = value.get("jobs")?.arrOrNull()?.values.orEmpty().mapNotNull { element ->
                    val job = element.objOrNull() ?: return@mapNotNull null
                    val id = job.get("id")?.strOrNull() ?: return@mapNotNull null
                    JobRow(
                        id = id,
                        label = job.get("label")?.strOrNull() ?: id,
                        kind = job.get("kind")?.strOrNull().orEmpty(),
                        status = job.get("status")?.strOrNull().orEmpty(),
                        progress = job.get("progress")?.strOrNull()?.takeUnless { it.isBlank() },
                        outputBytes = job.get("output")?.objOrNull()?.get("total")?.longOrNull() ?: 0L,
                    )
                }
                listener.onRows(rows)
            }

            override fun onClosed(message: String) = listener.onClosed(message)
        },
    )
}

suspend fun DshClient.killJob(target: PairingTarget, sessionId: String, jobId: String) {
    rpcValue(
        target,
        "job/kill",
        Json.obj(
            "request" to Json.obj(
                "sessionId" to Json.string(sessionId),
                "jobId" to Json.string(jobId),
            ),
        ),
    )
}

interface JobOutputListener {
    fun onOutput(text: String)
    fun onClosed(message: String)
}

suspend fun DshClient.openJobOutput(
    target: PairingTarget,
    sessionId: String,
    jobId: String,
    listener: JobOutputListener,
): SocketHandle {
    val cookie = cookieFor(target) ?: error("配对已失效，请重新配对")
    val streamId = requestId()
    val open = Json.obj(
        "type" to Json.string("open"),
        "streamId" to Json.string(streamId),
        "endpoint" to Json.string("job/follow"),
        "payload" to Json.obj(
            "args" to Json.obj(
                "request" to Json.obj(
                    "sessionId" to Json.string(sessionId),
                    "jobId" to Json.string(jobId),
                ),
            ),
        ),
    )
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
                    "output" -> {
                        val chunks = value.get("chunks")?.arrOrNull()?.values.orEmpty()
                        val text = chunks.mapNotNull { chunk ->
                            chunk.objOrNull()?.get("text")?.strOrNull()
                        }.joinToString("")
                        if (text.isNotEmpty()) listener.onOutput(text)
                    }
                    "status" -> listener.onClosed("任务已结束")
                }
            }

            override fun onClosed(message: String) = listener.onClosed(message)
        },
    )
}
