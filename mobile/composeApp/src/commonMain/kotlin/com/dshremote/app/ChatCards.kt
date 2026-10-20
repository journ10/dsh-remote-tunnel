package com.dshremote.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshremote.app.chat.ChatItem
import com.dshremote.app.diff.diffStats
import com.dshremote.app.diff.lineDiff
import com.dshremote.app.ui.*

@Composable
internal fun MessageBubble(message: ChatItem.Message, onOpenLink: (String) -> Unit) {
    val user = message.role == ChatItem.Message.Role.User
    if (user) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(Modifier.fillMaxWidth(0.9f), shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp), color = DshSurfaces.bubble) {
                Text(message.text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DshIcon(DshSymbol.Code, modifier = Modifier.size(18.dp))
                Text("DSH", style = MaterialTheme.typography.labelLarge)
            }
            MarkdownContent(message.text, onOpenUrl = onOpenLink)
        }
    }
}

@Composable
internal fun ToolCard(tool: ChatItem.Tool) {
    var expanded by remember(tool.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "已展开" else "已折叠" },
        shape = RoundedCornerShape(14.dp), color = DshSurfaces.sidebar,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DshIcon(if (tool.isError) DshSymbol.Close else if (tool.result == null) DshSymbol.Clock else DshSymbol.Check,
                    tint = if (tool.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tool.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (tool.isError) "失败" else if (tool.result == null) "运行中" else "已完成", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DshIcon(if (expanded) DshSymbol.Down else DshSymbol.Right, modifier = Modifier.size(16.dp))
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (tool.arguments.isNotBlank()) Text(tool.arguments, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    tool.result?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = if (tool.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
    }
}

@Composable
internal fun DeliverablesCard(item: ChatItem.Deliverables, onOpen: (ChatItem.DeliverableFile) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = DshSurfaces.sidebar, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Text("交付文件 · ${item.files.size}", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
            for (file in item.files) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(file) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DshIcon(DshSymbol.Files)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(file.description ?: file.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    DshIcon(DshSymbol.Right, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
internal fun ChangesCard(item: ChatItem.Changes, onOpen: (ChatItem.ToolDiff) -> Unit) {
    val stats = remember(item.diffs) { item.diffs.map { diffStats(lineDiff(it.oldText, it.newText)) } }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = DshSurfaces.base, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Surface(color = DshSurfaces.sidebar) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DshIcon(DshSymbol.Changes, tint = DshSurfaces.link)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("已修改 ${item.diffs.size} 个文件", style = MaterialTheme.typography.titleMedium)
                        Text("点击文件审阅改动", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DiffNumbers(stats.sumOf { it.added }, stats.sumOf { it.removed })
                }
            }
            item.diffs.forEachIndexed { index, diff ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onOpen(diff) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(diff.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if ('/' in diff.path) Text(diff.path.substringBeforeLast('/'), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DiffNumbers(stats[index].added, stats[index].removed)
                    DshIcon(DshSymbol.Right, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun DiffNumbers(added: Int, removed: Int) {
    Column(horizontalAlignment = Alignment.End) {
        Text("+$added", style = MaterialTheme.typography.labelMedium, color = DshSurfaces.success)
        Text("−$removed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
    }
}
