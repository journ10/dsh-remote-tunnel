package com.dshremote.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One small, platform-independent outline icon set; no font-dependent glyphs. */
enum class DshSymbol(val data: String) {
    Code("M8 6 L2 12 L8 18 M16 6 L22 12 L16 18 M14 4 L10 20"),
    Plus("M12 5 V19 M5 12 H19"),
    Back("M14 5 L7 12 L14 19"),
    Down("M6 9 L12 15 L18 9"),
    Right("M9 6 L15 12 L9 18"),
    Search("M10 3 A7 7 0 1 0 10 17 A7 7 0 1 0 10 3 M15 15 L21 21"),
    Folder("M3 6 Q3 4 5 4 H9 L12 7 H19 Q21 7 21 9 V18 Q21 20 19 20 H5 Q3 20 3 18 Z"),
    Chat("M5 4 H19 Q21 4 21 6 V16 Q21 18 19 18 H9 L4 21 V18 Q2 18 2 16 V6 Q2 4 5 4 Z"),
    More("M5 12 H5.1 M12 12 H12.1 M19 12 H19.1"),
    Settings("M4 7 H20 M4 17 H20 M9 4 V10 M15 14 V20"),
    Terminal("M4 6 L10 12 L4 18 M13 18 H20"),
    Files("M7 3 H15 L20 8 V21 H7 Z M15 3 V8 H20 M3 7 V17"),
    Changes("M8 3 V15 A4 4 0 1 0 12 19 M16 3 V9 Q16 13 12 13 H8 M5 6 L8 3 L11 6 M13 6 L16 3 L19 6"),
    ArrowUp("M12 19 V5 M5 12 L12 5 L19 12"),
    Image("M3 3 H21 V21 H3 Z M3 17 L9 11 L14 16 L17 13 L21 17 M16 7 H16.1"),
    Shield("M12 3 L20 6 V12 Q20 18 12 22 Q4 18 4 12 V6 Z M8 12 L11 15 L16 9"),
    Clock("M12 3 A9 9 0 1 0 12 21 A9 9 0 1 0 12 3 M12 7 V12 L16 14"),
    Check("M5 12 L10 17 L20 6"),
    Close("M6 6 L18 18 M18 6 L6 18"),
    Globe("M12 3 A9 9 0 1 0 12 21 A9 9 0 1 0 12 3 M3 12 H21 M12 3 Q5 12 12 21 M12 3 Q19 12 12 21"),
    Monitor("M3 4 H21 V17 H3 Z M12 17 V21 M8 21 H16"),
    Qr("M3 3 H9 V9 H3 Z M15 3 H21 V9 H15 Z M3 15 H9 V21 H3 Z M15 15 H18 V18 H21 V21 H15 Z"),
    Branch("M6 3 V16 M6 16 A3 3 0 1 0 6 22 A3 3 0 1 0 6 16 M18 3 A3 3 0 1 0 18 9 A3 3 0 1 0 18 3 M18 9 Q18 14 6 14"),
}

private val symbols = DshSymbol.entries.associateWith { symbol ->
    ImageVector.Builder(symbol.name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathParser().parsePathString(symbol.data).toNodes(),
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}

@Composable
fun DshIcon(symbol: DshSymbol, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(symbols.getValue(symbol), description, modifier.size(20.dp), tint)
}

@Composable
fun DshIconButton(symbol: DshSymbol, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        DshIcon(symbol, label)
    }
}

@Composable
fun StatusBadge(label: String, active: Boolean = false) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(50),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (active) DshIcon(DshSymbol.Clock, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun EmptyState(symbol: DshSymbol, title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                DshIcon(symbol, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun ErrorNotice(message: String, onRetry: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (onRetry != null) TextButton(onRetry) { Text("重试") }
        }
    }
}

@Composable
fun ToolAction(symbol: DshSymbol, title: String, detail: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            DshIcon(symbol)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            DshIcon(DshSymbol.Right, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
