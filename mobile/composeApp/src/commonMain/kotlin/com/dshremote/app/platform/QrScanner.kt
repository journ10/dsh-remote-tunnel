package com.dshremote.app.platform

import androidx.compose.runtime.Composable

interface QrScannerHandle {
    fun scan()
}

@Composable
expect fun rememberQrScanner(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
): QrScannerHandle
