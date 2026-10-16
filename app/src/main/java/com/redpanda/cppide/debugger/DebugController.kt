package com.redpanda.cppide.debugger

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.redpanda.cppide.compiler.CompileOptions
import com.redpanda.cppide.compiler.CompileResult
import com.redpanda.cppide.compiler.Compiler
import com.redpanda.cppide.compiler.ToolchainManager
import java.io.File

/** 断点 */
data class Breakpoint(val id: Int, val file: String, val line: Int)

/** 调用栈帧 */
data class StackFrame(
    val index: Int,
    val function: String,
    val file: String = "",
    val line: Int = 0
)

/** 变量 */
data class Variable(val name: String, val type: String, val value: String)

/** 调试位置 */
data class DebugLocation(val file: String, val line: Int)

/** 一次操作后的程序状态 */
data class StepResult(
    /** 是否停在断点上 */
    val atBreakpoint: Boolean,
    /** 程序是否已退出 */
    val exited: Boolean,
    /** 退出码（exited 为 true 时有效） */
    val exitCode: Int = 0,
    /** 当前停止位置 */
    val location: DebugLocation? = null,
    /** 原始输出（供调试用） */
    val raw: String = ""
)

enum class DebugPhase { IDLE, RUNNING, STOPPED, EXITED }

/**
 * 调试控制器：把 LLDB 命令封装成 UI 友好的操作，
 * 并解析文本输出成结构化数据。
 *
 * 所有解析都做了容错 —— 解析失败时 raw 字段仍有原始输出，
 * UI 可以展示原文，不会崩。
 */
class DebugController(
    private val toolchain: ToolchainManager,
    private val compiler: Compiler
) {

    val driver = LldbDriver(toolchain)

    private val breakpoints = mutableListOf<Breakpoint>()
    private var program: File? = null

    var phase by mutableStateOf(DebugPhase.IDLE)
        private set
    var lastLocation by mutableStateOf<DebugLocation?>(null)
        private set

    val currentBreakpoints: List<Breakpoint> get() = breakpoints.toList()

    private val breakpointRegex = Regex("""Breakpoint (\d+):""")
    private val frameRegex = Regex("""frame #(\d+):\s*(?:0x[0-9a-f]+)?\s*`?([^\s`]+)""")
    private val fileLineRegex = Regex("""at ([^\s:]+):(\d+)(?::(\d+))?""")
    private val varRegex = Regex("""^\(([^)]*)\)\s+([A-Za-z_]\w*)\s*=\s*(.*)$""")
    private val exitCodeRegex = Regex("""Process \d+ exited with status = (-?\d+)""")
    private val stopReasonRegex = Regex("""stop reason = (.+)""")

    /** 启动调试（会先用 -g 重新编译） */
    suspend fun start(
        source: File,
        sourceText: String,
        output: File,
        options: CompileOptions,
        onProgress: (String) -> Unit,
        onLiveOutput: (String) -> Unit
    ): CompileResult? {
        stop()

        onProgress("正在用 -g 重新编译（保留调试信息）…")
        val result = compiler.compile(
            listOf(source),
            output,
            options.copy(extraArgs = options.extraArgs + "-g", optimize = "-O0"),
            onProgress
        )
        if (!result.success) return result

        program = output
        onProgress("正在启动调试器…")
        val ok = driver.start(output, onLiveOutput)
        if (!ok) {
            onProgress("调试器启动失败（缺少 lldb 或 lldb-server）")
            return result
        }
        phase = DebugPhase.STOPPED
        lastLocation = null

        // 重建之前设置的断点（用 lldb 返回的真实 id）
        val saved = breakpoints.toList()
        breakpoints.clear()
        for (bp in saved) {
            val out = driver.send("breakpoint set --file ${quote(bp.file)} --line ${bp.line}")
            val id = breakpointRegex.find(out)?.groupValues?.get(1)?.toIntOrNull()
                ?: (breakpoints.size + 1)
            breakpoints += Breakpoint(id, bp.file, bp.line)
        }
        return result
    }

    /**
     * 设置断点。调试器未启动时先本地记录，
     * start() 时会统一下发给 lldb。
     */
    suspend fun setBreakpoint(file: String, line: Int): Breakpoint? {
        val existing = breakpoints.find { it.file == file && it.line == line }
        if (existing != null) return existing
        if (driver.isRunning()) {
            val out = driver.send("breakpoint set --file ${quote(file)} --line $line")
            val id = breakpointRegex.find(out)?.groupValues?.get(1)?.toIntOrNull()
            val bp = Breakpoint(id ?: (breakpoints.size + 1), file, line)
            breakpoints += bp
            return bp
        }
        val bp = Breakpoint(breakpoints.size + 1, file, line)
        breakpoints += bp
        return bp
    }

    /** 删除断点 */
    suspend fun deleteBreakpoint(file: String, line: Int): Boolean {
        val bp = breakpoints.find { it.file == file && it.line == line } ?: return false
        if (driver.isRunning()) {
            driver.send("breakpoint delete ${bp.id}")
        }
        breakpoints -= bp
        return true
    }

    fun toggleBreakpointAt(file: String, line: Int): Int {
        val existing = breakpoints.find { it.file == file && it.line == line }
        return if (existing != null) 0 else 1   // 0=删除 1=添加
    }

    fun hasBreakpoint(file: String, line: Int): Boolean =
        breakpoints.any { it.file == file && it.line == line }

    // ── 执行控制 ──

    suspend fun run(onProgress: (String) -> Unit): StepResult {
        val out = driver.send("run", timeoutMs = 180_000)
        return parseStop(out, onProgress)
    }

    suspend fun cont(onProgress: (String) -> Unit): StepResult {
        val out = driver.send("continue", timeoutMs = 180_000)
        return parseStop(out, onProgress)
    }

    suspend fun next(onProgress: (String) -> Unit): StepResult {
        val out = driver.send("next", timeoutMs = 60_000)
        return parseStop(out, onProgress)
    }

    suspend fun stepIn(onProgress: (String) -> Unit): StepResult {
        val out = driver.send("step", timeoutMs = 60_000)
        return parseStop(out, onProgress)
    }

    suspend fun stepOut(onProgress: (String) -> Unit): StepResult {
        val out = driver.send("finish", timeoutMs = 60_000)
        return parseStop(out, onProgress)
    }

    private suspend fun parseStop(raw: String, onProgress: (String) -> Unit): StepResult {
        val exited = exitCodeRegex.containsMatchIn(raw)
        if (exited) {
            val code = exitCodeRegex.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            phase = DebugPhase.EXITED
            lastLocation = null
            onProgress("程序已结束（退出码 $code）")
            return StepResult(
                atBreakpoint = false,
                exited = true,
                exitCode = code,
                raw = raw
            )
        }
        val reason = stopReasonRegex.find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val loc = parseCurrentLocation(raw)
        lastLocation = loc
        phase = DebugPhase.STOPPED
        onProgress(
            when {
                reason.startsWith("breakpoint") -> "停在断点：${loc?.file ?: ""}:${loc?.line ?: 0}"
                reason.startsWith("step") -> "单步：${loc?.file ?: ""}:${loc?.line ?: 0}"
                reason.isNotBlank() -> "停止：$reason"
                else -> ""
            }
        )
        return StepResult(
            atBreakpoint = reason.startsWith("breakpoint"),
            exited = false,
            location = loc,
            raw = raw
        )
    }

    /** 从 raw 输出里解析出 frame #0 的文件和行号 */
    fun parseCurrentLocation(raw: String): DebugLocation? {
        val firstFrameIdx = raw.indexOf("frame #0:")
        if (firstFrameIdx < 0) return null
        val lineEnd = raw.indexOf('\n', firstFrameIdx).let { if (it < 0) raw.length else it }
        val frameLine = raw.substring(firstFrameIdx, lineEnd)
        val m = fileLineRegex.find(frameLine) ?: return null
        val file = m.groupValues[1]
        val lineNo = m.groupValues[2].toIntOrNull() ?: return null
        if (lineNo <= 0) return null
        return DebugLocation(file, lineNo)
    }

    // ── 信息查询 ──

    suspend fun variables(): List<Variable> {
        val out = driver.send("frame variable")
        val list = mutableListOf<Variable>()
        for (line in out.split('\n')) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("(") && false) continue
            val m = varRegex.matchEntire(t) ?: continue
            val (type, name, value) = m.destructured
            if (name.isEmpty()) continue
            list += Variable(name, type.trim(), value.trim())
        }
        return list
    }

    suspend fun backtrace(): List<StackFrame> {
        val out = driver.send("bt")
        val list = mutableListOf<StackFrame>()
        for (line in out.split('\n')) {
            val t = line.trim()
            val m = frameRegex.find(t) ?: continue
            if (!t.startsWith("frame #")) continue
            val idx = m.groupValues[1].toIntOrNull() ?: continue
            val fn = m.groupValues[2]
            val fl = fileLineRegex.find(t)
            list += StackFrame(
                index = idx,
                function = fn,
                file = fl?.groupValues?.get(1) ?: "",
                line = fl?.groupValues?.get(2)?.toIntOrNull() ?: 0
            )
        }
        return list
    }

    /** 执行任意 lldb 命令（给控制台用） */
    suspend fun sendCommand(cmd: String): String = driver.send(cmd)

    fun stop() {
        driver.stop()
        phase = DebugPhase.IDLE
        lastLocation = null
    }

    private fun quote(s: String): String = "\"${s.replace("\"", "\\\"")}\""
}
