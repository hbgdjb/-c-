package com.redpanda.cppide.compiler

import java.io.File

/** 编译选项 */
data class CompileOptions(
    /** C 标准，如 c11 / c17 */
    val stdC: String = "c17",
    /** C++ 标准，如 c++11 / c++14 / c++17 / c++20 */
    val stdCpp: String = "c++17",
    /** 优化级别：-O0 / -O1 / -O2 / -Os */
    val optimize: String = "-O2",
    /** 是否开启 -Wall */
    val warnings: Boolean = true,
    /** 用户自定义的附加参数 */
    val extraArgs: List<String> = emptyList(),
    /** 编译范围：true = 当前文件夹内同族源文件一起编译 */
    val multiFile: Boolean = false
)

/** 一条编译诊断（错误/警告） */
data class Diagnostic(
    val file: String,
    val line: Int,
    val column: Int,
    val severity: Severity,
    val message: String
) {
    enum class Severity { ERROR, WARNING, NOTE }
}

/** 编译结果 */
data class CompileResult(
    val exitCode: Int,
    val outputFile: File?,
    val diagnostics: List<Diagnostic>
) {
    val success: Boolean get() = exitCode == 0 && outputFile != null
}

/**
 * 调用内置 clang 编译源文件（支持单文件与多文件）。
 *
 * 多文件时一次性把同一语言族的所有源文件交给 clang 编译并链接，
 * 由 clang 自己处理编译与链接，简单可靠。
 */
class Compiler(private val toolchain: ToolchainManager) {

    private val diagRegex =
        Regex("""^(.*?):(\d+):(\d+):\s*(error|warning|note):\s*(.*)$""")

    /** 是否为 C 源文件（其余按 C++ 处理） */
    fun isCSource(f: File): Boolean = f.extension.equals("c", ignoreCase = true)

    /**
     * 按参考文件的语言族筛选源文件。
     * 当前文件是 .c 就只编译 .c；否则只编译 .cpp/.cc/.cxx。
     * 避免 C 与 C++ 混编带来的 -std 冲突。
     */
    fun filterFamily(sources: List<File>, reference: File): List<File> {
        val cMode = isCSource(reference)
        return sources.filter { isCSource(it) == cMode }
    }

    /** 生成编译命令（sources 可含多个文件） */
    fun buildCommand(sources: List<File>, output: File, options: CompileOptions): List<String> {
        val cMode = sources.isNotEmpty() && sources.all { isCSource(it) }
        val driver = if (cMode) toolchain.clang() else toolchain.clangxx()

        val args = mutableListOf<String>()
        args += driver.absolutePath
        args += "--target=aarch64-linux-android24"
        args += "--sysroot=${toolchain.sysroot().absolutePath}"
        args += "-resource-dir=${toolchain.resourceDir().absolutePath}"
        args += "-fuse-ld=lld"
        if (!cMode) args += "-static-libstdc++"
        args += "-std=${if (cMode) options.stdC else options.stdCpp}"
        args += options.optimize
        if (options.warnings) args += "-Wall"
        args += options.extraArgs
        args += sources.map { it.absolutePath }
        args += "-o"
        args += output.absolutePath
        return args
    }

    /** 单文件编译（便捷重载） */
    fun compile(
        source: File,
        output: File,
        options: CompileOptions,
        onLine: (String) -> Unit
    ): CompileResult = compile(listOf(source), output, options, onLine)

    /**
     * 编译一个或多个源文件并链接。
     * onLine 会在后台线程被回调，用于实时输出日志。
     */
    fun compile(
        sources: List<File>,
        output: File,
        options: CompileOptions,
        onLine: (String) -> Unit
    ): CompileResult {
        if (sources.isEmpty()) {
            onLine("没有可编译的源文件")
            return CompileResult(-1, null, emptyList())
        }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val cmd = buildCommand(sources, output, options)
        onLine("$ " + cmd.joinToString(" ") { shellQuote(it) })

        val workDir = sources.first().parentFile ?: toolchain.ndkDir
        val process = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
            .apply { environment().putAll(toolchain.environment()) }
            .start()

        val lines = mutableListOf<String>()
        process.inputStream.bufferedReader().useLines { seq ->
            seq.forEach { line ->
                lines += line
                onLine(line)
            }
        }
        val code = process.waitFor()

        val diagnostics = lines.mapNotNull { parseDiagnostic(it) }
        return CompileResult(
            exitCode = code,
            outputFile = if (code == 0 && output.exists())
                output.also { it.setExecutable(true, false) } else null,
            diagnostics = diagnostics
        )
    }

    fun parseDiagnostic(line: String): Diagnostic? {
        val m = diagRegex.find(line) ?: return null
        val (file, l, c, sev, msg) = m.destructured
        val severity = when (sev) {
            "error" -> Diagnostic.Severity.ERROR
            "warning" -> Diagnostic.Severity.WARNING
            else -> Diagnostic.Severity.NOTE
        }
        return Diagnostic(file, l.toIntOrNull() ?: 1, c.toIntOrNull() ?: 1, severity, msg)
    }

    private fun shellQuote(s: String): String =
        if (s.any { it.isWhitespace() }) "\"$s\"" else s
}
