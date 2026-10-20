package com.dshremote.app.attachments

import androidx.compose.runtime.Composable

data class PickedFile(
    val name: String,
    val base64: String,
    val bytes: Long,
)

interface FilePickerHandle {
    fun pickFile()
}

@Composable
expect fun rememberFilePicker(onPicked: (PickedFile) -> Unit): FilePickerHandle
