package com.dshremote.app.attachments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): FilePickerHandle =
    remember { object : FilePickerHandle { override fun pickFile() = Unit } }
