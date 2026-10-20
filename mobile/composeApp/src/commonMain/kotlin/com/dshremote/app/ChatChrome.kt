package com.dshremote.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshremote.app.sessions.SessionRow
import com.dshremote.app.ui.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatHeader(session: SessionRow, onBack: () -> Unit, onTools: () -> Unit) {
    TopAppBar(
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(session.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(session.workspaceName + if (session.isSubagent) " · 子会话" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = { DshIconButton(DshSymbol.Back, "返回会话列表", onBack) },
        actions = { DshIconButton(DshSymbol.More, "工作区工具", onTools) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = DshSurfaces.base),
    )
}

@Composable
internal fun ChatViewTabs(review: Boolean, changes: Int, onSelect: (Boolean) -> Unit) {
    TabRow(selectedTabIndex = if (review) 1 else 0, containerColor = DshSurfaces.base, contentColor = MaterialTheme.colorScheme.onSurface) {
        Tab(!review, { onSelect(false) }, text = { Text("对话") })
        Tab(review, { onSelect(true) }, text = { Text(if (changes > 0) "变更 · $changes" else "变更") })
    }
}

@Composable
internal fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    model: String,
    permission: String,
    deliveryMode: String,
    planActive: Boolean,
    sending: Boolean,
    canSend: Boolean,
    readOnly: Boolean,
    onModel: () -> Unit,
    onPermission: () -> Unit,
    onDelivery: () -> Unit,
    onExitPlan: () -> Unit,
    onImage: () -> Unit,
    onFile: () -> Unit,
    onSend: () -> Unit,
) {
    var attachmentsOpen by remember { mutableStateOf(false) }
    if (readOnly) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp), color = DshSurfaces.sidebar) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                DshIcon(DshSymbol.Shield)
                Text("此子会话为只读，可返回主会话继续任务。", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    BoxWithConstraints {
        val compact = maxHeight < 400.dp
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp), color = DshSurfaces.base,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(Modifier.padding(8.dp)) {
                BasicTextField(
                    value, onValueChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).semantics { contentDescription = "消息，支持斜杠命令和 @ 引用" },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(DshSurfaces.link), minLines = if (compact) 1 else 2, maxLines = if (compact) 2 else 5,
                    decorationBox = { field ->
                        Box {
                            if (value.isEmpty()) Text("描述任务，或继续追问…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            field()
                        }
                    },
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        DshIconButton(DshSymbol.Plus, "添加附件或引用", { attachmentsOpen = true })
                        DropdownMenu(attachmentsOpen, { attachmentsOpen = false }) {
                            DropdownMenuItem({ Text("添加图片") }, { attachmentsOpen = false; onImage() }, leadingIcon = { DshIcon(DshSymbol.Image) })
                            DropdownMenuItem({ Text("添加文件") }, { attachmentsOpen = false; onFile() }, leadingIcon = { DshIcon(DshSymbol.Files) })
                            DropdownMenuItem({ Text("引用文件或会话") }, { attachmentsOpen = false; onValueChange(value + if (value.isBlank() || value.endsWith(' ')) "@" else " @") }, leadingIcon = { DshIcon(DshSymbol.Folder) })
                            DropdownMenuItem({ Text("命令与技能") }, { attachmentsOpen = false; onValueChange(if (value.isBlank()) "/" else value) }, enabled = value.isBlank(), leadingIcon = { DshIcon(DshSymbol.Terminal) })
                            if (compact) {
                                HorizontalDivider()
                                DropdownMenuItem({ Text("权限 · $permission") }, { attachmentsOpen = false; onPermission() }, leadingIcon = { DshIcon(DshSymbol.Shield) })
                                DropdownMenuItem({ Text(if (deliveryMode == "steer") "改为排队发送" else "改为立即插话") }, { attachmentsOpen = false; onDelivery() }, leadingIcon = { DshIcon(DshSymbol.Clock) })
                                if (planActive) DropdownMenuItem({ Text("退出计划模式") }, { attachmentsOpen = false; onExitPlan() })
                            }
                        }
                    }
                    TextButton(onModel, modifier = Modifier.weight(1f)) {
                        Text(model, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(4.dp))
                        DshIcon(DshSymbol.Down, modifier = Modifier.size(14.dp))
                    }
                    FilledIconButton(onSend, enabled = canSend && !sending, modifier = Modifier.size(48.dp), shape = CircleShape) {
                        if (sending) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        else DshIcon(DshSymbol.ArrowUp, "发送消息")
                    }
                }
                if (!compact) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onPermission) {
                        DshIcon(DshSymbol.Shield, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(permission, style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onDelivery) {
                        DshIcon(DshSymbol.Clock, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (deliveryMode == "steer") "立即插话" else "排队发送", style = MaterialTheme.typography.labelMedium)
                    }
                    if (planActive) TextButton(onExitPlan) { Text("退出计划", style = MaterialTheme.typography.labelMedium, color = DshSurfaces.link) }
                }
            }
        }
    }
}
