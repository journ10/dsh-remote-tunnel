package com.dshremote.app.diff

enum class DiffKind { Context, Added, Removed }

data class DiffLine(val kind: DiffKind, val text: String)

data class DiffStats(val added: Int, val removed: Int)

data class SideBySideLine(val left: DiffLine?, val right: DiffLine?)

fun sideBySide(lines: List<DiffLine>): List<SideBySideLine> {
    val out = mutableListOf<SideBySideLine>()
    var i = 0
    while (i < lines.size) {
        if (lines[i].kind == DiffKind.Context) {
            out.add(SideBySideLine(lines[i], lines[i]))
            i++
            continue
        }
        val removed = mutableListOf<DiffLine>()
        val added = mutableListOf<DiffLine>()
        while (i < lines.size && lines[i].kind != DiffKind.Context) {
            val line = lines[i]
            if (line.kind == DiffKind.Removed) removed.add(line) else added.add(line)
            i++
        }
        val rows = maxOf(removed.size, added.size)
        for (row in 0 until rows) {
            out.add(SideBySideLine(removed.getOrNull(row), added.getOrNull(row)))
        }
    }
    return out
}

fun diffStats(lines: List<DiffLine>): DiffStats = DiffStats(
    added = lines.count { it.kind == DiffKind.Added },
    removed = lines.count { it.kind == DiffKind.Removed },
)

fun lineDiff(oldText: String?, newText: String): List<DiffLine> {
    val oldLines = oldText?.split('\n').orEmpty()
    val newLines = newText.split('\n')
    if (oldText == null) return newLines.map { DiffLine(DiffKind.Added, it) }
    if (oldLines == newLines) return newLines.map { DiffLine(DiffKind.Context, it) }

    var prefix = 0
    while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
    var suffix = 0
    while (
        suffix < oldLines.size - prefix &&
        suffix < newLines.size - prefix &&
        oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
    ) suffix++

    val before = oldLines.take(prefix)
    val after = if (suffix == 0) emptyList() else oldLines.takeLast(suffix)
    val a = oldLines.subList(prefix, oldLines.size - suffix)
    val b = newLines.subList(prefix, newLines.size - suffix)
    val result = mutableListOf<DiffLine>()
    before.forEach { result.add(DiffLine(DiffKind.Context, it)) }
    result.addAll(lcsDiff(a, b))
    after.forEach { result.add(DiffLine(DiffKind.Context, it)) }
    return result
}

private fun lcsDiff(a: List<String>, b: List<String>): List<DiffLine> {
    if (a.isEmpty()) return b.map { DiffLine(DiffKind.Added, it) }
    if (b.isEmpty()) return a.map { DiffLine(DiffKind.Removed, it) }
    val n = a.size
    val m = b.size
    if (n * m > 4_000_000) {
        return a.map { DiffLine(DiffKind.Removed, it) } + b.map { DiffLine(DiffKind.Added, it) }
    }
    val table = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1
            else maxOf(table[i + 1][j], table[i][j + 1])
        }
    }
    val out = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> {
                out.add(DiffLine(DiffKind.Context, a[i]))
                i++
                j++
            }
            table[i + 1][j] >= table[i][j + 1] -> {
                out.add(DiffLine(DiffKind.Removed, a[i]))
                i++
            }
            else -> {
                out.add(DiffLine(DiffKind.Added, b[j]))
                j++
            }
        }
    }
    while (i < n) out.add(DiffLine(DiffKind.Removed, a[i++]))
    while (j < m) out.add(DiffLine(DiffKind.Added, b[j++]))
    return out
}
