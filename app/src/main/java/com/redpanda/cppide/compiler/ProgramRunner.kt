package com.redpanda.cppide.compiler

import java.io.File
import java.util.concurrent.TimeUnit

/** 运行结果 */
data class RunResult(
    val exitCode: Int,
    val timedOut: Boolean
)

/**
 * 运行编译产物：支持标准输入、实时回显标准输出/错误、超时强制终止。
 */
class ProgramRunner(private val toolchain: ToolchainManager) {

    /**
     * @param exe      要运行的可执行文件
     * @param args     命令行参数
     * @param stdin    标准输入内容（可为空）
     * @param workDir  工作目录
     * @param timeoutSec 超时秒数
     * @param onLine   输出回调（stdout 与 stderr 合并）
     */
    fun run(
        exe: File,
        args: List<String> = emptyList(),
        stdin: String = "",
        workDir: File = exe.parentFile ?: toolchain.ndkDir,
        timeoutSec: Long = 30,
        onLine: (String) -> Unit
    ): RunResult {
        val cmd = mutableListOf(exe.absolutePath)
        cmd += args

        val process = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
            // 环境变量（含 LD_LIBRARY_PATH -> 私有 libs 目录）统一由 ToolchainManager 提供，
            // 此处不要再覆盖，否则兜底的 libc++_shared.so 会找不到。
            .apply { environment().putAll(toolchain.environment()) }
            .start()

        // 写入标准输入
        Thread {
            runCatching {
                process.outputStream.bufferedWriter().use { w ->
                    if (stdin.isNotEmpty()) w.write(stdin)
                    w.flush()
                }
            }
        }.start()

        // 实时读取输出
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { onLine(it) }
                }
            }
        }
        reader.start()

        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            onLine("")
            onLine("[程序运行超时（${timeoutSec}s），已强制终止]")
        }
        runCatching { reader.join(2000) }
        return RunResult(if (finished) process.exitValue() else -1, !finished)
    }
}
