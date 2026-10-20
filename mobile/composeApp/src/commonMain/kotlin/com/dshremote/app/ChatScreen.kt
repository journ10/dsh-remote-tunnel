package com.dshremote.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshremote.app.attachments.PickedFile
import com.dshremote.app.attachments.PickedImage
import com.dshremote.app.attachments.rememberFilePicker
import com.dshremote.app.attachments.rememberImagePicker
import com.dshremote.app.chat.ChatItem
import com.dshremote.app.chat.SessionStreamListener
import com.dshremote.app.chat.followSession
import com.dshremote.app.chat.promptSubagent
import com.dshremote.app.chat.sendPrompt
import com.dshremote.app.chat.uploadFile
import com.dshremote.app.composer.CommandItem
import com.dshremote.app.composer.GoalState
import com.dshremote.app.composer.ModelCatalog
import com.dshremote.app.composer.ModelSelection
import com.dshremote.app.composer.PermissionCatalog
import com.dshremote.app.composer.ReferenceSession
import com.dshremote.app.composer.WorkspaceEntry
import com.dshremote.app.composer.currentGoal
import com.dshremote.app.composer.currentModel
import com.dshremote.app.composer.currentPermission
import com.dshremote.app.composer.currentPlanActive
import com.dshremote.app.composer.executeCommand
import com.dshremote.app.composer.listCommands
import com.dshremote.app.composer.listSkills
import com.dshremote.app.composer.listWorkspace
import com.dshremote.app.composer.modelCatalog
import com.dshremote.app.composer.permissionCatalog
import com.dshremote.app.composer.selectModel
import com.dshremote.app.composer.selectPermission
import com.dshremote.app.composer.sessionMention
import com.dshremote.app.composer.subagentModes
import com.dshremote.app.jobs.JobListListener
import com.dshremote.app.jobs.JobRow
import com.dshremote.app.jobs.killJob
import com.dshremote.app.jobs.openJobList
import com.dshremote.app.platform.PlatformBackHandler
import com.dshremote.app.platform.openExternalUrl
import com.dshremote.app.sessions.SessionRow
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget
import com.dshremote.app.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    client: DshClient,
    target: PairingTarget,
    session: SessionRow,
    onBack: () -> Unit,
    onOpenChild: (SessionRow) -> Unit = {},
    onUnauthorized: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var items by remember(session.id) { mutableStateOf<List<ChatItem>>(emptyList()) }
    var input by remember(session.id) { mutableStateOf("") }
    var loading by remember(session.id) { mutableStateOf(true) }
    var sending by remember(session.id) { mutableStateOf(false) }
    var error by remember(session.id) { mutableStateOf<String?>(null) }
    var streamingText by remember(session.id) { mutableStateOf("") }
    var modelCatalog by remember(session.id) { mutableStateOf(ModelCatalog(ModelSelection("", ""), emptyList())) }
    var permissionCatalog by remember(session.id) { mutableStateOf(PermissionCatalog(emptyList(), "")) }
    var modelSelection by remember(session.id) { mutableStateOf<ModelSelection?>(null) }
    var permission by remember(session.id) { mutableStateOf<String?>(null) }
    var commands by remember(session.id) { mutableStateOf<List<CommandItem>>(emptyList()) }
    var notice by remember(session.id) { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showPermissionPicker by remember { mutableStateOf(false) }
    var jobs by remember(session.id) { mutableStateOf<List<JobRow>>(emptyList()) }
    var showTools by remember { mutableStateOf(false) }
    var review by remember(session.id) { mutableStateOf(false) }
    var streaming by remember(session.id) { mutableStateOf(false) }
    var showJobs by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var showTerminal by remember { mutableStateOf(false) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    var previewName by remember { mutableStateOf("") }
    var previewDiff by remember { mutableStateOf<ChatItem.ToolDiff?>(null) }
    var browserUrl by remember { mutableStateOf<String?>(null) }
    var selectedJob by remember { mutableStateOf<JobRow?>(null) }
    var deliveryMode by remember(session.id) { mutableStateOf("queue") }
    var goal by remember(session.id) { mutableStateOf<GoalState?>(null) }
    var planActive by remember(session.id) { mutableStateOf(false) }
    var childSessions by remember(session.id) { mutableStateOf<List<SessionRow>>(emptyList()) }
    var showChildren by remember { mutableStateOf(false) }
    var attachment by remember(session.id) { mutableStateOf<PickedImage?>(null) }
    var attachedFile by remember(session.id) { mutableStateOf<PickedFile?>(null) }
    val imagePicker = rememberImagePicker { picked ->
        attachment = picked
        attachedFile = null
    }
    val filePicker = rememberFilePicker { picked ->
        attachedFile = picked
        attachment = null
    }
    var files by remember(session.id) { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
    var referenceSessions by remember(session.id) { mutableStateOf<List<ReferenceSession>>(emptyList()) }
    var referenceLoading by remember { mutableStateOf(false) }

    fun merge(next: List<ChatItem>) {
        val current = items.toMutableList()
        for (item in next) {
            val index = current.indexOfFirst { it.id == item.id }
            if (index >= 0) {
                val old = current[index]
                current[index] = if (item is ChatItem.Tool && old is ChatItem.Tool) {
                    item.copy(
                        name = if (item.name == "tool") old.name else item.name,
                        arguments = old.arguments.ifBlank { item.arguments },
                        diffs = item.diffs.ifEmpty { old.diffs },
                    )
                } else {
                    item
                }
            } else {
                current.add(item)
            }
        }
        if (next.any { it is ChatItem.Message && it.role == ChatItem.Message.Role.Assistant }) {
            streamingText = ""
        }
        items = current
        loading = false
    }

    PlatformBackHandler(enabled = true, onBack = onBack)

    fun runCommand(name: String) {
        scope.launch {
            runCatching { client.executeCommand(target, session.id, "/$name") }
                .onSuccess { notice = it ?: "命令已执行" }
                .onFailure { notice = it.message ?: "命令执行失败" }
        }
    }

    suspend fun reloadGoal() {
        val loaded = withContext(Dispatchers.Default) {
            runCatching { client.currentGoal(target, session.id) }.getOrNull() to
                runCatching { client.currentPlanActive(target, session.id) }.getOrDefault(false)
        }
        goal = loaded.first
        planActive = loaded.second
    }

    fun goalAction(action: String) {
        scope.launch {
            runCatching { client.executeCommand(target, session.id, "/goal $action") }
                .onFailure { notice = it.message ?: "Goal 操作失败" }
            reloadGoal()
        }
    }

    LaunchedEffect(session.id, session.parentSessionId) {
        reloadGoal()
        if (session.parentSessionId == null) {
            childSessions = withContext(Dispatchers.Default) {
                val modes = runCatching { client.subagentModes(target, session.id) }.getOrDefault(emptyMap())
                runCatching { client.listSessions(target) }
                    .getOrDefault(emptyList())
                    .filter { it.parentSessionId == session.id }
                    .map { child -> child.copy(subagentMode = modes[child.id] ?: child.subagentMode) }
            }
        }
    }

    LaunchedEffect(session.id) {
        val loaded = withContext(Dispatchers.Default) {
            runCatching {
                val models = client.modelCatalog(target)
                val permissions = client.permissionCatalog(target)
                val current = runCatching { client.currentModel(target, session.id) }.getOrNull()
                val currentPermission = runCatching { client.currentPermission(target, session.id) }.getOrNull()
                val commandList = runCatching { client.listCommands(target, session.id) }.getOrDefault(emptyList())
                val skills = runCatching { client.listSkills(target, session.id) }.getOrDefault(emptyList())
                val sessions = runCatching { client.listSessions(target) }
                    .getOrDefault(emptyList())
                    .filterNot { it.isSubagent || it.id == session.id }
                    .map { ReferenceSession(it.id, it.displayTitle, it.workspaceName) }
                Quad(
                    Triple(models, permissions, current),
                    currentPermission,
                    commandList + skills.filterNot { skill -> commandList.any { it.name == skill.name } },
                    sessions,
                )
            }
        }
        loaded.onSuccess { (first, currentPermission, commandList, sessions) ->
            modelCatalog = first.first
            permissionCatalog = first.second
            modelSelection = first.third
            permission = currentPermission ?: first.second.defaultPreset
            commands = commandList
            referenceSessions = sessions
        }
    }

    LaunchedEffect(session.id) {
        val handle = client.openJobList(target, session.id, object : JobListListener {
            override fun onRows(rows: List<JobRow>) {
                scope.launch { jobs = rows }
            }
            override fun onClosed(message: String) = Unit
        })
        try {
            awaitCancellation()
        } finally {
            handle.close()
        }
    }

    val atMatch = remember(input) { Regex("@([^\\s@]*)$").find(input) }
    val atQuery = atMatch?.groupValues?.get(1)
    val atSlash = atQuery?.lastIndexOf('/') ?: -1
    val atDirectory = when {
        atQuery == null -> ""
        atQuery.endsWith("/") -> atQuery.trimEnd('/')
        atSlash >= 0 -> atQuery.substring(0, atSlash)
        else -> ""
    }
    val atPrefix = when {
        atQuery == null -> ""
        atQuery.endsWith("/") -> ""
        atSlash >= 0 -> atQuery.substring(atSlash + 1)
        else -> atQuery
    }
    val referenceFiles = remember(files, atPrefix) {
        if (atPrefix.isBlank()) files else files.filter { it.name.contains(atPrefix, ignoreCase = true) }
    }
    val referenceMatches = remember(referenceSessions, atQuery, atPrefix) {
        if (atQuery == null || atQuery.contains('/')) emptyList() else referenceSessions
            .filter { atPrefix.isBlank() || it.title.contains(atPrefix, ignoreCase = true) }
            .take(5)
    }
    fun pickFile(entry: WorkspaceEntry) {
        val match = atMatch ?: return
        val before = input.substring(0, match.range.first)
        val directory = atDirectory.trimEnd('/')
        val path = if (directory.isEmpty()) entry.name else "$directory/${entry.name}"
        input = if (entry.isDirectory) {
            "$before@$path/"
        } else {
            "$before@" + (if (path.contains(' ')) "\"$path\"" else path) + " "
        }
    }

    fun pickSession(reference: ReferenceSession) {
        val match = atMatch ?: return
        val before = input.substring(0, match.range.first)
        input = before + sessionMention(reference.id, reference.title) + " "
    }

    LaunchedEffect(session.id, atQuery) {
        val query = atQuery ?: return@LaunchedEffect
        if (input.startsWith("/")) return@LaunchedEffect
        referenceLoading = true
        files = runCatching {
            client.listWorkspace(target, session.id, atDirectory.ifBlank { "." })
        }.getOrDefault(emptyList())
        referenceLoading = false
    }

    LaunchedEffect(session.id) {
        while (true) {
            val closed = CompletableDeferred<String>()
            val handle = runCatching {
                client.followSession(
                    target,
                    session.id,
                    object : SessionStreamListener {
                    override fun onSnapshot(nextItems: List<ChatItem>) {
                        scope.launch {
                            val pending = items.filter {
                                it is ChatItem.Message && it.id.startsWith("local-")
                            }
                            items = nextItems + pending
                            loading = false
                            error = null
                        }
                    }

                    override fun onItems(nextItems: List<ChatItem>) {
                        scope.launch {
                            val durableUsers = nextItems.filterIsInstance<ChatItem.Message>()
                                .filter { it.role == ChatItem.Message.Role.User }
                            if (durableUsers.isNotEmpty()) {
                                items = items.filterNot {
                                    it is ChatItem.Message && it.id.startsWith("local-") &&
                                        durableUsers.any { user -> user.text == it.text }
                                }
                            }
                            merge(nextItems)
                        }
                    }

                    override fun onStreamStart() {
                        scope.launch { streamingText = ""; streaming = true }
                    }

                    override fun onTextDelta(text: String) {
                        scope.launch { streamingText += text }
                    }

                    override fun onStreamEnd() { scope.launch { streaming = false } }

                    override fun onTurnChanges(diffs: List<ChatItem.ToolDiff>) {
                        scope.launch {
                            if (diffs.isEmpty()) return@launch
                            val id = "changes-${items.count { it is ChatItem.Changes }}"
                            if (items.none { it.id == id }) {
                                items = items + ChatItem.Changes(id, diffs)
                            }
                        }
                    }

                    override fun onClosed(message: String) {
                        scope.launch {
                            streamingText = ""
                            streaming = false
                            loading = false
                            if (message.contains("配对已失效")) onUnauthorized()
                            if (message.isNotBlank()) error = message
                        }
                        closed.complete(message)
                    }
                }, parentSessionId = session.parentSessionId, subagentMode = session.subagentMode)
            }.getOrElse {
                loading = false
                error = it.message ?: "会话流连接失败"
                null
            }
            if (handle == null) {
                delay(3_000)
                continue
            }
            try {
                closed.await()
            } finally {
                handle.close()
            }
            delay(2_000)
        }
    }

    val changeItems = remember(items) { items.filter { it is ChatItem.Changes || it is ChatItem.Deliverables } }
    val changedFiles = remember(items) { items.filterIsInstance<ChatItem.Changes>().flatMap { it.diffs }.map { it.path }.distinct().size }
    val followBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index.let {
        it == null || it >= listState.layoutInfo.totalItemsCount - 2
    }
    LaunchedEffect(items.size, streamingText, review) {
        if (!review && followBottom) {
            val count = items.size + if (streamingText.isNotBlank()) 1 else 0
            if (count > 0) listState.scrollToItem(count - 1)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { ChatHeader(session, onBack, onTools = { showTools = true }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            ChatViewTabs(review, changedFiles) { review = it }
            goal?.let { currentGoal ->
                Surface(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = DshSurfaces.code,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                currentGoal.objective,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "目标 · ${currentGoal.phase ?: "active"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            goalAction(if (currentGoal.phase == "paused") "resume" else "pause")
                        }) { Text(if (currentGoal.phase == "paused") "继续" else "暂停", style = MaterialTheme.typography.labelSmall) }
                        TextButton(onClick = { goalAction("clear") }) {
                            Text("清除", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            error?.let { ErrorNotice(it) }
            Box(modifier = Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth()) {
                when {
                    review -> {
                        if (changeItems.isEmpty()) {
                            EmptyState(DshSymbol.Changes, "变更会出现在这里", "任务编辑代码或产出文件后，可在这里逐项查看。", Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                item { Text("审阅本次工作", style = MaterialTheme.typography.titleLarge) }
                                items(changeItems, key = { it.id }) { item ->
                                    when (item) {
                                        is ChatItem.Changes -> ChangesCard(item) { previewDiff = it }
                                        is ChatItem.Deliverables -> DeliverablesCard(item) { previewPath = it.path; previewName = it.path.substringAfterLast('/') }
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }
                    loading && items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 3.dp)
                    items.isEmpty() && streamingText.isBlank() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(DshSymbol.Code, "让想法开始运行", "描述目标，DSH 会在这个工作区中执行。")
                        if (!session.isSubagent || session.continuableSubagent) {
                            TextButton(onClick = { input = "阅读当前项目，梳理架构并指出值得改进的地方" }) { Text("帮我理解这个项目") }
                            TextButton(onClick = { input = "检查当前改动，重点关注潜在问题和测试覆盖" }) { Text("检查当前代码改动") }
                        }
                    }
                    else -> LazyColumn(
                        state = listState, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(items, key = { it.id }) { item ->
                            when (item) {
                                is ChatItem.Message -> MessageBubble(item) { link ->
                                    if (link.startsWith("http://") || link.startsWith("https://")) browserUrl = link else openExternalUrl(link)
                                }
                                is ChatItem.Tool -> ToolCard(item)
                                is ChatItem.Changes -> ChangesCard(item) { previewDiff = it }
                                is ChatItem.Deliverables -> DeliverablesCard(item) { previewPath = it.path; previewName = it.path.substringAfterLast('/') }
                            }
                        }
                        if (streamingText.isNotBlank()) item(key = "streaming") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatusBadge(if (streaming) "正在生成" else "DSH", active = streaming)
                                MarkdownContent(streamingText) { browserUrl = it }
                            }
                        }
                    }
                }
            }
            Surface(modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                Column {
                    attachment?.let { image ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "已选择图片" + (image.name?.let { " · $it" } ?: ""),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { attachment = null }) { Text("移除") }
                        }
                    }
                    attachedFile?.let { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "已选择文件 · ${file.name}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { attachedFile = null }) { Text("移除") }
                        }
                    }
                    if (atQuery != null && !input.startsWith("/") && (referenceLoading || referenceFiles.isNotEmpty() || referenceMatches.isNotEmpty())) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = DshSurfaces.code,
                        ) {
                            Column(modifier = Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState())) {
                                Text(
                                    "引用 · /" + atDirectory.ifBlank { "." },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (referenceLoading) {
                                    Text("读取目录…", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                                }
                                for (entry in referenceFiles) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable { pickFile(entry) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            (if (entry.isDirectory) "▸ " else "") + entry.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                for (reference in referenceMatches) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable { pickSession(reference) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("会话", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.size(6.dp))
                                        Text(
                                            reference.title,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    val commandQuery = input.takeIf { it.startsWith("/") && !it.contains(' ') }?.drop(1)
                    val matches = remember(commands, commandQuery) {
                        if (commandQuery == null) emptyList() else commands
                            .filter { it.name.contains(commandQuery, ignoreCase = true) || it.description.contains(commandQuery, ignoreCase = true) }
                            .take(8)
                    }
                    if (commandQuery != null && matches.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = DshSurfaces.code,
                        ) {
                            Column(modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                                for (command in matches) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            if (!command.isSkill && command.hint == null) {
                                                input = ""
                                                runCommand(command.name)
                                            } else {
                                                input = "/${command.name} "
                                            }
                                        }.padding(horizontal = 10.dp, vertical = 7.dp),
                                    ) {
                                        Text("/${command.name}", style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.size(8.dp))
                                        Text(
                                            command.description,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(if (command.isSkill) "技能" else "命令", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    notice?.let {
                        Text(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ChatComposer(
                        value = input, onValueChange = { input = it },
                        model = modelLabel(modelCatalog, modelSelection),
                        permission = permissionCatalog.options.firstOrNull { it.value == permission }?.name ?: permission ?: "权限",
                        deliveryMode = deliveryMode, planActive = planActive, sending = sending,
                        canSend = input.isNotBlank() || attachment != null || attachedFile != null,
                        readOnly = session.isSubagent && !session.continuableSubagent,
                        onModel = { showModelPicker = true }, onPermission = { showPermissionPicker = true },
                        onDelivery = { deliveryMode = if (deliveryMode == "queue") "steer" else "queue" },
                        onExitPlan = { runCommand("plan off") }, onImage = { imagePicker.pickImage() }, onFile = { filePicker.pickFile() },
                        onSend = send@ {
                            val text = input.trim()
                            val image = attachment
                            val file = attachedFile
                            if ((text.isEmpty() && image == null && file == null) || sending || (session.isSubagent && !session.continuableSubagent)) return@send
                            val commandName = text.removePrefix("/").substringBefore(' ')
                            val command = commands.firstOrNull { !it.isSkill && it.name == commandName }
                            if (image == null && file == null && text.startsWith("/") && command != null) {
                                input = ""
                                runCommand(text.removePrefix("/"))
                                return@send
                            }
                            sending = true
                            input = ""
                            attachment = null
                            attachedFile = null
                            val marker = when {
                                image != null && file != null -> "[图片] [文件]"
                                image != null -> "[图片]"
                                file != null -> "[文件]"
                                else -> ""
                            }
                            val display = listOf(text, marker).filter { it.isNotBlank() }.joinToString("\n")
                            val local = ChatItem.Message(
                                "local-${display.hashCode()}-${items.size}",
                                ChatItem.Message.Role.User,
                                display,
                            )
                            items = items + local
                            scope.launch {
                                val receipt = file?.let {
                                    runCatching { client.uploadFile(target, session.id, it.base64, it.name) }
                                }
                                if (receipt?.isFailure == true) {
                                    items = items.filterNot { item -> item.id == local.id }
                                    error = receipt.exceptionOrNull()?.message ?: "上传文件失败"
                                    input = text
                                    attachedFile = file
                                    sending = false
                                    return@launch
                                }
                                runCatching {
                                    if (session.parentSessionId != null) {
                                        client.promptSubagent(
                                            target,
                                            session.parentSessionId,
                                            session.id,
                                            text,
                                            deliveryMode,
                                            image,
                                        )
                                    } else {
                                        client.sendPrompt(
                                            target,
                                            session.id,
                                            text,
                                            deliveryMode,
                                            image,
                                            receipt?.getOrNull(),
                                        )
                                    }
                                }.onFailure {
                                    items = items.filterNot { item -> item.id == local.id }
                                    error = it.message ?: "发送失败"
                                    input = text
                                    attachment = image
                                    attachedFile = file
                                }
                                sending = false
                            }
                        },
                    )
                }
            }
        }
    }

    if (showTools) {
        ModalBottomSheet(onDismissRequest = { showTools = false }, containerColor = DshSurfaces.sidebar) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("工作区工具", style = MaterialTheme.typography.titleLarge)
                Text(session.workspaceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToolAction(DshSymbol.Files, "文件", "浏览工作区文件与交付产物") { showTools = false; showFiles = true }
                ToolAction(DshSymbol.Terminal, "终端", "查看输出，运行工作区命令") { showTools = false; showTerminal = true }
                ToolAction(DshSymbol.Clock, "后台任务", "${jobs.count { it.running }} 个运行中 · ${jobs.size} 个任务") { showTools = false; showJobs = true }
                ToolAction(DshSymbol.Globe, "浏览器", "打开网页或本地服务") { showTools = false; browserUrl = "" }
                if (childSessions.isNotEmpty()) ToolAction(DshSymbol.Branch, "子会话", "查看 ${childSessions.size} 个协作会话") { showTools = false; showChildren = true }
            }
        }
    }

    if (showChildren) {
        AlertDialog(
            onDismissRequest = { showChildren = false },
            title = { Text("子会话") },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    for (child in childSessions) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                showChildren = false
                                onOpenChild(child)
                            }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(child.displayTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (child.continuableSubagent) "可继续" else "只读",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChildren = false }) { Text("关闭") } },
        )
    }
    if (showTerminal) {
        TerminalDialog(
            client = client,
            target = target,
            sessionId = session.id,
            onDismiss = { showTerminal = false },
        )
    }
    if (showFiles) {
        FilesDialog(
            client = client,
            target = target,
            sessionId = session.id,
            onDismiss = { showFiles = false },
        )
    }
    if (showJobs) {
        AlertDialog(
            onDismissRequest = { showJobs = false },
            title = { Text("后台任务") },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (jobs.isEmpty()) {
                        Text("当前会话没有后台任务", style = MaterialTheme.typography.bodySmall)
                    }
                    for (job in jobs) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { selectedJob = job }.padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(job.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(job.kind, job.status, job.progress).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (job.running) {
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching { client.killJob(target, session.id, job.id) }
                                            .onFailure { notice = it.message ?: "停止任务失败" }
                                    }
                                }) { Text("停止") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showJobs = false }) { Text("关闭") } },
        )
    }
    browserUrl?.let { url ->
        BrowserDialog(url = url, onDismiss = { browserUrl = null })
    }
    previewDiff?.let { diff ->
        DiffDialog(diff = diff, onDismiss = { previewDiff = null })
    }
    previewPath?.let { path ->
        FilePreviewDialog(
            client = client,
            target = target,
            sessionId = session.id,
            path = path,
            name = previewName,
            onDismiss = { previewPath = null },
        )
    }
    selectedJob?.let { job ->
        JobOutputDialog(
            client = client,
            target = target,
            sessionId = session.id,
            job = job,
            onDismiss = { selectedJob = null },
        )
    }
    if (showModelPicker) {
        ModelPickerDialog(
            catalog = modelCatalog,
            current = modelSelection,
            onDismiss = { showModelPicker = false },
            onSelect = { option ->
                val selection = ModelSelection(option.provider, option.id, option.defaultEffort)
                modelSelection = selection
                showModelPicker = false
                scope.launch {
                    runCatching { client.selectModel(target, session.id, selection) }
                        .onFailure { notice = it.message ?: "切换模型失败" }
                }
            },
        )
    }
    if (showPermissionPicker) {
        PermissionPickerDialog(
            catalog = permissionCatalog,
            current = permission,
            onDismiss = { showPermissionPicker = false },
            onSelect = { value ->
                permission = value
                showPermissionPicker = false
                scope.launch {
                    runCatching { client.selectPermission(target, session.id, value) }
                        .onFailure { notice = it.message ?: "切换权限失败" }
                }
            },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    catalog: ModelCatalog,
    current: ModelSelection?,
    onDismiss: () -> Unit,
    onSelect: (com.dshremote.app.composer.ModelOption) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for (group in catalog.groups) {
                    Text(
                        group.name,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (option in group.models) {
                        val selected = current?.provider == option.provider && current.model == option.id
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(option) }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(option.name, style = MaterialTheme.typography.bodySmall)
                                option.description?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                            }
                            if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun PermissionPickerDialog(
    catalog: PermissionCatalog,
    current: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限模式") },
        text = {
            Column {
                for (option in catalog.options) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(option.value) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(option.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (option.value == current) Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun modelLabel(catalog: ModelCatalog, selection: ModelSelection?): String {
    val chosen = selection?.let { selected ->
        catalog.options.firstOrNull { it.provider == selected.provider && it.id == selected.model }
    }
    return chosen?.name ?: selection?.model ?: "模型"
}


private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
