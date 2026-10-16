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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
    var helpPage by remember { mutableStateOf<HelpPage?>(null) }

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
                    selected = stdCpp,
                    onSelect = { stdCpp = it },
                    onHelp = { helpPage = HelpPage.CPP_STD },
                    hintFor = mapOf(
                        "c++11" to "兼容性最好，旧 OJ 常用",
                        "c++14" to "泛型 lambda、auto 返回类型",
                        "c++17" to "结构化绑定 / if constexpr —— 推荐",
                        "c++20" to "概念 / 协程，较新，部分环境支持不全"
                    )
                )
                CompactChips(
                    "C",
                    listOf("c11" to "11", "c17" to "17"),
                    selected = stdC,
                    onSelect = { stdC = it },
                    onHelp = { helpPage = HelpPage.C_STD },
                    hintFor = mapOf(
                        "c11" to "兼容性好",
                        "c17" to "c11 的缺陷修复版 —— 推荐"
                    )
                )
                CompactChips(
                    "优化",
                    listOf("-O0" to "0", "-O1" to "1", "-O2" to "2", "-Os" to "s"),
                    selected = opt,
                    onSelect = { opt = it },
                    onHelp = { helpPage = HelpPage.OPTIMIZE },
                    hintFor = mapOf(
                        "-O0" to "编译最快、最易调试（变量值准确）",
                        "-O1" to "轻量优化，编译较快",
                        "-O2" to "运行速度明显提升，编译稍慢 —— 推荐",
                        "-Os" to "优化体积，适合存储紧张"
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

    // ── 选项详细说明页（点设置项左边的标签进入）──
    helpPage?.let { page ->
        val title: String
        val entries: List<OptEntry>
        val current: String
        when (page) {
            HelpPage.CPP_STD -> {
                title = "C++ 标准"
                current = stdCpp
                entries = listOf(
                    OptEntry(
                        "c++11", "c++11", "兼容性最好，旧 OJ 常用",
                        "第一个现代化标准，引入 auto、范围 for、智能指针、右值引用。\n" +
                            "很多旧 OJ 的编译器默认就是这个。代码用了新特性编译不过时，可以先切回它。"
                    ),
                    OptEntry(
                        "c++14", "c++14", "泛型 lambda、auto 返回类型",
                        "c++11 的小修小补，支持泛型 lambda 和 auto 返回值推导。\n" +
                            "日常手感和 c++11 差别不大，除非明确用到新特性，否则不必专门选。"
                    ),
                    OptEntry(
                        "c++17", "c++17", "结构化绑定 / if constexpr —— 推荐",
                        "目前最通用的现代标准。支持结构化绑定 auto [a, b] = pair;、" +
                            "if constexpr、内联变量、std::optional、std::string_view。\n" +
                            "绝大多数 OJ 和现行教材都支持。\n\n👉 不确定选哪个就选它。"
                    ),
                    OptEntry(
                        "c++20", "c++20", "概念 / 协程，较新",
                        "最新大版本，引入概念(concepts)、协程、ranges、三路比较。\n" +
                            "但部分 OJ 编译器支持不完整，且编译明显更慢。除非明确需要，否则不建议。"
                    )
                )
            }
            HelpPage.C_STD -> {
                title = "C 标准"
                current = stdC
                entries = listOf(
                    OptEntry(
                        "c11", "c11", "兼容性好",
                        "C 语言 2011 版，支持 _Generic、匿名结构体、多线程。\n对老代码兼容性最好。"
                    ),
                    OptEntry(
                        "c17", "c17", "c11 的缺陷修复版 —— 推荐",
                        "C 语言 2018 版（原计划叫 c18），只修复了 c11 的一些缺陷，没有引入新特性。\n\n" +
                            "👉 日常写 C 选它即可，兼容性和 c11 一样好。"
                    )
                )
            }
            HelpPage.OPTIMIZE -> {
                title = "优化级别"
                current = opt
                entries = listOf(
                    OptEntry(
                        "-O0", "-O0", "编译最快、最易调试",
                        "不做任何优化。编译速度最快，生成的代码最容易理解，\n" +
                            "调试时变量值完全准确（不会显示 optimized out）。\n\n👉 写作业、调代码阶段推荐。"
                    ),
                    OptEntry(
                        "-O1", "-O1", "轻量优化，编译较快",
                        "做基础优化：去掉无用代码、简单内联、合并重复计算。\n" +
                            "编译时间和运行效果都比较平衡，日常使用也可以。"
                    ),
                    OptEntry(
                        "-O2", "-O2", "运行速度明显提升 —— 推荐",
                        "启用大部分优化：循环展开、函数内联、自动向量化等。\n" +
                            "计算量大的程序通常有 2～5 倍提速，代价是编译慢一点、可执行文件大一点。\n" +
                            "这是大多数 OJ 和竞赛的默认档。\n\n👉 追求程序跑得快就选它。"
                    ),
                    OptEntry(
                        "-Os", "-Os", "优化体积，适合存储紧张",
                        "在 -O2 的基础上优先减小生成文件的体积，可能牺牲一点运行速度。\n" +
                            "只在存储空间紧张时才需要，日常很少用到。"
                    )
                )
            }
        }
        OptionHelpDialog(
            title = title,
            entries = entries,
            current = current,
            onSelect = { v ->
                when (page) {
                    HelpPage.CPP_STD -> stdCpp = v
                    HelpPage.C_STD -> stdC = v
                    HelpPage.OPTIMIZE -> opt = v
                }
                helpPage = null
            },
            onDismiss = { helpPage = null }
        )
    }
}

/** 一个可选选项的详细说明 */
private data class OptEntry(
    val value: String,
    val label: String,
    val shortDesc: String,
    val longDesc: String
)

private enum class HelpPage { CPP_STD, C_STD, OPTIMIZE }

/** 选项详细说明页：列出所有选项的完整解释，点选即应用 */
@Composable
private fun OptionHelpDialog(
    title: String,
    entries: List<OptEntry>,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 17.sp) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 430.dp)) {
                items(entries.size) { i ->
                    val e = entries[i]
                    val selected = e.value == current
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onSelect(e.value) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    e.label,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                if (selected) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "当前使用",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                e.shortDesc,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Text(
                                e.longDesc,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
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
    onHelp: (() -> Unit)? = null,
    hintFor: Map<String, String> = emptyMap()
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .width(44.dp)
                    .then(
                        if (onHelp != null) {
                            Modifier.clickable { onHelp() }
                        } else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (onHelp != null) {
                    Text(
                        " ?",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
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
                modifier = Modifier.padding(start = 44.dp, top = 1.dp)
            )
        }
    }
}
