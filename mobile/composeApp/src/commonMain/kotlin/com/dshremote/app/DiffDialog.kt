package com.dshremote.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshremote.app.chat.ChatItem
import com.dshremote.app.diff.DiffKind
import com.dshremote.app.diff.DiffLine
import com.dshremote.app.diff.diffStats
import com.dshremote.app.diff.lineDiff
import com.dshremote.app.diff.sideBySide

@Composable
fun DiffDialog(diff: ChatItem.ToolDiff, onDismiss: () -> Unit) {
    val lines = remember(diff) { lineDiff(diff.oldText, diff.newText) }
    val stats = remember(lines) { diffStats(lines) }
    val paired = remember(lines) { sideBySide(lines) }
    var side by remember(diff) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(diff.path, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text(
                    "+${stats.added} −${stats.removed}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            ) {
                Row(modifier = Modifier.padding(bottom = 4.dp)) {
                    TextButton(onClick = { side = false }, enabled = side) { Text("统一") }
                    TextButton(onClick = { side = true }, enabled = !side) { Text("并排") }
                }
                if (!side) {
                    SelectionContainer {
                        Column {
                            for (line in lines) {
                                Text(
                                    prefixOf(line) + line.text,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(backgroundOf(line.kind))
                                        .padding(vertical = 1.dp),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = colorOf(line.kind),
                                )
                            }
                        }
                    }
                } else {
                    SelectionContainer {
                        Column {
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text("旧", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                                Text("新", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            }
                            for (pair in paired) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    SideCell(pair.left, Modifier.weight(1f))
                                    Spacer(
                                        Modifier
                                            .width(1.dp)
                                            .heightIn(min = 15.dp)
                                            .background(MaterialTheme.colorScheme.outlineVariant),
                                    )
                                    SideCell(pair.right, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun SideCell(line: DiffLine?, modifier: Modifier) {
    val kind = line?.kind ?: DiffKind.Context
    Text(
        text = line?.text.orEmpty(),
        modifier = modifier
            .background(backgroundOf(kind))
            .padding(horizontal = 2.dp, vertical = 1.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colorOf(kind),
    )
}

private fun prefixOf(line: DiffLine): String = when (line.kind) {
    DiffKind.Added -> "+ "
    DiffKind.Removed -> "- "
    DiffKind.Context -> "  "
}

@Composable
private fun colorOf(kind: DiffKind): Color = when (kind) {
    DiffKind.Added -> DshSurfaces.success
    DiffKind.Removed -> MaterialTheme.colorScheme.error
    DiffKind.Context -> MaterialTheme.colorScheme.onSurface
}

private fun backgroundOf(kind: DiffKind): Color = when (kind) {
    DiffKind.Added -> Color(0x1A2E7D32)
    DiffKind.Removed -> Color(0x1AC62828)
    DiffKind.Context -> Color.Transparent
}
