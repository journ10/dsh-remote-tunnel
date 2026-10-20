package com.dshremote.app.platform

import java.awt.Desktop
import java.net.URI

actual fun openExternalUrlPlatform(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}
