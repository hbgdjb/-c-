package com.redpanda.cppide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redpanda.cppide.compiler.CompileOptions
import com.redpanda.cppide.workspace.StorageManager
import kotlin.math.roundToInt

/** 设置：编译 / 外观 / 存储 / 关于。同行紧凑排布，减少滚动。 */
@Composable
fun SettingsDialog(
    options: CompileOptions,
    themeMode: String,
    fontSize: Float,
    storage: StorageManager,
    onAbout: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (CompileOptions, String, Float) -> Unit
) {
    var stdCpp by remember { mutableStateOf(options.stdCpp) }
    var stdC by remember { mutableStateOf(options.stdC) }
    var opt by remember { mutableStateOf(options.optimize) }
    var warn by remember { mutableStateOf(options.warnings) }
    var multi by remember { mutableStateOf(options.multiFile) }
    var theme by remember { mutableStateOf(themeMode) }
    var font by remember { mutableFloatStateOf(fontSize) }
    var storageTick by remember { mutableStateOf(0) }

    val items = remember(storageTick) { storage.items() }
    val total = remember(storageTick) { storage.total() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置", fontSize = 17.sp) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ───────── 编译 ─────────
                SectionTitle("编译")
                CompactChips(
                    "范围",
                    listOf("single" to "仅当前文件", "multi" to "当前文件夹"),
                    if (multi) "multi" else "single",
                    onSelect = { multi = (it == "multi") }
                )
                MiniHint(
                    if (multi) "多文件：编译当前文件夹内所有同类型源文件并链接成一个程序"
                    else "单文件：只编译当前打开的这个文件"
                )
                CompactChips(
                    "C++",
                    listOf("c++11" to "11", "c++14" to "14", "c++17" to "17", "c++20" to "20"),
                    stdCpp.removePrefix("c++"),
                    onSelect = { stdCpp = "c++$it" },
                    hintFor = mapOf(
                        "11" to "兼容性最好，旧 OJ 常用",
                        "14" to "泛型 lambda、auto 返回类型",
                        "17" to "结构化绑定 / if constexpr —— 推荐",
                        "20" to "概念 / 协程，较新，部分环境支持不全"
                    )
                )
                CompactChips(
                    "C",
                    listOf("c11" to "11", "c17" to "17"),
                    stdC.removePrefix("c"),
                    onSelect = { stdC = "c$it" },
                    hintFor = mapOf(
                        "11" to "兼容性好",
                        "17" to "c11 的缺陷修复版 —— 推荐"
                    )
                )
                CompactChips(
                    "优化",
                    listOf("-O0" to "0", "-O1" to "1", "-O2" to "2", "-Os" to "s"),
                    opt.removePrefix("-O"),
                    onSelect = { opt = "-O$it" },
                    hintFor = mapOf(
                        "0" to "编译最快、最易调试（变量值准确）",
                        "1" to "轻量优化，编译较快",
                        "2" to "运行速度明显提升，编译稍慢 —— 推荐",
                        "s" to "优化体积，适合存储紧张"
                    )
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = warn, onCheckedChange = { warn = it })
                    Text("开启 -Wall 警告", fontSize = 12.sp)
                }
                MiniHint(
                    if (multi) "多文件：编译当前文件夹内所有同类型源文件并链接成一个程序"
                    else "单文件：只编译当前打开的这个文件"
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()

                // ───────── 外观 ─────────
                SectionTitle("外观")
                CompactChips(
                    "配色",
                    listOf("auto" to "跟随系统", "light" to "浅色", "dark" to "深色"),
                    theme,
                    onSelect = { theme = it }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("字号", fontSize = 12.sp, modifier = Modifier.width(36.dp))
                    Slider(
                        value = font,
                        onValueChange = { font = it },
                        valueRange = 11f..24f,
                        steps = 11,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${font.roundToInt()} sp", fontSize = 12.sp, modifier = Modifier.width(44.dp))
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()

                // ───────── 存储 ─────────
                SectionTitle("存储占用（${StorageManager.format(total)}）")
                for (it in items) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(it.name, fontSize = 12.sp, modifier = Modifier.width(60.dp))
                        MiniHint(it.hint, modifier = Modifier.weight(1f))
                        Text(
                            StorageManager.format(it.bytes),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        storage.clearBuild()
                        storage.clearTmpInCache()
                        storageTick++
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text("清理编译产物与临时文件", fontSize = 12.sp) }
                MiniHint("内置编译器与你的代码不会被清理")

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()

                // ───────── 关于 ─────────
                SectionTitle("关于")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAbout() }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("关于本应用", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "原作来源 / 开源许可 / 第三方组件",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    options.copy(
                        stdCpp = stdCpp,
                        stdC = stdC,
                        optimize = opt,
                        warnings = warn,
                        multiFile = multi
                    ),
                    theme,
                    font
                )
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SectionTitle(t: String) {
    Text(
        t,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
    )
}

@Composable
private fun MiniHint(t: String, modifier: Modifier = Modifier) {
    Text(
        t,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 1.dp)
    )
}

/**
 * 紧凑的一行设置：短标签 + 可横向滚动的芯片。
 * 比「标签一行 + 芯片一行」省一半高度，标签固定宽度保证对齐。
 */
@Composable
private fun CompactChips(
    label: String,
    values: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    hintFor: Map<String, String> = emptyMap()
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(36.dp)
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for ((v, text) in values) {
                    FilterChip(
                        selected = selected == v,
                        onClick = { onSelect(v) },
                        label = { Text(text, fontSize = 11.sp) }
                    )
                }
            }
        }
        val hint = hintFor[selected]
        if (hint != null) {
            Text(
                hint,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 36.dp, top = 1.dp)
            )
        }
    }
}
