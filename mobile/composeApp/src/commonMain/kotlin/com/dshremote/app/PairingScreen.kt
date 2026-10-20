package com.dshremote.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dshremote.app.platform.rememberQrScanner
import com.dshremote.app.platform.PlatformBackHandler
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget
import com.dshremote.app.transport.PlatformServices
import com.dshremote.app.ui.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
fun PairingScreen(
    services: PlatformServices,
    onCancel: (() -> Unit)? = null,
    onPaired: (PairingTarget) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val client = remember(services) { DshClient(services.transport, services.cookies) }

    fun connect(raw: String) {
        if (raw.isBlank() || busy) return
        url = raw.trim()
        busy = true
        error = null
        scope.launch {
            runCatching {
                client.pair(raw.trim()).also { services.connections.save(it) }
            }
                .onSuccess { target ->
                    busy = false
                    onPaired(target)
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    busy = false
                    error = it.message ?: "配对失败"
                }
        }
    }

    val scanner = rememberQrScanner(
        onResult = { contents -> connect(contents) },
        onError = { error = it },
    )

    PlatformBackHandler(enabled = onCancel != null || busy) { if (!busy) onCancel?.invoke() }
    PairingContent(url, { url = it }, busy, error, { scanner.scan() }, { connect(url) }, onCancel)
}

@Composable
internal fun PairingContent(url: String, onUrl: (String) -> Unit, busy: Boolean, error: String?, onScan: () -> Unit, onConnect: () -> Unit, onCancel: (() -> Unit)? = null) {
    Surface(color = DshSurfaces.base) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (onCancel != null) TextButton(onClick = onCancel, enabled = !busy) {
                    DshIcon(DshSymbol.Back)
                    Spacer(Modifier.width(6.dp))
                    Text("返回已有实例")
                }
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary) {
                    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                        DshIcon(DshSymbol.Code, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Text("DSH Remote", style = MaterialTheme.typography.headlineMedium)
                Text(if (onCancel != null) "添加一个新实例" else "把你的工作区，带在身边。", style = MaterialTheme.typography.titleLarge)
                Text("连接电脑上的 DeepSeek Harness，随时发起任务、跟进进度、审阅代码。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = DshSurfaces.sidebar) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        PairingStep("1", "在电脑端打开 DSH", "进入插件设置，生成远程配对二维码")
                        PairingStep("2", "扫描二维码", "配对后即可访问你的工作区和会话")
                    }
                }
                Button(onScan, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else { DshIcon(DshSymbol.Qr); Spacer(Modifier.width(10.dp)); Text("扫码配对") }
                }
                var manual by remember { mutableStateOf(false) }
                TextButton(onClick = { manual = !manual }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (manual) "收起链接配对" else "使用配对链接")
                    Spacer(Modifier.width(6.dp))
                    DshIcon(if (manual) DshSymbol.Down else DshSymbol.Right, modifier = Modifier.size(16.dp))
                }
                if (manual) {
                    OutlinedTextField(url, onUrl, modifier = Modifier.fillMaxWidth(), enabled = !busy, label = { Text("配对链接") }, placeholder = { Text("https://…/pair…") }, maxLines = 3, shape = RoundedCornerShape(14.dp))
                    OutlinedButton(onConnect, enabled = url.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("连接工作区") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun PairingStep(number: String, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(number, style = MaterialTheme.typography.titleMedium, color = DshSurfaces.link)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
