package com.redpanda.cppide.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionHelper
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference

/**
 * 在 Sora 的 TextMateLanguage 基础上追加 C/C++ 关键字与代码片段的输入补全。
 *
 * 做法：用 Kotlin 的接口委托包装原 Language，只覆写 requireAutoComplete，
 * 先加入自己的候选项，再交给原语言做标识符补全。
 * 任何一步出错都安全降级，不影响正常编辑。
 */
class CppCompletionLanguage(private val delegate: Language) : Language by delegate {

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        runCatching {
            if (CompletionHelper.checkCancelled()) return@runCatching
            val prefix = CompletionHelper.computePrefix(content, position) { ch ->
                ch.isLetterOrDigit() || ch == '_'
            }
            if (prefix.isEmpty()) return@runCatching

            val lower = prefix.lowercase()
            // 关键字
            for (kw in KEYWORDS) {
                if (kw.length > lower.length && kw.startsWith(lower)) {
                    publisher.addItem(
                        SimpleCompletionItem(kw, "关键字", prefix.length, kw)
                    )
                }
            }
            // 代码片段（输入 fori / ife / main 等触发）
            for (s in SNIPPETS) {
                if (s.trigger.startsWith(lower)) {
                    publisher.addItem(
                        SimpleCompletionItem(s.trigger, s.desc, prefix.length, s.code)
                    )
                }
            }
        }

        // 再交给 TextMate 做标识符补全（文档里已出现过的词）
        runCatching { delegate.requireAutoComplete(content, position, publisher, extraArguments) }
    }

    private data class SnippetCompletion(
        val trigger: String,
        val desc: String,
        val code: String
    )

    companion object {
        private val KEYWORDS = listOf(
            "auto", "bool", "break", "case", "catch", "char", "class", "const", "constexpr",
            "continue", "default", "delete", "do", "double", "else", "enum", "explicit",
            "extern", "false", "float", "for", "friend", "goto", "if", "inline", "int",
            "long", "mutable", "namespace", "new", "nullptr", "operator", "private",
            "protected", "public", "register", "return", "short", "signed", "sizeof",
            "static", "struct", "switch", "template", "this", "throw", "true", "try",
            "typedef", "typename", "union", "unsigned", "using", "virtual", "void",
            "volatile", "while",
            "cin", "cout", "cerr", "endl", "std", "string", "vector", "map", "set",
            "pair", "queue", "stack", "sort", "reverse", "max", "min", "swap", "abs",
            "printf", "scanf", "malloc", "free", "fopen", "fclose", "getline",
            "size_t", "NULL", "include", "define", "pragma"
        )

        private val SNIPPETS = listOf(
            SnippetCompletion(
                "fori", "for 循环",
                "for (int i = 0; i < n; i++) {\n    \n}"
            ),
            SnippetCompletion(
                "forj", "嵌套 for 循环",
                "for (int i = 0; i < n; i++) {\n    for (int j = 0; j < m; j++) {\n        \n    }\n}"
            ),
            SnippetCompletion(
                "ife", "if-else 分支",
                "if (condition) {\n    \n} else {\n    \n}"
            ),
            SnippetCompletion(
                "whi", "while 循环",
                "while (condition) {\n    \n}"
            ),
            SnippetCompletion(
                "swi", "switch 分支",
                "switch (value) {\n    case 1:\n        \n        break;\n    default:\n        break;\n}"
            ),
            SnippetCompletion(
                "main", "主函数",
                "int main() {\n    \n    return 0;\n}"
            ),
            SnippetCompletion(
                "func", "函数定义",
                "int funcName(int a, int b) {\n    return 0;\n}"
            ),
            SnippetCompletion(
                "stru", "结构体",
                "struct Name {\n    int field;\n};"
            ),
            SnippetCompletion(
                "clas", "类定义",
                "class Name {\npublic:\n    Name() {}\n    ~Name() {}\n\nprivate:\n    int field;\n};"
            ),
            SnippetCompletion(
                "vec", "vector 声明",
                "vector<int> v;"
            ),
            SnippetCompletion(
                "vect", "vector 遍历",
                "for (auto& x : v) {\n    \n}"
            ),
            SnippetCompletion(
                "map", "map 声明",
                "map<string, int> m;"
            ),
            SnippetCompletion(
                "readn", "读入 n 个数",
                "int n;\ncin >> n;\nvector<int> a(n);\nfor (int i = 0; i < n; i++) cin >> a[i];"
            ),
            SnippetCompletion(
                "cout", "输出到控制台",
                "cout <<  << endl;"
            ),
            SnippetCompletion(
                "printf", "格式化输出",
                "printf(\"%d\\n\", n);"
            ),
            SnippetCompletion(
                "scanf", "格式化输入",
                "scanf(\"%d\", &n);"
            ),
            SnippetCompletion(
                "sort", "排序",
                "sort(v.begin(), v.end());"
            )
        )
    }
}
