package com.redpanda.cppide.workspace

import android.content.Context
import java.io.File

/**
 * 用户代码工作区，位于应用私有目录 files/work。
 */
class Workspace(private val context: Context) {

    val root: File = File(context.filesDir, "work").apply { mkdirs() }

    fun listSources(): List<File> =
        root.listFiles { f -> f.isFile && f.name.matches(Regex(".*\\.(c|cpp|cc|cxx|h|hpp)$")) }
            ?.sortedBy { it.name } ?: emptyList()

    fun listAll(): List<File> = root.listFiles()?.sortedBy { it.name } ?: emptyList()

    fun read(file: File): String =
        if (file.exists()) file.readText() else ""

    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    fun create(name: String, content: String): File {
        val f = File(root, name)
        write(f, content)
        return f
    }

    fun delete(file: File): Boolean = file.delete()

    /** 编译产物路径 */
    fun buildDir(): File = File(context.filesDir, "build").apply { mkdirs() }

    fun outputFor(source: File): File {
        val base = source.nameWithoutExtension
        return File(buildDir(), base)
    }

    /** 列出某目录下的所有 C/C++ 源文件（不递归） */
    fun sourcesIn(dir: File): List<File> =
        dir.listFiles { f ->
            f.isFile && f.name.matches(Regex(""".*\.(c|cpp|cc|cxx)$""", RegexOption.IGNORE_CASE))
        }?.sortedBy { it.name } ?: emptyList()

    /**
     * 生成 compile_commands.json，让 clangd 拿到准确的编译参数（sysroot / include）。
     *
     * 这是导航能不能正确解析 `iostream`、`cstdio` 的关键：
     * 优先用这个文件；只有当它被采纳时，fallbackFlags 才退化为兜底。
     */
    fun writeCompileCommands(sources: List<File>, ndkPath: String): File {
        val arr = org.json.JSONArray()
        for (f in sources) {
            arr.put(
                org.json.JSONObject().apply {
                    put("directory", root.absolutePath)
                    put(
                        "command",
                        "clang++ --target=aarch64-linux-android24" +
                            " --sysroot=$ndkPath/sysroot" +
                            " -resource-dir=$ndkPath/lib/clang/21" +
                            " -I$ndkPath/sysroot/usr/include" +
                            " -I$ndkPath/lib/clang/21/include" +
                            " -c ${f.absolutePath}"
                    )
                    put("file", f.absolutePath)
                }
            )
        }
        val out = File(root, "compile_commands.json")
        runCatching { out.writeText(arr.toString()) }
        return out
    }
}
