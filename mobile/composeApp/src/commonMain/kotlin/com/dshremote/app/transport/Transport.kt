package com.dshremote.app.transport

import com.dshremote.app.json.JValue
import com.dshremote.app.json.Json
import com.dshremote.app.json.arrOrNull
import com.dshremote.app.json.get
import com.dshremote.app.json.objOrNull
import com.dshremote.app.sessions.SessionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class PairingTarget(val origin: String, val host: String)

data class HttpResult(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>>,
)

data class BinaryHttpResult(
    val status: Int,
    val bytes: ByteArray,
    val headers: Map<String, List<String>>,
)

interface SocketListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosed(message: String)
}

interface SocketHandle {
    fun send(text: String)
    fun close()
}

interface RemoteTransport {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
    ): HttpResult
    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpResult
    suspend fun postBytes(url: String, body: String, headers: Map<String, String> = emptyMap()): BinaryHttpResult
    suspend fun openSocket(
        url: String,
        headers: Map<String, String>,
        initialFrame: String,
        listener: SocketListener,
    ): SocketHandle
}

/** Keys are full origins. Bare-host keys are read only by the legacy migration. */
interface CookieStore {
    suspend fun load(host: String): String?
    suspend fun save(host: String, cookie: String)
    suspend fun clear(host: String)
}

interface ConnectionStore {
    suspend fun loadAll(): List<PairingTarget>
    suspend fun load(): PairingTarget?
    /** Add or re-pair one instance and make it active, keeping other instances. */
    suspend fun save(target: PairingTarget)
    suspend fun setActive(origin: String): PairingTarget?
    suspend fun remove(origin: String): PairingTarget?
    suspend fun clear()
}

interface PlatformServices {
    val transport: RemoteTransport
    val cookies: CookieStore
    val connections: ConnectionStore
}

expect fun platformServices(): PlatformServices

fun pairingTarget(url: String): PairingTarget {
    val schemeEnd = url.indexOf("://")
    require(schemeEnd > 0) { "配对链接必须以 http:// 或 https:// 开头" }
    val scheme = url.substring(0, schemeEnd)
    require(scheme == "http" || scheme == "https") { "只支持 http/https 配对链接" }
    val rest = url.substring(schemeEnd + 3)
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
    require(authority.isNotBlank()) { "配对链接缺少 Host" }
    val origin = "$scheme://$authority"
    return PairingTarget(origin = origin.trimEnd('/'), host = authority)
}

class DshClient(
    internal val transport: RemoteTransport,
    private val cookies: CookieStore,
) {
    suspend fun cookieFor(target: PairingTarget): String? = cookies.load(target.origin)

    suspend fun pair(pairUrl: String): PairingTarget {
        val target = pairingTarget(pairUrl)
        val result = transport.get(pairUrl, followRedirects = false)
        if (result.status == 401 || result.status == 403) error("配对二维码已失效，请重新生成")
        if (result.status == 502 || result.status == 503 || result.status == 504) {
            error("配对失败：relay 暂时不可用（HTTP ${result.status}），请稍后重试")
        }
        if (result.status !in 200..399) error("配对失败：HTTP ${result.status}")
        val cookie = result.headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
            .firstNotNullOfOrNull { header ->
                header.substringBefore(';')
                    .trim()
                    .takeIf { it.startsWith("__dsh_device=") }
                    ?.substringAfter('=')
            }
            ?: error("配对响应没有下发 __dsh_device")
        cookies.save(target.origin, cookie)
        return target
    }

    suspend fun listSessions(target: PairingTarget): List<SessionRow> {
        val value = rpc(target, "session/list", """{"_request":{}}""")
        val items = value["result"]?.objOrNull()?.get("value")?.objOrNull()?.get("items")?.let {
            (it as? JValue.Arr)?.values
        } ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element.objOrNull() ?: return@mapNotNull null
            val id = item["sessionId"]?.let { (it as? JValue.Str)?.value } ?: return@mapNotNull null
            val projections = item["projections"]?.objOrNull()
            val values = projections?.get("values")?.objOrNull()
            val title = values?.get("title")?.stringValue() ?: ""
            val cwd = item["cwd"]?.stringValue()
            val parent = item["parentSessionId"]?.stringValue()
            val origin = item["origin"]?.stringValue()
            val subagentMode = values?.get("subagentCatalog")?.arrOrNull()?.values
                ?.mapNotNull { it.objOrNull() }
                ?.firstOrNull { it["id"]?.stringValue() == id }
                ?.get("mode")?.stringValue()
            SessionRow(
                id = id,
                title = title,
                cwd = cwd,
                running = item["running"]?.let { (it as? JValue.Bool)?.value } ?: false,
                blank = item["blank"]?.let { (it as? JValue.Bool)?.value } ?: false,
                isSubagent = parent != null || origin == "subagent",
                updatedAt = (item["updatedAt"] as? JValue.Num)?.raw?.toLongOrNull(),
                parentSessionId = parent,
                subagentMode = subagentMode,
            )
        }
    }

    suspend fun createSession(target: PairingTarget, cwd: String?): SessionRow {
        val request = if (cwd.isNullOrBlank()) "{}" else Json.obj("cwd" to Json.string(cwd))
        val args = """{"request":${request}}"""
        val value = rpc(target, "session/create", args)
        val id = value["result"]?.objOrNull()?.get("value")?.objOrNull()?.get("sessionId")?.stringValue()
            ?: error("Host 没有返回 sessionId")
        return SessionRow(id, "新会话", cwd, running = false, blank = true)
    }

    suspend fun rename(target: PairingTarget, id: String, title: String) {
        rpc(
            target,
            "session/rename",
            """{"request":{"sessionId":${Json.string(id)},"title":${Json.string(title)}}}""",
        )
    }

    suspend fun fork(target: PairingTarget, id: String): SessionRow {
        val value = rpc(target, "session/fork", """{"request":{"sessionId":${Json.string(id)}}}""")
        val forkedId = value["result"]?.objOrNull()?.get("value")?.objOrNull()?.get("sessionId")?.stringValue()
            ?: error("Host 没有返回 Fork 会话 ID")
        val parent = listSessions(target).firstOrNull { it.id == id }
        return SessionRow(
            id = forkedId,
            title = "Fork · ${parent?.displayTitle ?: id}",
            cwd = parent?.cwd,
            running = false,
            blank = false,
        )
    }

    suspend fun archive(target: PairingTarget, id: String) {
        rpc(
            target,
            "workspace/archiveSession",
            """{"request":{"sessionId":${Json.string(id)},"stopActivity":true}}""",
        )
    }

    suspend fun rpcValue(target: PairingTarget, method: String, argsJson: String): JValue.Obj =
        rpc(target, method, argsJson)

    private suspend fun rpc(target: PairingTarget, method: String, argsJson: String): JValue.Obj {
        val cookie = cookieFor(target) ?: error("配对已失效，请重新配对")
        val envelope = Json.obj(
            "type" to Json.string("client-request"),
            "rpcId" to Json.string(requestToken()),
            "method" to Json.string(method),
            "payload" to Json.obj("args" to argsJson),
        )
        val result = withContext(Dispatchers.Default) {
            transport.post(
                "${target.origin}/api/$method",
                envelope,
                mapOf(
                    "Content-Type" to "application/json; charset=utf-8",
                    "Cookie" to "__dsh_device=$cookie",
                    "Origin" to target.origin,
                ),
            )
        }
        if (result.status == 401 || result.status == 403) {
            cookies.clear(target.origin)
            error("配对已失效，请重新扫码")
        }
        if (result.status == 502 || result.status == 503 || result.status == 504) {
            error("relay 暂时不可用（HTTP ${result.status}），请稍后重试")
        }
        if (result.body.trimStart().startsWith("<")) {
            error("relay 返回了网页错误（HTTP ${result.status}），请重新扫码或稍后重试")
        }
        if (result.status !in 200..299) error("$method 失败：HTTP ${result.status}")
        val root = runCatching { Json.parse(result.body).objOrNull() }
            .getOrNull() ?: error("$method 返回异常（HTTP ${result.status}）")
        val remote = root["result"]?.objOrNull() ?: error("$method 返回格式错误")
        if (remote["ok"]?.let { (it as? JValue.Bool)?.value } != true) {
            val message = remote["error"]?.objOrNull()?.get("message")?.stringValue()
            error(message?.ifBlank { "$method 被 Host 拒绝" } ?: "$method 被 Host 拒绝")
        }
        return root
    }

    private fun JValue.stringValue(): String? =
        (this as? JValue.Str)?.value?.takeUnless { it.isBlank() || it == "null" }
}

class RemoteSessionsRepository(
    private val client: DshClient,
    private val target: PairingTarget,
) : com.dshremote.app.sessions.SessionsRepository {
    override suspend fun list(): List<SessionRow> {
        val rows = client.listSessions(target)
        val state = runCatching { client.workspaceState(target) }.getOrNull()
        val archived = state?.archivedSessionIds.orEmpty()
        val visible = if (archived.isEmpty()) rows else rows.filterNot { it.id in archived }
        if (state == null) return visible
        return visible.map { row ->
            val workspace = state.workspaceFor(row.id)
            row.copy(
                workspaceId = workspace?.id,
                workspaceTitle = workspace?.title,
                groupedByWorkspace = true,
            )
        }
    }

    override suspend fun create(cwd: String?): SessionRow = client.createSession(target, cwd)

    override suspend fun rename(id: String, title: String) = client.rename(target, id, title)

    override suspend fun fork(id: String): SessionRow = client.fork(target, id)

    override suspend fun archive(id: String) {
        client.archive(target, id)
    }
}

fun requestId(): String = requestToken()

private fun requestToken(): String {
    val chars = "0123456789abcdef"
    val bytes = Random.nextBytes(16)
    val hex = bytes.joinToString("") { chars[(it.toInt() and 0xff) shr 4].toString() + chars[it.toInt() and 0x0f] }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
