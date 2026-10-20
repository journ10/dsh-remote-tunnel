package com.dshremote.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshremote.app.jobs.JobOutputListener
import com.dshremote.app.jobs.JobRow
import com.dshremote.app.jobs.openJobOutput
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget

@Composable
fun JobOutputDialog(
    client: DshClient,
    target: PairingTarget,
    sessionId: String,
    job: JobRow,
    onDismiss: () -> Unit,
) {
    var output by remember(job.id) { mutableStateOf("") }
    var error by remember(job.id) { mutableStateOf<String?>(null) }
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    DisposableEffect(job.id) {
        var handle: com.dshremote.app.transport.SocketHandle? = null
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        scope.launch {
            runCatching {
                client.openJobOutput(target, sessionId, job.id, object : JobOutputListener {
                    override fun onOutput(text: String) {
                        uiScope.launch { output = (output + text).takeLast(200_000) }
                    }

                    override fun onClosed(message: String) {
                        uiScope.launch { error = message }
                    }
                })
            }.onSuccess { handle = it }
        }
        onDispose { handle?.close() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(job.label) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (output.isEmpty()) {
                    Text("等待输出…", style = MaterialTheme.typography.labelSmall)
                } else {
                    SelectionContainer {
                        Text(
                            output,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
                error?.let {
                    Text(it, modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
