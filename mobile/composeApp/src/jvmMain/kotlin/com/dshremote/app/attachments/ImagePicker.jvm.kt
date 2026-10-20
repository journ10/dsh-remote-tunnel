package com.dshremote.app.attachments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberImagePicker(onPicked: (PickedImage) -> Unit): ImagePickerHandle =
    remember { object : ImagePickerHandle { override fun pickImage() = Unit } }
