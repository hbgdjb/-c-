package com.redpanda.cppide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 一条代码片段 */
data class Snippet(
    val name: String,
    val desc: String,
    val category: String,
    val code: String
)

/**
 * C/C++ 常用代码片段。
 * 手机上敲代码慢，点一下插入整段模板比打字快得多。
 */
val CPP_SNIPPETS: List<Snippet> = listOf(
    // ── 基础结构 ──
    Snippet("main (C++)", "标准 C++ 主函数", "基础结构",
        "#include <iostream>\nusing namespace std;\n\nint main() {\n    \n    return 0;\n}\n"),
    Snippet("main (C)", "标准 C 主函数", "基础结构",
        "#include <stdio.h>\n\nint main() {\n    \n    return 0;\n}\n"),
    Snippet("函数", "定义一个函数", "基础结构",
        "int funcName(int a, int b) {\n    return 0;\n}\n"),
    Snippet("结构体", "struct 定义", "基础结构",
        "struct Name {\n    int field;\n};\n"),
    Snippet("类", "class 定义", "基础结构",
        "class Name {\npublic:\n    Name() {}\n    ~Name() {}\n\nprivate:\n    int field;\n};\n"),

    // ── 控制流 ──
    Snippet("for", "计数循环", "控制流",
        "for (int i = 0; i < n; i++) {\n    \n}\n"),
    Snippet("for 倒序", "倒序循环", "控制流",
        "for (int i = n - 1; i >= 0; i--) {\n    \n}\n"),
    Snippet("while", "当型循环", "控制流",
        "while (condition) {\n    \n}\n"),
    Snippet("do-while", "直到型循环", "控制流",
        "do {\n    \n} while (condition);\n"),
    Snippet("if-else", "条件分支", "控制流",
        "if (condition) {\n    \n} else {\n    \n}\n"),
    Snippet("switch", "多分支", "控制流",
        "switch (value) {\n    case 1:\n        \n        break;\n    default:\n        break;\n}\n"),

    // ── 输入输出 ──
    Snippet("cin/cout", "C++ 输入输出", "输入输出",
        "int n;\ncin >> n;\ncout << n << endl;\n"),
    Snippet("scanf/printf", "C 输入输出", "输入输出",
        "int n;\nscanf(\"%d\", &n);\nprintf(\"%d\\n\", n);\n"),
    Snippet("读 n 个数", "读入一维数组", "输入输出",
        "int n;\ncin >> n;\nvector<int> a(n);\nfor (int i = 0; i < n; i++) cin >> a[i];\n"),
    Snippet("读字符串", "读入一行字符串", "输入输出",
        "string s;\ngetline(cin, s);\n"),

    // ── 常用容器 ──
    Snippet("vector", "动态数组", "常用容器",
        "vector<int> v;\n"),
    Snippet("vector 遍历", "范围 for 遍历", "常用容器",
        "for (auto& x : v) {\n    \n}\n"),
    Snippet("map", "映射", "常用容器",
        "map<string, int> m;\n"),
    Snippet("set", "集合", "常用容器",
        "set<int> s;\n"),
    Snippet("二维数组", "固定大小二维数组", "常用容器",
        "int a[100][100];\n"),
    Snippet("排序", "升序排序", "常用容器",
        "sort(v.begin(), v.end());\n"),
    Snippet("排序(降序)", "降序排序", "常用容器",
        "sort(v.begin(), v.end(), greater<int>());\n"),

    // ── 文件与调试 ──
    Snippet("文件读取", "C 方式读文件", "文件与调试",
        "FILE* fp = fopen(\"in.txt\", \"r\");\nif (!fp) return 1;\n\nfclose(fp);\n"),
    Snippet("计时", "测量运行时间", "文件与调试",
        "clock_t t0 = clock();\n\nprintf(\"耗时 %.3f s\\n\", (double)(clock() - t0) / CLOCKS_PER_SEC);\n"),
)

/** 代码片段选择面板 */
@Composable
fun SnippetPanel(
    onDismiss: () -> Unit,
    onPick: (Snippet) -> Unit
) {
    val grouped = remember { CPP_SNIPPETS.groupBy { it.category } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("插入代码片段") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                grouped.forEach { (category, list) ->
                    item {
                        Text(
                            category,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(list.size) { i ->
                        val s = list[i]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(s) }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(s.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                s.desc,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                s.code.lineSequence().first().take(40),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
