package com.dshremote.app.platform

import android.content.Intent
import android.net.Uri
import com.dshremote.app.transport.AndroidPlatform

actual fun openExternalUrlPlatform(url: String) {
    val context = AndroidPlatform.appContext
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
