package com.redpanda.cppide.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redpanda.cppide.compiler.CompileOptions
import com.redpanda.cppide.compiler.Compiler
import com.redpanda.cppide.compiler.ProgramRunner
import com.redpanda.cppide.compiler.ToolchainManager
import com.redpanda.cppide.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val HELLO_CPP = """#include <iostream>
#include <vector>

int main() {
    std::vector<int> v{1, 2, 3};
    int sum = 0;
    for (int x : v) sum += x;
    std::cout << "小熊猫C++ 自检通过，1+2+3=" << sum << std::endl;
    return 0;
}
"""

private const val STATE_IDLE = 0
private const val STATE_RUNNING = 1
private const val STATE_OK = 2
private const val STATE_FAIL = 3

private data class CheckStep(
    val title: String,
    val state: Int = STATE_IDLE,
    val output: String = ""
)

/**
 * 环境自检：确认内置 clang 工具链能在本机运行。
 * 四步：工具链是否解压 → clang++ --version → 编译 Hello World → 运行产物。
 */
@Composable
fun SelfCheckDialog(
    toolchain: ToolchainManager,
    compiler: Compiler,
    runner: ProgramRunner,
    workspace: Workspace,
    onDismiss: () -> Unit
) {
    val steps = remember {
        mutableStateListOf(
            CheckStep("① 工具链是否已解压"),
            CheckStep("② 执行 clang++ --version"),
            CheckStep("③ 编译 Hello World"),
            CheckStep("④ 运行编译产物"),
            CheckStep("⑤ 执行 lldb --version（调试器）")
        )
    }
    var running by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun setStep(index: Int, state: Int, output: String) {
        if (index in steps.indices) {
            steps[index] = steps[index].copy(state = state, output = output)
        }
    }

    fun startCheck() {
        started = true
        running = true
        for (i in steps.indices) {
            steps[i] = steps[i].copy(state = STATE_IDLE, output = "")
        }
        scope.launch {
            // ① 工具链
            setStep(0, STATE_RUNNING, "")
            val installed = withContext(Dispatchers.IO) { toolchain.isInstalled() }
            if (!installed) {
                setStep(
                    0, STATE_FAIL,
                    "工具链尚未安装完成。\n首次启动需要解压约 46MB 压缩包（解压后约 237MB），\n请等待首页状态栏显示“编译器安装完成”后再试。"
                )
                running = false
                return@launch
            }
            setStep(
                0, STATE_OK,
                "clang 版本目录：${toolchain.versionName()}\n路径：${toolchain.ndkDir.absolutePath}"
            )

            // ② clang++ --version
            setStep(1, STATE_RUNNING, "")
            val (code, out) = withContext(Dispatchers.IO) { runClangVersion(toolchain) }
            setStep(1, if (code == 0) STATE_OK else STATE_FAIL, "退出码：$code\n$out")

            // ③ 编译
            setStep(2, STATE_RUNNING, "")
            val src = File(workspace.root, "selfcheck.cpp")
            withContext(Dispatchers.IO) { workspace.write(src, HELLO_CPP) }
            val exe = workspace.outputFor(src)
            val compileLog = StringBuilder()
            val compiled = withContext(Dispatchers.IO) {
                compiler.compile(src, exe, CompileOptions()) { line -> compileLog.appendLine(line) }
            }
            setStep(
                2,
                if (compiled.success) STATE_OK else STATE_FAIL,
                "源文件：${src.name}\n产物：${exe.absolutePath}\n\n$compileLog"
            )
            if (!compiled.success) {
                running = false
                return@launch
            }

            // ④ 运行
            setStep(3, STATE_RUNNING, "")
            val runLog = StringBuilder()
            val res = withContext(Dispatchers.IO) {
                runner.run(exe, workDir = workspace.root) { line -> runLog.appendLine(line) }
            }
            setStep(
                3,
                if (res.exitCode == 0) STATE_OK else STATE_FAIL,
                "退出码：${res.exitCode}${if (res.timedOut) "（超时）" else ""}\n\n$runLog"
            )

            // ⑤ 调试器
            setStep(4, STATE_RUNNING, "")
            val dbg = withContext(Dispatchers.IO) { runLldbVersion(toolchain) }
            setStep(4, if (dbg.first == 0) STATE_OK else STATE_FAIL, "退出码：${dbg.first}\n${dbg.second}")
            running = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("环境自检") },
        text = {
            Column {
                Text(
                    "检测内置编译器能否在本机运行。首次启动需先等工具链解压完成。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(steps.size) { i ->
                        val s = steps[i]
                        val mark = when (s.state) {
                            STATE_OK -> "✅"
                            STATE_FAIL -> "❌"
                            STATE_RUNNING -> "⏳"
                            else -> "○"
                        }
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(mark, fontSize = 14.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(s.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                            if (s.output.isNotBlank()) {
                                Text(
                                    s.output.trimEnd(),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 22.dp, top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { startCheck() }, enabled = !running) {
                Text(if (started) "重新自检" else "开始自检")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) { Text("关闭") }
        }
    )
}

/** 执行 clang++ --version，返回 (退出码, 合并输出) */
private fun runClangVersion(toolchain: ToolchainManager): Pair<Int, String> {
    return runCatching {
        val pb = ProcessBuilder(toolchain.clangxx().absolutePath, "--version")
            .redirectErrorStream(true)
        pb.environment().putAll(toolchain.environment())
        val p = pb.start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        val code = p.waitFor()
        code to text
    }.getOrElse { -1 to ("启动失败：${it.message}") }
}

/** 执行 lldb --version，返回 (退出码, 合并输出) */
private fun runLldbVersion(toolchain: ToolchainManager): Pair<Int, String> {
    val lldb = File(toolchain.ndkDir, "bin/lldb")
    if (!lldb.exists()) return -1 to "未找到 lldb：内置工具链不包含调试器"
    return runCatching {
        val pb = ProcessBuilder(lldb.absolutePath, "--version").redirectErrorStream(true)
        pb.environment().putAll(toolchain.environment())
        val p = pb.start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        val code = p.waitFor()
        code to text
    }.getOrElse { -1 to ("启动失败：${it.message}") }
}
