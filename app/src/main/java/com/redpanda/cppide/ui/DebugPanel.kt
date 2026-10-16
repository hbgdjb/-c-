package com.redpanda.cppide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redpanda.cppide.debugger.Breakpoint
import com.redpanda.cppide.debugger.DebugLocation
import com.redpanda.cppide.debugger.DebugPhase
import com.redpanda.cppide.debugger.StackFrame
import com.redpanda.cppide.debugger.Variable

/**
 * 调试面板：状态栏 + 调试工具栏 + 断点/变量/调用栈三个标签页。
 * 手机端做成紧凑面板，可以直接关闭。
 */
@Composable
fun DebugPanel(
    phase: DebugPhase,
    location: DebugLocation?,
    breakpoints: List<Breakpoint>,
    variables: List<Variable>,
    stack: List<StackFrame>,
    onStart: () -> Unit,
    onContinue: () -> Unit,
    onNext: () -> Unit,
    onStepIn: () -> Unit,
    onStepOut: () -> Unit,
    onStop: () -> Unit,
    onDeleteBreakpoint: (Breakpoint) -> Unit,
    onLocationClick: ((DebugLocation) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val busy = phase == DebugPhase.RUNNING

    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            // ── 状态栏 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (phase) {
                        DebugPhase.IDLE -> "未调试"
                        DebugPhase.RUNNING -> "运行中…"
                        DebugPhase.STOPPED -> "已暂停"
                        DebugPhase.EXITED -> "已结束"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (phase) {
                        DebugPhase.STOPPED -> MaterialTheme.colorScheme.primary
                        DebugPhase.EXITED -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                if (location != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${location.file.substringAfterLast('/')}:${location.line}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = if (onLocationClick != null) {
                            Modifier.clickable { onLocationClick(location) }
                        } else Modifier
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.Close,
                        "关闭调试面板",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ── 调试工具栏 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DbgIcon(Icons.Filled.BugReport, "开始调试", busy) { onStart() }
                DbgIcon(Icons.Filled.PlayArrow, "继续", busy) { onContinue() }
                DbgIcon(Icons.Filled.SkipNext, "步过", busy) { onNext() }
                DbgIcon(Icons.Filled.ArrowDownward, "步入", busy) { onStepIn() }
                DbgIcon(Icons.Filled.ArrowUpward, "步出", busy) { onStepOut() }
                DbgIcon(Icons.Filled.Stop, "停止", busy) { onStop() }
                Spacer(Modifier.weight(1f))
                Text(
                    "断点 ${breakpoints.size} 个",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── 标签页 ──
            TabRow(selectedTabIndex = tab, modifier = Modifier.height(36.dp)) {
                listOf("断点", "变量", "调用栈").forEachIndexed { i, t ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(t, fontSize = 12.sp) },
                        modifier = Modifier.height(36.dp)
                    )
                }
            }

            // ── 内容 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                when (tab) {
                    0 -> BreakpointList(breakpoints, onDeleteBreakpoint)
                    1 -> VariableList(variables)
                    2 -> StackList(stack, onLocationClick)
                }
            }
        }
    }
}

@Composable
private fun DbgIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BreakpointList(
    breakpoints: List<Breakpoint>,
    onDelete: (Breakpoint) -> Unit
) {
    if (breakpoints.isEmpty()) {
        EmptyHint("还没有断点\n在编辑时点击工具栏的「断点」按钮在当前行添加")
        return
    }
    LazyColumn {
        items(breakpoints) { bp ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "●",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(bp.file.substringAfterLast('/'), fontSize = 13.sp)
                    Text(
                        "第 ${bp.line} 行",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onDelete(bp) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Filled.Delete, "删除", modifier = Modifier.size(16.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun VariableList(variables: List<Variable>) {
    if (variables.isEmpty()) {
        EmptyHint("没有变量\n先开始调试并暂停，或走到某个栈帧里")
        return
    }
    LazyColumn {
        items(variables) { v ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(v.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        v.type,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "= ${v.value}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun StackList(stack: List<StackFrame>, onLocationClick: ((DebugLocation) -> Unit)?) {
    if (stack.isEmpty()) {
        EmptyHint("调用栈为空\n程序未在断点上暂停时无调用栈")
        return
    }
    LazyColumn {
        items(stack) { f ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = onLocationClick != null && f.line > 0
                    ) {
                        if (onLocationClick != null && f.line > 0) {
                            onLocationClick(DebugLocation(f.file, f.line))
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    "#${f.index}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(f.function, fontSize = 13.sp)
                    if (f.line > 0) {
                        Text(
                            "${f.file.substringAfterLast('/')}:${f.line}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
