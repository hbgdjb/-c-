package com.redpanda.cppide.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import java.io.File

/** 一条 TODO / FIXME 注释 */
data class TodoItem(
    val file: String,
    /** 1 基行号 */
    val line: Int,
    val text: String
)

/** 一个书签 */
data class BookmarkItem(
    val file: String,
    /** 1 基行号 */
    val line: Int
)

/**
 * 「任务与书签」对话框：TODO/FIXME 扫描结果 + 手动书签，点条目直接跳转。
 */
@Composable
fun TasksDialog(
    todos: List<TodoItem>,
    bookmarks: List<BookmarkItem>,
    onJump: (String, Int) -> Unit,
    onAddBookmark: () -> Unit,
    onRemoveBookmark: (BookmarkItem) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("任务与书签", fontSize = 16.sp) },
        text = {
            Column {
                TabRow(selectedTabIndex = tab, modifier = Modifier.height(36.dp)) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = { Text("TODO (${todos.size})", fontSize = 12.sp) },
                        modifier = Modifier.height(36.dp)
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text("书签 (${bookmarks.size})", fontSize = 12.sp) },
                        modifier = Modifier.height(36.dp)
                    )
                }

                if (tab == 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onAddBookmark) {
                            Icon(Icons.Filled.Add, "添加", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("在当前行加书签", fontSize = 12.sp)
                        }
                    }
                }

                val maxH = 360.dp
                when (tab) {
                    0 -> if (todos.isEmpty()) {
                        EmptyHint(
                            "没有找到 TODO / FIXME 注释\n" +
                                "在代码里写 // TODO: 待办 就会被收集到这里",
                            maxH
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = maxH)) {
                            items(todos.size) { i ->
                                val t = todos[i]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onJump(t.file, t.line) }
                                        .padding(vertical = 7.dp, horizontal = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            t.text,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 2
                                        )
                                        Text(
                                            "${File(t.file).name} · 第 ${t.line} 行",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                    else -> if (bookmarks.isEmpty()) {
                        EmptyHint(
                            "还没有书签\n" +
                                "点上方「在当前行加书签」，之后可从列表快速跳回",
                            maxH
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = maxH)) {
                            items(bookmarks.size) { i ->
                                val b = bookmarks[i]
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { onJump(b.file, b.line) }
                                            .padding(vertical = 8.dp, horizontal = 2.dp)
                                    ) {
                                        Text(
                                            File(b.file).name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        Text(
                                            "第 ${b.line} 行",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { onRemoveBookmark(b) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            "删除书签",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun EmptyHint(text: String, maxHeight: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp, max = maxHeight)
            .padding(vertical = 10.dp)
    )
}
