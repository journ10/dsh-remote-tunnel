package com.dshremote.app.terminal

private val ANSI = Regex("\u001B(?:\\[[0-9;?]*[ -/]*[@-~]|\\][^\u0007]*(?:\u0007|\u001B\\\\)|[()][A-Z0-9]|[=><])")

fun stripAnsi(text: String): String = ANSI.replace(text, "").replace("\r", "")
