package com.dshremote.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshremote.app.composer.WorkspaceEntry
import com.dshremote.app.composer.listWorkspace
import com.dshremote.app.files.PlatformFilePreview
import com.dshremote.app.files.WorkspaceBlob
import com.dshremote.app.files.WorkspaceText
import com.dshremote.app.files.readWorkspaceBytes
import com.dshremote.app.files.readWorkspaceFile
import com.dshremote.app.files.renderOfficePdf
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FilesDialog(
    client: DshClient,
    target: PairingTarget,
    sessionId: String,
    onDismiss: () -> Unit,
) {
    var directory by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<WorkspaceEntry?>(null) }

    LaunchedEffect(sessionId, directory) {
        loading = true
        error = null
        entries = withContext(Dispatchers.Default) {
            runCatching { client.listWorkspace(target, sessionId, directory.ifBlank { "." }) }
                .getOrElse {
                    error = it.message ?: "无法读取工作区"
                    emptyList()
                }
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("工作区文件") },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (directory.isNotEmpty()) {
                        TextButton(onClick = {
                            directory = directory.substringBeforeLast('/', "")
                        }) { Text("上一级") }
                    }
                    Text(
                        "/" + directory,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (loading) Text("读取目录…", style = MaterialTheme.typography.labelSmall)
                error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    for (entry in entries) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (entry.isDirectory) directory = entry.path else selected = entry
                            }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (entry.isDirectory) "▸" else "·",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(7.dp))
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )

    selected?.let { file ->
        FilePreviewDialog(
            client = client,
            target = target,
            sessionId = sessionId,
            path = file.path,
            name = file.name,
            onDismiss = { selected = null },
        )
    }
}

@Composable
fun FilePreviewDialog(
    client: DshClient,
    target: PairingTarget,
    sessionId: String,
    path: String,
    name: String,
    onDismiss: () -> Unit,
) {
    val lower = path.lowercase()
    val binary = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
        lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".pdf")
    val office = lower.endsWith(".docx") || lower.endsWith(".xlsx") || lower.endsWith(".pptx") ||
        lower.endsWith(".doc") || lower.endsWith(".xls") || lower.endsWith(".ppt") ||
        lower.endsWith(".odt") || lower.endsWith(".ods") || lower.endsWith(".odp")
    if (!binary && !office) {
        TextFileDialog(client, target, sessionId, path, name, onDismiss)
        return
    }
    var blob by remember(path) { mutableStateOf<WorkspaceBlob?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path, office) {
        runCatching {
            if (office) {
                val pdf = client.renderOfficePdf(target, sessionId, path)
                pdf.copy(mediaType = "application/pdf")
            } else {
                client.readWorkspaceBytes(target, sessionId, path)
            }
        }
            .onSuccess { blob = it }
            .onFailure { error = it.message ?: "无法读取文件" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp)) {
                when {
                    error != null -> Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    blob == null -> Text(
                        if (office) "转换 Office 文档…" else "读取文件…",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    else -> PlatformFilePreview(blob!!.bytes, blob!!.mediaType, path)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun TextFileDialog(
    client: DshClient,
    target: PairingTarget,
    sessionId: String,
    path: String,
    name: String,
    onDismiss: () -> Unit,
) {
    var text by remember(path) { mutableStateOf<WorkspaceText?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        runCatching { client.readWorkspaceFile(target, sessionId, path) }
            .onSuccess { text = it }
            .onFailure { error = it.message ?: "无法读取文件" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                when {
                    error != null -> Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    text == null -> Text("读取文件…", style = MaterialTheme.typography.labelSmall)
                    else -> {
                        SelectionContainer {
                            Text(
                                text!!.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                        if (!text!!.eof) {
                            Text("仅显示前 ${text!!.lines} 行", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
