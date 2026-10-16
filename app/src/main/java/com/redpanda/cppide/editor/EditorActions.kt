package com.redpanda.cppide.editor

import io.github.rosemoe.sora.widget.CodeEditor

/**
 * 编辑器行级操作：注释切换、缩进调整。
 * 全部基于 Sora 的 Content API（已核对签名）。
 */
object EditorActions {

    private const val INDENT = "    "

    /** 注释 / 取消注释选中的行（// 风格） */
    fun toggleLineComment(editor: CodeEditor) {
        val content = editor.text
        val cursor = editor.cursor
        val start = minOf(cursor.leftLine, cursor.rightLine)
        val end = maxOf(cursor.leftLine, cursor.rightLine)
        if (start < 0 || end >= content.lineCount) return

        // 只有「所有非空行都已注释」时才取消注释
        var allCommented = true
        for (l in start..end) {
            val s = content.getLineString(l).trimStart()
            if (s.isNotEmpty() && !s.startsWith("//")) {
                allCommented = false
                break
            }
        }

        content.beginBatchEdit()
        try {
            for (l in start..end) {
                val line = content.getLineString(l)
                if (allCommented) {
                    val idx = line.indexOf("//")
                    // Content.delete 只有 (行,列) 与 (起行,起列,止行,止列) 两个重载
                    if (idx >= 0) content.delete(l, idx, l, idx + 2)
                } else {
                    if (line.isBlank()) continue
                    val indent = line.length - line.trimStart().length
                    content.insert(l, indent, "//")
                }
            }
        } finally {
            content.endBatchEdit()
        }
    }

    /** 增加缩进 */
    fun indent(editor: CodeEditor) {
        val content = editor.text
        val cursor = editor.cursor
        val start = minOf(cursor.leftLine, cursor.rightLine)
        val end = maxOf(cursor.leftLine, cursor.rightLine)
        if (start < 0 || end >= content.lineCount) return

        content.beginBatchEdit()
        try {
            for (l in start..end) {
                if (content.getLineString(l).isBlank()) continue
                content.insert(l, 0, INDENT)
            }
        } finally {
            content.endBatchEdit()
        }
    }

    /** 减少缩进 */
    fun unindent(editor: CodeEditor) {
        val content = editor.text
        val cursor = editor.cursor
        val start = minOf(cursor.leftLine, cursor.rightLine)
        val end = maxOf(cursor.leftLine, cursor.rightLine)
        if (start < 0 || end >= content.lineCount) return

        content.beginBatchEdit()
        try {
            for (l in start..end) {
                val line = content.getLineString(l)
                var n = 0
                while (n < INDENT.length && n < line.length && line[n] == ' ') n++
                if (n == 0 && line.isNotEmpty() && line[0] == '\t') n = 1
                if (n > 0) content.delete(l, 0, l, n)
            }
        } finally {
            content.endBatchEdit()
        }
    }

    /** 在光标处插入文本（供符号快捷输入栏使用） */
    fun insertText(editor: CodeEditor, text: String) {
        val cursor = editor.cursor
        val line = cursor.leftLine
        val col = cursor.leftColumn
        editor.text.insert(line, col, text)
        editor.setSelection(line, col + text.length)
        editor.ensureSelectionVisible()
    }

    /**
     * 取光标处的标识符。
     * 返回 (标识符, 起始列0基, 行号0基)，光标处没有标识符时返回 null。
     * 会把 `::` 当成标识符的一部分，让 clangd 能识别 `std::cout`。
     */
    fun wordAt(editor: CodeEditor): Triple<String, Int, Int>? {
        val content = editor.text
        val cursor = editor.cursor
        val line = cursor.leftLine
        val col = cursor.leftColumn
        if (line !in 0 until content.lineCount) return null
        val text = content.getLineString(line)
        if (col > text.length) return null

        var start = col
        while (start > 0 && isIdentChar(text[start - 1])) start--
        var end = col
        while (end < text.length && isIdentChar(text[end])) end++
        if (start >= end) return null
        val word = text.substring(start, end)
        if (word.isEmpty() || word.all { it == ':' }) return null
        return Triple(word, start, line)
    }

    private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == ':'

    /** 跳转到指定行（1 基）并选中该列 */
    fun jumpTo(editor: CodeEditor, line1Based: Int, column1Based: Int) {        val content = editor.text
        val line = (line1Based - 1).coerceIn(0, (content.lineCount - 1).coerceAtLeast(0))
        val col = (column1Based - 1).coerceAtLeast(0)
            .coerceAtMost(content.getLineString(line).length)
        editor.setSelection(line, col)
        editor.ensureSelectionVisible()
    }

    /**
     * 基于大括号层级的重新缩进。
     *
     * 不依赖外部格式化工具（Sora 的 formatCodeAsync 需要语言插件，TextMate 没有），
     * 手机上整理缩进足够用。会跳过字符串/字符/注释里的大括号。
     */
    fun reformat(editor: CodeEditor) {
        val content = editor.text
        val n = content.lineCount
        if (n == 0) return

        val out = ArrayList<String>(n)
        var depth = 0
        val st = ScanState()

        for (i in 0 until n) {
            val raw = content.getLineString(i)
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                out.add("")
                continue
            }
            // 行首连续的闭括号先减层级（} 或 }; 之类）
            var d = depth
            var k = 0
            while (k < trimmed.length && trimmed[k] == '}') {
                d--
                k++
            }
            out.add(INDENT.repeat(d.coerceAtLeast(0)) + trimmed)
            depth = (depth + netBraces(trimmed, st)).coerceAtLeast(0)
        }

        content.beginBatchEdit()
        try {
            for (i in 0 until n) {
                val old = content.getLineString(i)
                val new = out[i]
                if (old != new) content.replace(i, 0, i, old.length, new)
            }
        } finally {
            content.endBatchEdit()
        }
    }

    /** 跨行块注释状态 */
    private class ScanState {
        var inBlockComment = false
    }

    /** 统计一行中的净大括号数，跳过字符串、字符与注释 */
    private fun netBraces(line: String, st: ScanState): Int {
        var net = 0
        var i = 0
        var inStr = false
        var inChr = false
        var inLineComment = false
        while (i < line.length) {
            val c = line[i]
            val next = if (i + 1 < line.length) line[i + 1] else '\u0000'
            when {
                inLineComment -> { /* 行注释，忽略整行剩余 */ }
                st.inBlockComment -> if (c == '*' && next == '/') {
                    st.inBlockComment = false
                    i++
                }
                inStr -> {
                    if (c == '\\') i++ else if (c == '"') inStr = false
                }
                inChr -> {
                    if (c == '\\') i++ else if (c == '\'') inChr = false
                }
                c == '/' && next == '/' -> { inLineComment = true; i++ }
                c == '/' && next == '*' -> { st.inBlockComment = true; i++ }
                c == '"' -> inStr = true
                c == '\'' -> inChr = true
                c == '{' -> net++
                c == '}' -> net--
            }
            i++
        }
        return net
    }
}
