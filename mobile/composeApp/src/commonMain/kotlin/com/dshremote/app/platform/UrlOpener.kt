package com.dshremote.app.platform

fun openExternalUrl(url: String) {
    if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
        openExternalUrlPlatform(url)
    }
}

expect fun openExternalUrlPlatform(url: String)
