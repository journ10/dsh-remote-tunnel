package com.dshremote.app.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Platform preview for image/PDF workspace blobs. Text falls back to the text viewer. */
@Composable
expect fun PlatformFilePreview(
    bytes: ByteArray,
    mediaType: String?,
    path: String,
    modifier: Modifier = Modifier,
)
