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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redpanda.cppide.workspace.StorageManager
import java.io.File

private data class TreeRow(val file: File, val depth: Int)

/** 把目录树按展开状态压平成一维列表，便于用 LazyColumn 渲染 */
private fun flatten(root: File, expanded: Set<String>, depth: Int = 0): List<TreeRow> {
    val out = mutableListOf<TreeRow>()
    val children = root.listFiles()?.sortedWith(
        compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
    ) ?: return out
    for (c in children) {
        if (c.name.startsWith(".")) continue
        out += TreeRow(c, depth)
        if (c.isDirectory && expanded.contains(c.absolutePath)) {
            out += flatten(c, expanded, depth + 1)
        }
    }
    return out
}

/**
 * 文件树面板（放在左侧抽屉里）。
 * 支持展开目录、新建文件/文件夹、重命名、删除。
 */
@Composable
fun FileTreePanel(
    root: File,
    currentFile: File?,
    onOpen: (File) -> Unit,
    onChanged: () -> Unit
) {
    val expanded = remember { mutableStateListOf<String>() }
    var refreshKey by remember { mutableStateOf(0) }

    var dialog by remember { mutableStateOf<DialogKind?>(null) }
    var targetDir by remember { mutableStateOf(root) }

    val rows = remember(refreshKey, expanded.size, expanded.toList()) {
        flatten(root, expanded.toSet())
    }

    fun refresh() {
        refreshKey++
        onChanged()
    }

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── 头部：标题 + 新建操作 ──
        Surface(tonalElevation = 2.dp) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("我的代码", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        targetDir = root
                        dialog = DialogKind.NewFile
                    }) { Icon(Icons.Filled.NoteAdd, "新建文件", modifier = Modifier.size(20.dp)) }
                    IconButton(onClick = {
                        targetDir = root
                        dialog = DialogKind.NewDir
                    }) { Icon(Icons.Filled.CreateNewFolder, "新建文件夹", modifier = Modifier.size(20.dp)) }
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Filled.Refresh, "刷新", modifier = Modifier.size(20.dp))
                    }
                }
                Text(
                    StorageManager.format(dirSize(root)) + " · " + root.absolutePath,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
                HorizontalDivider()
            }
        }

        if (rows.isEmpty()) {
            Text(
                "还没有文件，点右上角新建一个",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }

        LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
            items(rows) { row ->
                FileRowItem(
                    row = row,
                    isExpanded = expanded.contains(row.file.absolutePath),
                    isCurrent = currentFile?.absolutePath == row.file.absolutePath,
                    onToggle = {
                        if (expanded.contains(row.file.absolutePath)) expanded.remove(row.file.absolutePath)
                        else expanded.add(row.file.absolutePath)
                    },
                    onOpen = { onOpen(row.file) },
                    onNewFileHere = {
                        targetDir = if (row.file.isDirectory) row.file else row.file.parentFile ?: root
                        dialog = DialogKind.NewFile
                    },
                    onRename = { dialog = DialogKind.Rename(row.file) },
                    onDelete = { dialog = DialogKind.Delete(row.file) }
                )
            }
        }
    }

    // ── 各种小对话框 ──
    when (val d = dialog) {
        DialogKind.NewFile -> NameInputDialog(
            title = "新建文件",
            initial = "main.cpp",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                val f = File(targetDir, name)
                if (!f.exists()) {
                    f.parentFile?.mkdirs()
                    f.writeText(if (name.endsWith(".c")) "#include <stdio.h>\n\nint main() {\n    \n    return 0;\n}\n"
                    else "#include <iostream>\n\nusing namespace std;\n\nint main() {\n    \n    return 0;\n}\n")
                }
                dialog = null
                refresh()
                onOpen(f)
            }
        )
        DialogKind.NewDir -> NameInputDialog(
            title = "新建文件夹",
            initial = "newfolder",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                File(targetDir, name).mkdirs()
                dialog = null
                refresh()
            }
        )
        is DialogKind.Rename -> NameInputDialog(
            title = "重命名",
            initial = d.file.name,
            onDismiss = { dialog = null },
            onConfirm = { name ->
                val dst = File(d.file.parentFile, name)
                if (!dst.exists()) d.file.renameTo(dst)
                dialog = null
                refresh()
            }
        )
        is DialogKind.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("删除确认") },
            text = { Text("确定删除「${d.file.name}」吗？${if (d.file.isDirectory) "（包含其中的所有内容）" else ""}") },
            confirmButton = {
                TextButton(onClick = {
                    d.file.deleteRecursively()
                    dialog = null
                    refresh()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } }
        )
        null -> Unit
    }
}

@Composable
private fun FileRowItem(
    row: TreeRow,
    isExpanded: Boolean,
    isCurrent: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onNewFileHere: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val f = row.file

    Surface(
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (f.isDirectory) onToggle() else onOpen()
                }
                .padding(start = (12 + row.depth * 16).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    f.isDirectory && isExpanded -> Icons.Filled.FolderOpen
                    f.isDirectory -> Icons.Filled.Folder
                    else -> Icons.Filled.InsertDriveFile
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (f.isDirectory) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(f.name, fontSize = 14.sp)
                if (!f.isDirectory) {
                    Text(
                        StorageManager.format(f.length()),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.MoreVert, "更多", modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("新建文件", fontSize = 14.sp) },
                        leadingIcon = { Icon(Icons.Filled.NoteAdd, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menu = false; onNewFileHere() }
                    )
                    DropdownMenuItem(
                        text = { Text("重命名", fontSize = 14.sp) },
                        leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menu = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", fontSize = 14.sp) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("名称") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private sealed interface DialogKind {
    data object NewFile : DialogKind
    data object NewDir : DialogKind
    data class Rename(val file: File) : DialogKind
    data class Delete(val file: File) : DialogKind
}

private fun dirSize(f: File): Long {
    if (!f.exists()) return 0
    if (f.isFile) return f.length()
    var sum = 0L
    val stack = ArrayDeque<File>()
    stack.addLast(f)
    while (stack.isNotEmpty()) {
        val cur = stack.removeLast()
        for (c in cur.listFiles() ?: continue) {
            if (c.isDirectory) stack.addLast(c) else sum += c.length()
        }
    }
    return sum
}
