package com.dshremote.app.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
actual fun rememberQrScanner(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
): QrScannerHandle {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) onError("没有扫描到二维码") else onResult(contents)
    }
    return remember(launcher) {
        object : QrScannerHandle {
            override fun scan() {
                val options = ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("扫描 DSH 配对二维码")
                    .setBeepEnabled(false)
                    .setOrientationLocked(true)
                launcher.launch(options)
            }
        }
    }
}
