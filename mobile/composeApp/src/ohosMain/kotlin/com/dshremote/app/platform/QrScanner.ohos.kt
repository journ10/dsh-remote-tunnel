package com.dshremote.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberQrScanner(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
): QrScannerHandle = remember {
    object : QrScannerHandle {
        override fun scan() = onError("当前平台暂不支持扫码")
    }
}
