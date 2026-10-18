package com.redpanda.cppide.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * 断点标记绘制。
 *
 * 为什么不把点塞进行号区里：手机上那条 gutter 已经只够显示数字了。
 * 但 Sora 的行号栏结构是 `数字 + lineNumberMarginLeft`（`measureLineNumber() = 数字宽 + 左边距`），
 * 且数字默认**右对齐**，所以左边那段 `lineNumberMarginLeft` 本来就是空白。
 * 编辑器启动时把这段边距加宽（见 EditorCore 里的 setLineNumberMarginLeft），
 * 红点正好画在这里 —— 就是经典的 `● 123  代码…` 排布。
 *
 * X 坐标的推导（都来自 Sora 源码/字节码）：
 *   EditorRenderer.drawLineNumber(canvas, line, -offsetX, measureLineNumber(), color)
 *   数字右对齐在 `[-offsetX, -offsetX + measureLineNumber]` 里，
 *   因此左侧空白中心 = `-offsetX + lineNumberMarginLeft / 2`
 *
 * 完全走覆盖层、不碰 Styles，所以 `styles=null` 也不影响显示。
 */

/** 断点红点颜色 */
val BreakpointDotColor = Color(0xFFE53935)

/**
 * @param editor 编辑器，null 时跳过
 * @param lines 断点行号（**0 基**）
 * @param dotRadiusPx 红点半径（像素）
 */
fun DrawScope.drawBreakpointMarks(
    editor: CodeEditor?,
    lines: Set<Int>,
    dotRadiusPx: Float,
    dotColor: Color = BreakpointDotColor
) {
    val ed = editor ?: return
    if (lines.isEmpty()) return

    val rowHeight = ed.rowHeight.toFloat()
    if (rowHeight <= 0f) return

    val offsetX = ed.getOffsetX().toFloat()
    val offsetY = ed.getOffsetY().toFloat()
    // 行号左边的空白条中心（数字右对齐，这块区域空着）
    val marginLeft = runCatching { ed.lineNumberMarginLeft }.getOrDefault(0f)
    val dotCx = (marginLeft / 2f - offsetX).coerceAtLeast(dotRadiusPx)

    val lineCount = runCatching { ed.text.lineCount }.getOrDefault(1)

    for (line in lines) {
        if (line < 0) continue
        val l = line.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        val top = runCatching {
            // 逻辑行 → 所在显示行（自动换开时一行可能占多行）
            val index = ed.text.getCharIndex(l, 0)
            val row = ed.layout.getRowIndexForPosition(index)
            ed.getRowTop(row).toFloat() - offsetY
        }.getOrNull() ?: continue
        if (top + rowHeight < 0f || top > size.height) continue

        drawCircle(
            color = dotColor,
            radius = dotRadiusPx,
            center = Offset(dotCx, top + rowHeight / 2f)
        )
    }
}
