package com.dshremote.app.attachments

import androidx.compose.runtime.Composable

data class PickedImage(
    val mediaType: String,
    val base64: String,
    val name: String?,
)

interface ImagePickerHandle {
    fun pickImage()
}

@Composable
expect fun rememberImagePicker(onPicked: (PickedImage) -> Unit): ImagePickerHandle
