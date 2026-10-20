package com.dshremote.app

import com.dshremote.app.terminal.stripAnsi
import kotlin.test.Test
import kotlin.test.assertEquals

class AnsiTest {
    @Test
    fun stripsCursorAndColorSequences() {
        assertEquals("hello\nworld", stripAnsi("\u001B[1;32mhello\u001B[0m\r\n\u001B[2Kworld"))
    }
}
