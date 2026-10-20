package com.dshremote.app.attachments

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): FilePickerHandle {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("cannot read file")
                    if (bytes.size > 20 * 1024 * 1024) error("file too large")
                    PickedFile(
                        name = uri.lastPathSegment?.substringAfterLast('/') ?: "file",
                        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        bytes = bytes.size.toLong(),
                    )
                }
            }.getOrNull()
            if (picked != null) onPicked(picked)
        }
    }
    return remember(launcher) {
        object : FilePickerHandle {
            override fun pickFile() {
                launcher.launch("*/*")
            }
        }
    }
}
