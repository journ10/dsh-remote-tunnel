package com.dshremote.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshremote.app.sessions.*
import com.dshremote.app.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SessionHomeScreen(
    repository: SessionsRepository,
    connectionLabel: String,
    onOpen: (SessionRow) -> Unit,
    onSettings: () -> Unit,
    onUnauthorized: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var sessions by remember(repository) { mutableStateOf<List<SessionRow>>(emptyList()) }
    var loading by remember(repository) { mutableStateOf(true) }
    var error by remember(repository) { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<SessionRow?>(null) }
    var renameText by remember { mutableStateOf("") }
    var archiveTarget by remember { mutableStateOf<SessionRow?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var selectedCwd by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        runCatching { repository.list() }
            .onSuccess { sessions = it; error = null }
            .onFailure {
                val message = it.message ?: "加载会话失败"
                if (message.contains("配对已失效")) onUnauthorized() else error = message
            }
        loading = false
    }
    LaunchedEffect(repository) {
        while (true) { refresh(); delay(5_000) }
    }
    SessionHomeContent(
        sessions, connectionLabel, loading, error,
        onOpen = onOpen, onSettings = onSettings,
        onCreate = { showCreate = true; createError = null },
        onRetry = { scope.launch { refresh() } },
        onRename = { renameTarget = it; renameText = it.displayTitle },
        onFork = { session ->
            scope.launch {
                runCatching { repository.fork(session.id) }
                    .onSuccess { sessions = listOf(it) + sessions; onOpen(it) }
                    .onFailure { error = it.message ?: "创建分支失败" }
            }
        },
        onArchive = { archiveTarget = it },
    )
    if (showCreate) {
        val directories = sessions.mapNotNull { it.cwd }.distinct()
        AlertDialog(
            onDismissRequest = { if (!creating) showCreate = false },
            title = { Text("新建任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("选择任务的工作目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        item {
                            DirectoryOption("默认目录", "使用电脑端的默认工作目录", selectedCwd == null, !creating) { selectedCwd = null }
                        }
                        items(directories) { cwd ->
                            DirectoryOption(cwd.trimEnd('/').substringAfterLast('/'), cwd, selectedCwd == cwd, !creating) { selectedCwd = cwd }
                        }
                    }
                    createError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(enabled = !creating, onClick = {
                    creating = true
                    createError = null
                    scope.launch {
                        runCatching { repository.create(selectedCwd) }
                            .onSuccess { sessions = listOf(it) + sessions; showCreate = false; onOpen(it) }
                            .onFailure { createError = it.message ?: "创建失败，请重试" }
                        creating = false
                    }
                }) {
                    if (creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("创建任务")
                }
            },
            dismissButton = { TextButton(enabled = !creating, onClick = { showCreate = false }) { Text("取消") } },
        )
    }
    renameTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { renameTarget = null }, title = { Text("重命名会话") },
            text = { OutlinedTextField(renameText, { renameText = it }, label = { Text("会话名称") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = renameText.isNotBlank(), onClick = {
                    val title = renameText.trim()
                    renameTarget = null
                    scope.launch {
                        runCatching { repository.rename(session.id, title) }
                            .onSuccess { refresh() }.onFailure { error = it.message ?: "重命名失败" }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
    archiveTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null }, title = { Text("归档会话") },
            text = { Text(session.displayTitle) },
            confirmButton = {
                TextButton(onClick = {
                    archiveTarget = null
                    scope.launch {
                        runCatching { repository.archive(session.id) }
                            .onSuccess { sessions = sessions.filterNot { it.id == session.id } }
                            .onFailure { error = it.message ?: "归档失败" }
                    }
                }) { Text("归档") }
            },
            dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionHomeContent(
    sessions: List<SessionRow>, connectionLabel: String, loading: Boolean, error: String?,
    onOpen: (SessionRow) -> Unit, onSettings: () -> Unit, onCreate: () -> Unit, onRetry: () -> Unit,
    onRename: (SessionRow) -> Unit, onFork: (SessionRow) -> Unit, onArchive: (SessionRow) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var runningOnly by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val visible = remember(sessions) { visibleSessions(sessions) }
    val groups = remember(visible, query, runningOnly) { filterSessionGroups(visible, query, runningOnly) }
    val runningCount = visible.count { it.running }
    Scaffold(
        containerColor = DshSurfaces.base,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DshIcon(DshSymbol.Code, modifier = Modifier.size(26.dp))
                        Text("DSH Remote", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = { DshIconButton(DshSymbol.Settings, "实例管理", onSettings) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DshSurfaces.base),
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val compact = maxHeight < 480.dp
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize().padding(horizontal = 20.dp)) {
                if (!compact) Surface(onClick = onSettings, shape = RoundedCornerShape(12.dp), color = DshSurfaces.sidebar) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DshIcon(DshSymbol.Monitor, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text("当前实例 · 点击切换", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(connectionLabel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        StatusBadge(if (loading) "同步中" else if (error != null) "连接异常" else "已连接", active = !loading && error == null)
                        DshIcon(DshSymbol.Down, modifier = Modifier.size(16.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("工作台", style = MaterialTheme.typography.headlineMedium)
                        if (!compact) Text("从一个想法，到一次交付", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onCreate, shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                        DshIcon(DshSymbol.Plus)
                        Spacer(Modifier.width(6.dp))
                        Text("新任务")
                    }
                }
                OutlinedTextField(
                    query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("搜索会话或工作区") },
                    leadingIcon = { DshIcon(DshSymbol.Search, "搜索会话或工作区") },
                    trailingIcon = { if (query.isNotEmpty()) DshIconButton(DshSymbol.Close, "清除搜索", { query = "" }) },
                    shape = RoundedCornerShape(14.dp), textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant, focusedBorderColor = DshSurfaces.link),
                )
                Row(Modifier.padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!runningOnly, { runningOnly = false }, { Text("全部 ${visible.size}") }, shape = RoundedCornerShape(50))
                    FilterChip(runningOnly, { runningOnly = true }, { Text("进行中 $runningCount") }, shape = RoundedCornerShape(50), leadingIcon = { DshIcon(DshSymbol.Clock, modifier = Modifier.size(16.dp)) })
                }
                if (error != null) ErrorNotice(error, onRetry)
                when {
                    loading && visible.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                    groups.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(DshSymbol.Chat, if (query.isNotBlank()) "没有匹配的会话" else if (runningOnly) "目前没有运行中的任务" else "开始你的第一个任务", if (query.isNotBlank()) "试试会话名称或工作区名称" else if (runningOnly) "已完成的会话可以在「全部」中查看" else "选择工作目录，描述你想完成的事情")
                    }
                    else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (group in groups) {
                            val isCollapsed = group.key in collapsed && query.isBlank()
                            item(key = "group-${group.key}") {
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .semantics { stateDescription = if (isCollapsed) "已折叠" else "已展开" }
                                        .clickable(enabled = query.isBlank(), role = Role.Button, onClickLabel = if (isCollapsed) "展开工作区" else "折叠工作区") {
                                            collapsed = if (group.key in collapsed) collapsed - group.key else collapsed + group.key
                                        }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    DshIcon(DshSymbol.Folder, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(group.workspace.trimEnd('/').substringAfterLast('/').ifBlank { "默认工作区" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${group.sessions.size}", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    DshIcon(if (isCollapsed) DshSymbol.Right else DshSymbol.Down, modifier = Modifier.size(16.dp))
                                }
                            }
                            if (!isCollapsed) items(group.sessions, key = { it.id }) { session ->
                                SessionRowView(session, { onOpen(session) }, { onRename(session) }, { onFork(session) }, { onArchive(session) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryOption(title: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SessionRowView(session: SessionRow, onClick: () -> Unit, onRename: () -> Unit, onFork: () -> Unit, onArchive: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp), color = if (session.running) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else DshSurfaces.sidebar,
        border = if (session.running) BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)) else null,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(role = Role.Button, onClick = onClick).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                DshIcon(if (session.running) DshSymbol.Clock else DshSymbol.Chat, tint = if (session.running) DshSurfaces.link else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(session.displayTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (session.running) "正在执行" else if (session.blank) "等待你的想法" else "继续会话", style = MaterialTheme.typography.bodySmall, color = if (session.running) DshSurfaces.link else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                DshIconButton(DshSymbol.More, "会话操作：${session.displayTitle}", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("重命名") }, { menu = false; onRename() })
                    DropdownMenuItem({ Text("创建分支") }, { menu = false; onFork() })
                    DropdownMenuItem({ Text("归档") }, { menu = false; onArchive() })
                }
            }
        }
    }
}
