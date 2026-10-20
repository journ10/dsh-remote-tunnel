package com.dshremote.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshremote.app.terminal.TerminalConnection
import com.dshremote.app.terminal.TerminalInfo
import com.dshremote.app.terminal.TerminalListener
import com.dshremote.app.terminal.closeTerminal
import com.dshremote.app.terminal.createTerminal
import com.dshremote.app.terminal.newTerminalId
import com.dshremote.app.terminal.openTerminal
import com.dshremote.app.terminal.stripAnsi
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TerminalDialog(
    client: DshClient,
    target: PairingTarget,
    sessionId: String,
    onDismiss: () -> Unit,
) {
    val uiScope = rememberCoroutineScope()
    val terminalId = remember(sessionId) { newTerminalId() }
    var screen by remember(terminalId) { mutableStateOf("") }
    var info by remember(terminalId) { mutableStateOf<TerminalInfo?>(null) }
    var writable by remember(terminalId) { mutableStateOf(true) }
    var input by remember(terminalId) { mutableStateOf("") }
    var error by remember(terminalId) { mutableStateOf<String?>(null) }
    var connection by remember(terminalId) { mutableStateOf<TerminalConnection?>(null) }

    LaunchedEffect(terminalId) {
        runCatching {
            val created = client.createTerminal(target, sessionId, terminalId)
            info = created
            while (true) {
                val closed = CompletableDeferred<Unit>()
                val next = client.openTerminal(target, sessionId, terminalId, object : TerminalListener {
                    override fun onSnapshot(nextScreen: String, nextInfo: TerminalInfo, nextWritable: Boolean) {
                        uiScope.launch {
                            screen = stripAnsi(nextScreen)
                            info = nextInfo
                            writable = nextWritable
                            error = null
                        }
                    }

                    override fun onOutput(data: String) {
                        uiScope.launch { screen = (screen + stripAnsi(data)).takeLast(200_000) }
                    }

                    override fun onState(nextInfo: TerminalInfo, nextWritable: Boolean) {
                        uiScope.launch {
                            info = nextInfo
                            writable = nextWritable
                        }
                    }

                    override fun onClosed(message: String) {
                        uiScope.launch { error = message }
                        closed.complete(Unit)
                    }
                })
                connection = next
                try {
                    closed.await()
                } finally {
                    next.close()
                    connection = null
                }
                delay(2_000)
            }
        }.onFailure {
            error = it.message ?: "无法打开终端"
        }
    }

    DisposableEffect(terminalId) {
        onDispose {
            connection?.close()
            uiScope.launch { runCatching { client.closeTerminal(target, sessionId, terminalId) } }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(info?.let { "${it.title} · ${it.state}" } ?: "终端")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (error != null && screen.isEmpty()) {
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    SelectionContainer {
                        Text(
                            screen.ifEmpty { "终端启动中…" },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
                error?.takeIf { screen.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("命令…", style = MaterialTheme.typography.labelSmall) },
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            val line = input
                            if (line.isNotEmpty()) {
                                connection?.sendLine(line)
                                input = ""
                            }
                        },
                        enabled = writable && connection != null,
                    ) { Text("发送") }
                    TextButton(
                        onClick = { connection?.sendControl() },
                        enabled = writable && connection != null,
                    ) { Text("^C") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
