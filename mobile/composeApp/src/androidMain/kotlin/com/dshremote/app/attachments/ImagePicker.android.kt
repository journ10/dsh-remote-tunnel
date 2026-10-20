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
actual fun rememberImagePicker(onPicked: (PickedImage) -> Unit): ImagePickerHandle {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching {
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    if (mime !in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
                        error("unsupported image type")
                    }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("cannot read image")
                    if (bytes.size > 8 * 1024 * 1024) error("image too large")
                    PickedImage(
                        mediaType = mime,
                        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        name = uri.lastPathSegment?.substringAfterLast('/'),
                    )
                }
            }.getOrNull()
            if (picked != null) onPicked(picked)
        }
    }
    return remember(launcher) {
        object : ImagePickerHandle {
            override fun pickImage() {
                launcher.launch("image/*")
            }
        }
    }
}
