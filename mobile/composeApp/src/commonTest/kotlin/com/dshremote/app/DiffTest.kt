package com.dshremote.app

import com.dshremote.app.diff.DiffKind
import com.dshremote.app.diff.diffStats
import com.dshremote.app.diff.lineDiff
import com.dshremote.app.diff.sideBySide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffTest {
    @Test
    fun marksAddedRemovedAndContextLines() {
        val lines = lineDiff("a\nb\nc", "a\nB\nc")
        assertEquals(DiffKind.Context, lines.first().kind)
        assertTrue(lines.any { it.kind == DiffKind.Removed && it.text == "b" })
        assertTrue(lines.any { it.kind == DiffKind.Added && it.text == "B" })
        assertEquals(DiffKind.Context, lines.last().kind)
        assertEquals(1, diffStats(lines).added)
        assertEquals(1, diffStats(lines).removed)
    }

    @Test
    fun pairsRemovedAndAddedLinesSideBySide() {
        val pairs = sideBySide(lineDiff("a\nb\nc", "a\nB\nC"))
        assertEquals(3, pairs.size)
        assertEquals("a", pairs[0].left?.text)
        assertEquals("b", pairs[1].left?.text)
        assertEquals("B", pairs[1].right?.text)
        assertEquals("c", pairs[2].left?.text)
        assertEquals("C", pairs[2].right?.text)
    }

    @Test
    fun treatsNewFileAsAllAdded() {
        val lines = lineDiff(null, "x\ny")
        assertEquals(listOf(DiffKind.Added, DiffKind.Added), lines.map { it.kind })
    }
}
