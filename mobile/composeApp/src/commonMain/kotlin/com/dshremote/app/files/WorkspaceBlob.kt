package com.dshremote.app.files

import com.dshremote.app.json.JValue
import com.dshremote.app.json.Json
import com.dshremote.app.json.get
import com.dshremote.app.json.longOrNull
import com.dshremote.app.json.objOrNull
import com.dshremote.app.json.strOrNull
import com.dshremote.app.transport.BinaryHttpResult
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget
import com.dshremote.app.transport.requestId

data class WorkspaceBlob(
    val bytes: ByteArray,
    val mediaType: String?,
    val offset: Int,
    val eof: Boolean,
    val totalBytes: Long?,
)

suspend fun DshClient.readWorkspaceBytes(
    target: PairingTarget,
    sessionId: String,
    path: String,
): WorkspaceBlob {
    val cookie = cookieFor(target) ?: error("配对已失效，请重新配对")
    val args = Json.obj(
        "workspaceFileScopeId" to Json.string(sessionId),
        "path" to Json.string(path),
        "options" to "{}",
    )
    val envelope = Json.obj(
        "type" to Json.string("client-request"),
        "rpcId" to Json.string(requestId()),
        "method" to Json.string("workspaceFiles/readBytes"),
        "payload" to Json.obj("args" to args),
    )
    val result: BinaryHttpResult = transport.postBytes(
        "${target.origin}/api/workspaceFiles/readBytes",
        envelope,
        mapOf(
            "Cookie" to "__dsh_device=$cookie",
            "Origin" to target.origin,
            "Content-Type" to "application/json; charset=utf-8",
        ),
    )
    if (result.status == 401 || result.status == 403) error("设备配对已失效")
    if (result.status !in 200..299) error("文件读取失败：HTTP ${result.status}")
    val contentType = result.headers.entries
        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
        ?.value?.firstOrNull().orEmpty()
    if (contentType.startsWith("multipart/", ignoreCase = true)) {
        val boundary = contentType.substringAfter("boundary=", "").trim().trim('"')
        if (boundary.isEmpty()) error("文件响应缺少 boundary")
        return parseWorkspaceBlob(result.bytes, boundary)
    }
    val root = runCatching { Json.parse(result.bytes.decodeToString()) }.getOrNull()?.objOrNull()
        ?: error("文件响应格式错误")
    val value = root.get("result")?.objOrNull()?.get("value")?.objOrNull()
        ?: error("文件响应格式错误")
    return WorkspaceBlob(
        bytes = decodeBase64(value.get("data")?.strOrNull().orEmpty()),
        mediaType = value.get("mediaType")?.strOrNull()?.takeUnless { it.isBlank() },
        offset = value.get("offset")?.longOrNull()?.toInt() ?: 0,
        eof = (value.get("eof") as? JValue.Bool)?.value ?: false,
        totalBytes = value.get("bytes")?.longOrNull(),
    )
}

internal fun parseWorkspaceBlob(body: ByteArray, boundary: String): WorkspaceBlob {
    val delimiter = "--$boundary".encodeToByteArray()
    val headerSeparator = "\r\n\r\n".encodeToByteArray()
    var cursor = indexOf(body, delimiter, 0)
    var binary: ByteArray? = null
    var mediaType: String? = null
    var metadata: com.dshremote.app.json.JValue.Obj? = null
    while (cursor >= 0) {
        var start = cursor + delimiter.size
        if (start + 1 < body.size && body[start] == '\r'.code.toByte() && body[start + 1] == '\n'.code.toByte()) {
            start += 2
        }
        val next = indexOf(body, delimiter, start)
        if (next < 0) break
        var end = next
        if (end >= 2 && body[end - 2] == '\r'.code.toByte() && body[end - 1] == '\n'.code.toByte()) end -= 2
        val part = body.copyOfRange(start, end)
        val headerEnd = indexOf(part, headerSeparator, 0)
        if (headerEnd >= 0) {
            val headers = part.copyOfRange(0, headerEnd).decodeToString()
            val data = part.copyOfRange(headerEnd + headerSeparator.size, part.size)
            if (headers.contains("application/json", ignoreCase = true) ||
                headers.contains("name=\"metadata\"", ignoreCase = true)
            ) {
                metadata = runCatching { Json.parse(data.decodeToString()).objOrNull() }.getOrNull()
            } else if (headers.contains("application/octet-stream", ignoreCase = true) || binary == null) {
                binary = data
                mediaType = Regex("(?i)content-type:\\s*([^\\r\\n]+)").find(headers)
                    ?.groupValues?.get(1)?.trim()
            }
        }
        cursor = next
    }
    val value = metadata?.get("result")?.objOrNull()?.get("value")?.objOrNull()
        ?: error("文件响应缺少元数据")
    return WorkspaceBlob(
        bytes = binary ?: ByteArray(0),
        mediaType = mediaType,
        offset = value.get("offset")?.longOrNull()?.toInt() ?: 0,
        eof = (value.get("eof") as? JValue.Bool)?.value ?: false,
        totalBytes = value.get("bytes")?.longOrNull(),
    )
}

private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
    if (needle.isEmpty()) return from
    outer@ for (i in from..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

private fun decodeBase64(value: String): ByteArray {
    if (value.isBlank()) return ByteArray(0)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val out = ArrayList<Byte>(value.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (char in value) {
        if (char == '=') break
        val index = alphabet.indexOf(char)
        if (index < 0) continue
        buffer = (buffer shl 6) or index
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.add(((buffer shr bits) and 0xff).toByte())
        }
    }
    return out.toByteArray()
}
