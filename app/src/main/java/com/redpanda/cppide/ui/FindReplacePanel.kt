package com.redpanda.cppide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 查找/替换浮层，覆盖在编辑器顶部。
 * 手机屏幕窄，所以做成两行紧凑布局，不占用编辑器主区域。
 */
@Composable
fun FindReplacePanel(
    matchInfo: String,
    caseInsensitive: Boolean,
    useRegex: Boolean,
    onSearch: (query: String, caseInsensitive: Boolean, useRegex: Boolean) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplace: (String) -> Unit,
    onReplaceAll: (String) -> Unit,
    onToggleCase: (Boolean) -> Unit,
    onToggleRegex: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var showReplace by remember { mutableStateOf(false) }

    fun runSearch(q: String = query) = onSearch(q, caseInsensitive, useRegex)

    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {

            // ── 第一行：查找 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        runSearch(it)
                    },
                    placeholder = { Text("查找", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(52.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
                if (matchInfo.isNotEmpty()) {
                    Text(
                        matchInfo,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                IconButton(onClick = onPrev, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, "上一个")
                }
                IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, "下一个")
                }
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, "关闭")
                }
            }

            // ── 第二行：选项 + 替换 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = { showReplace = !showReplace }) {
                    Text(if (showReplace) "收起替换" else "替换", fontSize = 12.sp)
                }
                TextButton(onClick = {
                    val v = !caseInsensitive
                    onToggleCase(v)
                    runSearch()
                }) {
                    Text(
                        "Aa",
                        fontSize = 12.sp,
                        color = if (caseInsensitive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = {
                    val v = !useRegex
                    onToggleRegex(v)
                    runSearch()
                }) {
                    Text(
                        ".*",
                        fontSize = 12.sp,
                        color = if (useRegex) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showReplace) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        placeholder = { Text("替换为", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).height(52.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    )
                    TextButton(onClick = { onReplace(replacement) }) { Text("替换", fontSize = 12.sp) }
                    TextButton(onClick = { onReplaceAll(replacement) }) { Text("全部", fontSize = 12.sp) }
                }
            }
        }
    }
}
