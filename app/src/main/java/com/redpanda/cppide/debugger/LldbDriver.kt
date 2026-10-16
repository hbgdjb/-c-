package com.redpanda.cppide.debugger

import com.redpanda.cppide.compiler.ToolchainManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.BufferedWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * LLDB 驱动：把内置的 aarch64 lldb 作为子进程跑起来，通过 stdin/stdout 驱动它。
 *
 * 交互方式：写入命令 → 读取输出，直到出现 `(lldb) ` 提示符（或超时）。
 * 提示符没有换行，所以逐字符读取检测。
 */
class LldbDriver(private val toolchain: ToolchainManager) {

    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private val lines = LinkedBlockingQueue<String>()

    @Volatile private var alive = false
    @Volatile private var sawPrompt = false

    companion object {
        const val PROMPT = "(lldb) "
        private const val PROMPT_MARKER = "\u0000PROMPT\u0000"
    }

    /** lldb 可执行文件（内置，aarch64 静态） */
    fun lldb(): File = File(toolchain.ndkDir, "bin/lldb")

    /** lldb-server 可执行文件 */
    fun lldbServer(): File = File(toolchain.ndkDir, "bin/lldb-server")

    fun isAvailable(): Boolean = lldb().exists() && lldbServer().exists()

    fun isRunning(): Boolean = alive

    /** 启动 lldb 并加载目标程序 */
    suspend fun start(
        program: File,
        onLiveOutput: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable() || alive) return@withContext false
        lldb().setExecutable(true, false)
        lldbServer().setExecutable(true, false)

        val env = toolchain.environment().toMutableMap()
        // 让 lldb 在工具链 bin 下找到 lldb-server
        env["PATH"] = "${toolchain.ndkDir.absolutePath}/bin:/system/bin:/system/xbin"

        val pb = ProcessBuilder(lldb().absolutePath, "--no-use-colors", program.absolutePath)
            .redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(env)

        val p = runCatching { pb.start() }.getOrNull() ?: return@withContext false
        proc = p
        writer = p.outputStream.bufferedWriter()
        lines.clear()
        sawPrompt = false
        alive = true

        safeSend("settings set use-color false")
        safeSend("settings set interpreter.prompt-on-quit false")

        Thread {
            val reader = p.inputStream.bufferedReader()
            val tail = StringBuilder()
            val current = StringBuilder()
            try {
                while (true) {
                    val c = reader.read()
                    if (c < 0) break
                    if (c == '\n'.code) {
                        val line = current.toString()
                        current.setLength(0)
                        if (line.isNotBlank()) {
                            runCatching { lines.put(line) }
                            runCatching { onLiveOutput(line) }
                        }
                    } else if (c == '\r'.code) {
                        // 忽略
                    } else {
                        current.append(c.toChar())
                        tail.append(c.toChar())
                        if (tail.length > PROMPT.length) tail.deleteCharAt(0)
                        if (tail.toString() == PROMPT) {
                            sawPrompt = true
                            current.setLength(0)
                            tail.setLength(0)
                            runCatching { lines.put(PROMPT_MARKER) }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                alive = false
                runCatching { lines.put(PROMPT_MARKER) }
            }
        }.also { it.isDaemon = true }.start()

        true
    }

    /** 发送命令并等待其输出完成 */
    suspend fun send(cmd: String, timeoutMs: Long = 60_000): String =
        withContext(Dispatchers.IO) { safeSend(cmd, timeoutMs) }

    private fun safeSend(cmd: String, timeoutMs: Long = 60_000): String {
        val w = writer ?: return ""
        if (proc == null) return ""
        lines.clear()
        sawPrompt = false
        return try {
            w.write(cmd)
            w.write("\n")
            w.flush()

            val deadline = System.currentTimeMillis() + timeoutMs
            val sb = StringBuilder()
            while (System.currentTimeMillis() < deadline) {
                val line = lines.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (line == PROMPT_MARKER) break
                sb.append(line).append('\n')
            }
            sb.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    /** 关闭 lldb（会中断目标进程） */
    fun stop() {
        runCatching { writer?.write("quit\n"); writer?.flush() }
        alive = false
        runCatching { proc?.destroyForcibly() }
        proc = null
        writer = null
    }
}
