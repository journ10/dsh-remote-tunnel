package com.dshremote.app.sessions

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.time

@OptIn(ExperimentalForeignApi::class)
actual fun currentEpochMillis(): Long = time(null) * 1000L
