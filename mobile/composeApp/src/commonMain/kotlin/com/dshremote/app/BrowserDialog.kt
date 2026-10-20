package com.dshremote.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dshremote.app.platform.PlatformWebView
import com.dshremote.app.platform.openExternalUrl
import com.dshremote.app.ui.*

@Composable
fun BrowserDialog(url: String, onDismiss: () -> Unit) {
    var address by remember(url) { mutableStateOf(url) }
    var current by remember(url) { mutableStateOf(url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("浏览器") },
        text = {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("网页地址") },
                        placeholder = { Text("https://…") },
                        textStyle = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = {
                        val next = address.trim()
                        if (next.startsWith("http://") || next.startsWith("https://")) current = next
                    }) { Text("打开") }
                }
                if (current.isBlank()) EmptyState(DshSymbol.Globe, "打开网页", "输入网页或本地服务的完整地址")
                else PlatformWebView(
                    current,
                    Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 440.dp),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = current.isNotBlank(), onClick = { openExternalUrl(current) }) { Text("系统浏览器") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}
