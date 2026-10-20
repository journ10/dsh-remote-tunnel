package com.dshremote.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshremote.app.transport.PairingTarget
import com.dshremote.app.ui.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    connections: List<PairingTarget>,
    activeOrigin: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSwitch: (PairingTarget) -> Unit,
    onAdd: () -> Unit,
    onRemove: (PairingTarget) -> Unit,
) {
    var removal by remember { mutableStateOf<PairingTarget?>(null) }
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DshSurfaces.base,
    ) {
        InstancesContent(connections, activeOrigin, busy, onSwitch, onAdd, { removal = it })
    }
    removal?.let { instance ->
        AlertDialog(
            onDismissRequest = { removal = null },
            title = { Text("移除这个实例？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(instance.origin, style = MaterialTheme.typography.bodyMedium)
                    Text("之后使用此实例需要重新扫码配对。其他实例的配对会保留。")
                }
            },
            confirmButton = {
                TextButton(onClick = { removal = null; onRemove(instance) }, enabled = !busy) {
                    Text("移除实例", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removal = null }) { Text("取消") } },
        )
    }
}

@Composable
internal fun InstancesContent(
    connections: List<PairingTarget>,
    activeOrigin: String?,
    busy: Boolean,
    onSwitch: (PairingTarget) -> Unit,
    onAdd: () -> Unit,
    onRemove: (PairingTarget) -> Unit,
) {
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("实例", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("${connections.size} 个已配对", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "切换电脑或 DSH 实例，继续你的工作。",
            Modifier.padding(top = 8.dp, bottom = 20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            Modifier.weight(1f, fill = false).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            items(connections, key = { it.origin }) { instance ->
                val active = instance.origin == activeOrigin
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = if (active) MaterialTheme.colorScheme.secondaryContainer else DshSurfaces.sidebar,
                    border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)) else null,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = { onSwitch(instance) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).semantics { selected = active },
                            color = androidx.compose.ui.graphics.Color.Transparent,
                        ) {
                            Row(
                                Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                DshIcon(DshSymbol.Monitor, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(instance.host, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(instance.origin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (active) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        DshIcon(DshSymbol.Check, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                                        Text("当前实例", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            DshIconButton(DshSymbol.More, "管理实例 ${instance.origin}", { menu = true }, enabled = !busy)
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("移除实例", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; onRemove(instance) },
                                )
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onAdd, enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp).heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            DshIcon(DshSymbol.Plus)
            Spacer(Modifier.width(8.dp))
            Text("添加实例")
        }
    }
}
