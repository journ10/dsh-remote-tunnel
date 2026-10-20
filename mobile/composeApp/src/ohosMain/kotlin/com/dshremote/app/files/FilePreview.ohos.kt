package com.dshremote.app.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
actual fun PlatformFilePreview(
    bytes: ByteArray,
    mediaType: String?,
    path: String,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = 120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("此平台暂不支持文件预览", style = MaterialTheme.typography.labelSmall)
    }
}
