package com.redpanda.cppide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FormatIndentDecrease
import androidx.compose.material.icons.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 编辑工具栏：撤销/重做/查找/注释/缩进/格式化/保存 + 符号栏开关。
 * 横向可滚动，触控目标 44dp，适合单手拇指操作。
 */
@Composable
fun EditorToolbar(
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: () -> Unit,
    onComment: () -> Unit,
    onIndent: () -> Unit,
    onUnindent: () -> Unit,
    onFormat: () -> Unit,
    onSnippets: () -> Unit,
    onGotoDefinition: () -> Unit,
    onInfo: () -> Unit,
    onReferences: () -> Unit,
    onRename: () -> Unit,
    onTasks: () -> Unit,
    onBreakpoint: () -> Unit,
    onSave: () -> Unit,
    symbolBarOn: Boolean,
    onToggleSymbolBar: () -> Unit
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarIcon(Icons.Filled.Undo, "撤销", onUndo)
            BarIcon(Icons.Filled.Redo, "重做", onRedo)
            BarIcon(Icons.Filled.Search, "查找替换", onFind)
            BarIcon(Icons.Filled.Comment, "注释", onComment)
            BarIcon(Icons.Filled.FormatIndentIncrease, "增加缩进", onIndent)
            BarIcon(Icons.Filled.FormatIndentDecrease, "减少缩进", onUnindent)
            BarIcon(Icons.Filled.AutoFixHigh, "格式化", onFormat)
            BarIcon(Icons.Filled.Code, "插入片段", onSnippets)
            BarIcon(Icons.Filled.ArrowForward, "跳转定义", onGotoDefinition)
            BarIcon(Icons.Filled.ManageSearch, "查找引用", onReferences)
            BarIcon(Icons.Filled.DriveFileRenameOutline, "重命名符号", onRename)
            BarIcon(Icons.Filled.Info, "查看类型/签名", onInfo)
            BarIcon(Icons.Filled.FormatListBulleted, "任务与书签", onTasks)
            BarIcon(Icons.Filled.Circle, "添加/删除断点", onBreakpoint)
            BarIcon(Icons.Filled.Save, "保存", onSave)
            BarIcon(
                Icons.Filled.Keyboard,
                "符号栏",
                onToggleSymbolBar,
                active = symbolBarOn
            )
        }
    }
}

@Composable
private fun BarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 符号快捷输入栏使用的符号分组（按使用频率排） */
private val SYMBOLS = listOf(
    "{", "}", "(", ")", "[", "]", ";", ",", ".",
    "<", ">", "\"", "'", "#", "\\", "|", "&", "%",
    "=", "==", "!=", "+", "-", "*", "/", "++", "--",
    "::", "->", "<<", ">>", "<=", ">=", "&&", "||",
    "!", "?", "_", "~", "^", "$", "@"
)

/**
 * 符号快捷输入栏：解决手机打代码符号的痛点。
 * 横向滚动，紧凑排版。
 */
@Composable
fun SymbolBar(onSymbol: (String) -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (s in SYMBOLS) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    TextButton(
                        onClick = { onSymbol(s) },
                        modifier = Modifier.height(36.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 10.dp, vertical = 0.dp
                        )
                    ) {
                        Text(
                            s,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
