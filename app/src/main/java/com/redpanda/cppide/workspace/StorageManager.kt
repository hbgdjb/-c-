package com.redpanda.cppide.workspace

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * 存储占用统计与清理。
 *
 * 手机上空间敏感，这里把占用拆成几块，让用户能看清、能清理。
 */
class StorageManager(private val context: Context) {

    data class Item(val name: String, val bytes: Long, val canClear: Boolean, val hint: String)

    fun items(): List<Item> = listOf(
        Item("内置编译器", sizeOf(File(context.filesDir, "ndk")), false, "解压后的 clang 工具链，卸载才清理"),
        Item("运行时库", sizeOf(File(context.filesDir, "libs")), false, "libc++ 运行时，很小"),
        Item("我的代码", sizeOf(File(context.filesDir, "work")), false, "你的源文件，请勿清理"),
        Item("编译产物", sizeOf(File(context.filesDir, "build")), true, "可随时清理，下次编译会重新生成"),
        Item("临时文件", sizeOf(context.cacheDir), true, "编译器产生的中间文件，可清理")
    )

    fun total(): Long = items().sumOf { it.bytes }

    /** 清理编译产物，返回释放的字节数 */
    fun clearBuild(): Long {
        val f = File(context.filesDir, "build")
        val n = sizeOf(f)
        f.deleteRecursively()
        f.mkdirs()
        return n
    }

    /** 清理临时文件 */
    fun clearCache(): Long {
        val n = sizeOf(context.cacheDir)
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        return n
    }

    /** 清理编译器产生的多余文件（保留可执行必需部分） */
    fun clearTmpInCache(): Long {
        val t = File(context.cacheDir, "tmp")
        val n = sizeOf(t)
        t.deleteRecursively()
        t.mkdirs()
        return n
    }

    private fun sizeOf(f: File): Long {
        if (!f.exists()) return 0
        if (f.isFile) return f.length()
        var sum = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(f)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.addLast(c) else sum += c.length()
            }
        }
        return sum
    }

    companion object {
        fun format(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
            return String.format(Locale.US, "%.2f GB", mb / 1024.0)
        }
    }
}
