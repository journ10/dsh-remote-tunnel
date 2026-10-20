package com.dshremote.app.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
actual fun PlatformFilePreview(
    bytes: ByteArray,
    mediaType: String?,
    path: String,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lower = path.lowercase()
    val isPdf = lower.endsWith(".pdf") || mediaType?.contains("pdf", ignoreCase = true) == true
    val bitmap = remember(bytes, path) {
        runCatching {
            if (isPdf) renderPdfFirstPage(context.cacheDir, path, bytes)
            else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    if (bitmap == null) {
        Box(
            modifier = modifier.fillMaxWidth().heightIn(min = 120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("无法预览此文件", style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = path.substringAfterLast('/'),
        modifier = modifier.fillMaxWidth().heightIn(max = 430.dp).padding(vertical = 4.dp),
        contentScale = ContentScale.Fit,
    )
}

private fun renderPdfFirstPage(cacheDir: File, path: String, bytes: ByteArray): Bitmap? {
    val file = File(cacheDir, "dsh-preview-${path.hashCode()}.pdf")
    file.writeBytes(bytes)
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val width = 1200
                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }
}
