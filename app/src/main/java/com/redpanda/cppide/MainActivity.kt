package com.redpanda.cppide

import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.redpanda.cppide.compiler.*
import com.redpanda.cppide.debugger.Breakpoint
import com.redpanda.cppide.debugger.DebugController
import com.redpanda.cppide.debugger.DebugPhase
import com.redpanda.cppide.debugger.StackFrame
import com.redpanda.cppide.debugger.Variable
import com.redpanda.cppide.editor.CppCompletionLanguage
import com.redpanda.cppide.editor.EditorActions
import com.redpanda.cppide.editor.drawBreakpointMarks
import com.redpanda.cppide.navigator.LspDiagnostic
import com.redpanda.cppide.navigator.NavLocation
import com.redpanda.cppide.navigator.NavigationController
import com.redpanda.cppide.navigator.TextEditRange
import com.redpanda.cppide.ui.*
import com.redpanda.cppide.workspace.StorageManager
import com.redpanda.cppide.workspace.FileSharing
import com.redpanda.cppide.workspace.TemplateManager
import com.redpanda.cppide.workspace.Workspace
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorFocusChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.util.IntPair
import kotlinx.coroutines.Job
import kotlin.math.abs
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 已打开的文件 */
class OpenFile(val file: File, var text: String) {
    var modified by mutableStateOf(false)
}

/** 匹配 // TODO / FIXME / XXX / HACK 注释（也覆盖块注释里的 * TODO） */
private val TODO_REGEX =
    Regex("""(//+|/\*+|^\s*\*)\s*(TODO|FIXME|XXX|HACK)\b""", RegexOption.IGNORE_CASE)

/** 两个路径是否指向同一个文件（安卓上 /data/data 与 /data/user/0 是同一目录的两种写法） */
private fun samePath(a: String, b: String): Boolean {
    if (a == b) return true
    val ca = runCatching { File(a).canonicalPath }.getOrNull() ?: a
    val cb = runCatching { File(b).canonicalPath }.getOrNull() ?: b
    return ca == cb
}

/** 取某个文件的断点行号，并转成 0 基（编辑器侧边图标用） */
private fun bpLinesOf(filePath: String, bps: List<Breakpoint>): Set<Int> =
    bps.asSequence().filter { samePath(it.file, filePath) }.map { it.line - 1 }.toSet()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppScreen(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(activity: ComponentActivity) {

    val toolchain = remember { ToolchainManager(activity) }
    val compiler = remember { Compiler(toolchain) }
    val runner = remember { ProgramRunner(toolchain) }
    val workspace = remember { Workspace(activity) }
    val templates = remember { TemplateManager(activity) }
    val storage = remember { StorageManager(activity) }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val openFiles = remember { mutableStateListOf<OpenFile>() }
    var activeIndex by remember { mutableIntStateOf(-1) }
    val console = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    var stdin by remember { mutableStateOf("") }
    var showTemplates by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSelfCheck by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showSymbolBar by remember { mutableStateOf(false) }
    var showSnippets by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var debugVars by remember { mutableStateOf<List<Variable>>(emptyList()) }
    var debugStack by remember { mutableStateOf<List<StackFrame>>(emptyList()) }
    var debugBusy by remember { mutableStateOf(false) }
    /** 调试命令控制台的输出历史 */
    val debugConsole = remember { mutableStateListOf<String>() }

    // ── 断点标记 ──
    /** 断点集合版本号：变化 +1，用于触发编辑器上的断点覆盖层重绘 */
    var bpVersion by remember { mutableIntStateOf(0) }

    // ── 任务与书签 / 自动换行 ──
    var showTasks by remember { mutableStateOf(false) }
    var todos by remember { mutableStateOf<List<TodoItem>>(emptyList()) }
    val bookmarks = remember { mutableStateListOf<BookmarkItem>() }
    var wordwrapOn by remember { mutableStateOf(false) }
    var navResults by remember { mutableStateOf<List<NavLocation>>(emptyList()) }
    var hoverInfo by remember { mutableStateOf<String?>(null) }
    var showBpDialog by remember { mutableStateOf(false) }
    var bpPendingFile by remember { mutableStateOf<String?>(null) }
    var bpPendingLine by remember { mutableStateOf<Int?>(null) }
    var bpCondition by remember { mutableStateOf("") }
    var bpIsEdit by remember { mutableStateOf(false) }
    /** 横屏右侧面板当前显示哪个（false=控制台, true=调试） */
    var sideIsDebug by remember { mutableStateOf(false) }

    // ── 实时诊断 / 引用 / 重命名 ──
    var diagnosticsOn by remember { mutableStateOf(true) }
    var diagJob by remember { mutableStateOf<Job?>(null) }
    /** 诊断链路的自检日志只打一次，避免刷屏 */
    var diagOkLogged by remember { mutableStateOf(false) }
    var diagMismatchLogged by remember { mutableStateOf(false) }
    /** 软键盘是否弹出（决定是否收起底部编译栏） */
    var imeVisible by remember { mutableStateOf(false) }
    /** 键盘检测是否真的生效过（用于判断要不要退回到「焦点」兜底） */
    var imeSignalWorks by remember { mutableStateOf(false) }
    /** 编辑器是否获得焦点 */
    var editorFocused by remember { mutableStateOf(false) }
    var navTitle by remember { mutableStateOf("跳转到定义") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renamePendingFile by remember { mutableStateOf<File?>(null) }
    var renamePendingLine by remember { mutableStateOf(0) }
    var renamePendingChar by remember { mutableStateOf(0) }
    var renameOldName by remember { mutableStateOf("") }
    var renameNewName by remember { mutableStateOf("") }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val debugger = remember { DebugController(toolchain, compiler) }
    val navigator = remember { NavigationController(toolchain, workspace) }

    var themeMode by remember { mutableStateOf("auto") }
    var fontSize by remember { mutableFloatStateOf(15f) }

    var findInfo by remember { mutableStateOf("") }
    var findCase by remember { mutableStateOf(false) }
    var findRegex by remember { mutableStateOf(false) }

    var options by remember { mutableStateOf(CompileOptions()) }
    val editorRef = remember { mutableStateOf<CodeEditor?>(null) }

    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    fun log(s: String) {
        console.add(s)
        if (console.size > 2000) console.removeRange(0, console.size - 2000)
    }

    fun openFile(f: File, content: String) {
        val idx = openFiles.indexOfFirst { it.file.absolutePath == f.absolutePath }
        if (idx >= 0) activeIndex = idx
        else {
            openFiles.add(OpenFile(f, content))
            activeIndex = openFiles.size - 1
        }
    }

    fun saveActive() {
        val of = openFiles.getOrNull(activeIndex) ?: return
        editorRef.value?.let { of.text = it.text.toString() }
        workspace.write(of.file, of.text)
        of.modified = false
    }

    fun updateFindInfo() {
        val s = editorRef.value?.searcher ?: return
        findInfo = if (s.hasQuery() && s.matchedPositionCount > 0) {
            "${s.currentMatchedPositionIndex + 1}/${s.matchedPositionCount}"
        } else ""
    }

    // ── 主题切换（同时切换 TextMate 配色）──
    LaunchedEffect(dark) {
        ThemeRegistry.getInstance().setTheme(if (dark) "dark_vs" else "quietlight")
        editorRef.value?.let { ed ->
            runCatching { ed.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance()) }
        }
    }

    // ── 字号 ──
    LaunchedEffect(fontSize) {
        editorRef.value?.setTextSize(fontSize)
    }

    // ── 首次进入横屏时自动展开侧面板，让分屏效果立即可见 ──
    var landscapeSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(isLandscape) {
        if (isLandscape && !landscapeSeeded) {
            showConsole = true
            landscapeSeeded = true
        }
    }

    // ── 首次启动：安装编译器 ──
    LaunchedEffect(Unit) {
        if (!toolchain.isInstalled()) {
            installing = true
            status = "正在安装内置编译器…"
            withContext(Dispatchers.IO) {
                runCatching {
                    toolchain.install { msg -> activity.runOnUiThread { status = msg } }
                }.onFailure { e ->
                    activity.runOnUiThread { status = "编译器安装失败：${e.message}" }
                }
            }
            installing = false
        } else {
            status = "编译器已就绪（clang ${toolchain.versionName()}）"
        }
        if (openFiles.isEmpty()) {
            val f = File(workspace.root, "main.cpp")
            if (!f.exists()) workspace.write(f, templates.defaultSource(true))
            openFile(f, workspace.read(f))
        }
    }

    // ── 切换文件：同步内容与语言 ──
    LaunchedEffect(activeIndex) {
        val of = openFiles.getOrNull(activeIndex) ?: return@LaunchedEffect
        var ed = editorRef.value
        var tries = 0
        while (ed == null && tries < 50) {
            delay(20); ed = editorRef.value; tries++
        }
        ed ?: return@LaunchedEffect
        if (ed.text.toString() != of.text) ed.setText(of.text)
        val isC = of.file.extension.equals("c", true)
        // 包一层，在 TextMate 基础上追加 C/C++ 关键字与片段补全
        runCatching {
            val scopeName = if (isC) "source.c" else "source.cpp"
            ed.setEditorLanguage(CppCompletionLanguage(TextMateLanguage.create(scopeName, true)))
        }
    }

    // ── 退到后台自动保存 ──
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) runCatching { saveActive() }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    fun doCompile(run: Boolean) {
        val of = openFiles.getOrNull(activeIndex)
        if (of == null) { log("请先打开一个源文件"); return }
        if (!toolchain.isInstalled()) { log("编译器尚未安装完成，请稍候…"); return }

        // 保存所有已打开文件的编辑内容，保证多文件编译时用到的是最新代码
        editorRef.value?.let { of.text = it.text.toString(); of.modified = false }
        openFiles.forEach { workspace.write(it.file, it.text) }

        val sources = if (options.multiFile) {
            val dir = of.file.parentFile ?: workspace.root
            compiler.filterFamily(workspace.sourcesIn(dir), of.file)
        } else {
            listOf(of.file)
        }
        if (sources.isEmpty()) { log("当前文件夹里没有同类型的源文件"); return }

        busy = true
        showConsole = true
        scope.launch {
            val out = workspace.outputFor(of.file)
            log("")
            log("=== 开始编译（${sources.size} 个源文件）===")
            if (sources.size > 1) sources.forEach { log("    ${it.name}") }
            val result = withContext(Dispatchers.IO) {
                compiler.compile(sources, out, options) { line ->
                    activity.runOnUiThread { log(line) }
                }
            }
            if (!result.success) {
                log("=== 编译失败（退出码 ${result.exitCode}） ===")
                if (result.diagnostics.isNotEmpty()) {
                    log("提示：点击带行号的错误可直接跳到对应代码行")
                }
                busy = false
                return@launch
            }
            log("=== 编译成功 ===")
            if (run) {
                log("=== 运行 ${out.name} ===")
                withContext(Dispatchers.IO) {
                    runner.run(out, stdin = stdin, workDir = of.file.parentFile ?: workspace.root) { line ->
                        activity.runOnUiThread { log(line) }
                    }
                }
                log("=== 运行结束 ===")
            }
            busy = false
        }
    }

    // ── 调试 ──
    fun dbgLog(line: String) {
        activity.runOnUiThread { log(line) }
    }

    /** 调试命令控制台：表达式求值 / 原始 lldb 命令 */
    fun runDebugCommand(text: String, raw: Boolean) {
        if (text.isBlank()) return
        debugConsole.add(if (raw) "> $text" else "expr> $text")
        if (debugConsole.size > 600) debugConsole.removeRange(0, debugConsole.size - 600)
        scope.launch {
            val out = runCatching {
                if (raw) debugger.sendCommand(text) else debugger.evaluate(text)
            }.getOrElse { "错误：${it.message ?: it.javaClass.simpleName}" }
            debugConsole.add(out.ifBlank { "（无输出）" })
            if (debugConsole.size > 600) debugConsole.removeRange(0, debugConsole.size - 600)
        }
    }

    /**
     * 断点集合变化后刷新行号旁的断点标记。
     * 标记由编辑器上的覆盖层自己画（见 BreakpointOverlay.kt），
     * 这里只需要递增版本号触发重绘，并顺手记一条日志便于排查。
     */
    fun refreshBreakpointMarks() {
        bpVersion++
        val ed = editorRef.value
        val of = openFiles.getOrNull(activeIndex) ?: return
        val lines = bpLinesOf(of.file.absolutePath, debugger.currentBreakpoints)
        val styleOk = ed?.styles != null
        if (lines.isEmpty()) {
            val all = debugger.currentBreakpoints.joinToString { "${it.file}:${it.line}" }
            log("断点标记：0 个 / 当前=${of.file.absolutePath} 已有=[$all] 样式就绪=$styleOk")
        } else {
            log("断点标记：${lines.size} 个 → 已在代码左侧标出（样式就绪=$styleOk）")
        }
    }

    /** 按行切换断点（line 为 1 基，与 Breakpoint.line 一致） */
    fun toggleBreakpointAtLine(line: Int) {
        val of = openFiles.getOrNull(activeIndex) ?: run { log("请先打开一个源文件"); return }
        val path = of.file.absolutePath
        if (debugger.hasBreakpoint(path, line)) {
            scope.launch {
                debugger.deleteBreakpoint(path, line)
                log("已删除断点 ${of.file.name}:$line")
                refreshBreakpointMarks()
            }
        } else {
            // 弹窗询问条件（可留空 = 无条件命中）
            bpPendingFile = path
            bpPendingLine = line
            bpCondition = ""
            bpIsEdit = false
            showBpDialog = true
        }
    }

    fun toggleBreakpointAtCursor() {
        val ed = editorRef.value ?: return
        toggleBreakpointAtLine(ed.cursor.leftLine + 1)
    }

    /** 确认添加/保存断点（condition 为空表示无条件命中） */
    fun confirmBreakpoint(condition: String) {
        val path = bpPendingFile ?: return
        val line = bpPendingLine ?: return
        val wasEdit = bpIsEdit
        showBpDialog = false
        val cond = condition.trim()
        scope.launch {
            debugger.setBreakpoint(path, line, cond)
            val verb = if (wasEdit) "已更新断点" else "已添加断点"
            log(
                if (cond.isEmpty()) "$verb：第 $line 行"
                else "$verb：第 $line 行（条件 $cond）"
            )
            refreshBreakpointMarks()
        }
    }

    fun dbgStart() {
        val of = openFiles.getOrNull(activeIndex) ?: return
        if (!toolchain.isInstalled()) { log("编译器尚未安装完成，请稍候…"); return }
        if (!debugger.driver.isAvailable()) {
            log("调试器不可用：内置工具链缺少 lldb / lldb-server，请先重新安装")
            return
        }
        editorRef.value?.let { of.text = it.text.toString(); of.modified = false }
        workspace.write(of.file, of.text)

        debugBusy = true
        showDebug = true
        showConsole = true
        scope.launch {
            val out = workspace.outputFor(of.file)
            log("")
            log("=== 准备调试 ${of.file.name} ===")
            val r = runCatching {
                debugger.start(
                    source = of.file,
                    sourceText = of.text,
                    output = out,
                    options = options,
                    onProgress = ::dbgLog,
                    onLiveOutput = ::dbgLog
                )
            }.getOrNull()

            if (r == null || !r.success) {
                log("=== 调试准备失败（编译未通过，或调试器启动失败） ===")
                debugBusy = false
                return@launch
            }
            log("=== 调试器已启动，运行到第一个断点 ===")
            val step = runCatching {
                debugger.run(::dbgLog)
            }.getOrNull()
            debugVars = runCatching { debugger.variables() }.getOrDefault(emptyList())
            debugStack = runCatching { debugger.backtrace() }.getOrDefault(emptyList())
            if (step != null && step.exited) {
                log("程序已直接运行结束，没命中任何断点 —— 请先加断点再开始调试")
            }
            debugBusy = false
        }
    }

    /** 通用的调试动作（继续/单步/停止），执行完自动刷新变量与调用栈 */
    fun dbgRun(action: suspend (DebugController) -> Unit) {
        if (debugBusy) return
        debugBusy = true
        scope.launch {
            runCatching { action(debugger) }
            debugVars = runCatching { debugger.variables() }.getOrDefault(emptyList())
            debugStack = runCatching { debugger.backtrace() }.getOrDefault(emptyList())
            debugBusy = false
        }
    }

    fun dbgStop() {
        debugger.stop()
        debugVars = emptyList()
        debugStack = emptyList()
        log("调试已停止")
    }

    // ── 跳转定义（clangd）──
    fun gotoDefinition() {
        val of = openFiles.getOrNull(activeIndex) ?: return
        val ed = editorRef.value ?: return
        val (word, col, line) = EditorActions.wordAt(ed) ?: run {
            log("请先把光标放到一个标识符（函数名/变量名）上")
            return
        }
        if (!toolchain.isInstalled()) { log("编译器尚未安装完成，请稍候…"); return }

        showConsole = true
        log("正在查找「$word」的定义…")
        val isC = of.file.extension.equals("c", true)
        scope.launch {
            navigator.didOpen(of.file, of.text, if (isC) "c" else "cpp")
            val results = runCatching {
                navigator.gotoDefinition(of.file, line, col)
            }.getOrDefault(emptyList())
            if (results.isEmpty()) {
                log("没找到「$word」的定义")
            } else {
                log("找到 ${results.size} 处定义：${results.joinToString { File(it.file).name + ":" + (it.line + 1) }}")
                navTitle = "跳转到定义"
                navResults = results
            }
        }
    }

    /** 计算纯文本里 (行,列) 对应的字符下标（0 基）；越界返回 -1 */
    fun indexInString(text: String, line: Int, character: Int): Int {
        if (line < 0) return -1
        var idx = 0
        var curLine = 0
        while (curLine < line) {
            val nl = text.indexOf('\n', idx)
            if (nl < 0) return -1
            idx = nl + 1
            curLine++
        }
        val nl = text.indexOf('\n', idx)
        val lineEnd = if (nl < 0) text.length else nl
        val c = character.coerceAtLeast(0)
        return (idx + c).coerceAtMost(lineEnd)
    }

    /**
     * 判断两个路径是否指向同一个文件。
     * 安卓上 /data/data/<pkg> 与 /data/user/0/<pkg> 是同一目录的不同写法，
     * clangd 会做 realpath，所以必须用 canonicalPath 比较。
     */
    fun sameFile(a: File, otherPath: String): Boolean {
        val b = File(otherPath)
        val ca = runCatching { a.canonicalPath }.getOrDefault(a.absolutePath)
        val cb = runCatching { b.canonicalPath }.getOrDefault(b.absolutePath)
        if (ca == cb) return true
        // 兜底：父目录归一化后文件名相同（应对个别机型的符号链接差异）
        val pa = runCatching { a.parentFile?.canonicalPath }.getOrNull()
        val pb = runCatching { b.parentFile?.canonicalPath }.getOrNull()
        return a.name == b.name && pa != null && pa == pb
    }

    /** Content 里 (行,列) → 字符下标；越界返回 null */
    fun charIndexOf(content: Content, line: Int, character: Int, total: Int): Int? {
        if (line < 0 || line >= content.lineCount) return null
        val lineLen = content.getLineString(line).length
        val c = character.coerceIn(0, lineLen)
        return runCatching { content.getCharIndex(line, c).coerceIn(0, total) }.getOrNull()
    }

    // ── 查找引用（clangd）──
    fun findReferences() {
        val of = openFiles.getOrNull(activeIndex) ?: run { log("请先打开一个源文件"); return }
        val ed = editorRef.value ?: return
        val (word, col, line) = EditorActions.wordAt(ed) ?: run {
            log("请先把光标放到一个标识符（函数名/变量名）上")
            return
        }
        if (!toolchain.isInstalled()) { log("编译器尚未安装完成，请稍候…"); return }

        showConsole = true
        log("正在查找「$word」的所有引用…")
        val isC = of.file.extension.equals("c", true)
        scope.launch {
            navigator.didOpen(of.file, of.text, if (isC) "c" else "cpp")
            val results = runCatching {
                navigator.references(of.file, line, col, true)
            }.getOrDefault(emptyList())
            if (results.isEmpty()) {
                log("没找到「$word」的引用")
            } else {
                log("找到 ${results.size} 处引用")
                navTitle = "「$word」的引用"
                navResults = results
            }
        }
    }

    // ── 重命名符号（clangd）──
    fun startRename() {
        val of = openFiles.getOrNull(activeIndex) ?: run { log("请先打开一个源文件"); return }
        val ed = editorRef.value ?: return
        val (word, col, line) = EditorActions.wordAt(ed) ?: run {
            log("请先把光标放到一个标识符（函数名/变量名）上")
            return
        }
        if (!toolchain.isInstalled()) { log("编译器尚未安装完成，请稍候…"); return }

        showConsole = true
        log("正在检查「$word」是否可重命名…")
        val isC = of.file.extension.equals("c", true)
        scope.launch {
            navigator.didOpen(of.file, of.text, if (isC) "c" else "cpp")
            val prep = runCatching { navigator.prepareRename(of.file, line, col) }.getOrNull()
            if (prep == null) {
                log("「$word」在此处无法重命名（可能是关键字、宏或标准库符号）")
                return@launch
            }
            renamePendingFile = of.file
            renamePendingLine = line
            renamePendingChar = col
            renameOldName = prep.placeholder.ifBlank { word }
            renameNewName = renameOldName
            showRenameDialog = true
        }
    }

    /** 确认重命名：把 clangd 返回的多文件编辑落到工作区 */
    fun confirmRename(newName: String) {
        val f = renamePendingFile ?: return
        val line = renamePendingLine
        val ch = renamePendingChar
        val name = newName.trim()
        showRenameDialog = false

        if (name.isEmpty() || !name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            log("名字不合法：只能由字母、数字、下划线组成，且不能以数字开头")
            return
        }
        if (name == renameOldName) { log("名字没有变化"); return }

        scope.launch {
            val edits = runCatching { navigator.rename(f, line, ch, name) }
                .getOrDefault(emptyMap())
            if (edits.isEmpty()) {
                log("重命名失败：clangd 没有返回可改动的位置")
                return@launch
            }
            var total = 0
            val touched = mutableListOf<File>()
            for ((path, list) in edits) {
                val target = File(path)
                if (!target.exists() || target.isDirectory) continue
                var content = workspace.read(target)
                // 从后往前应用，避免前面的编辑让后面的行列偏移
                val sorted = list.sortedWith(
                    compareByDescending<TextEditRange> { it.startLine }
                        .thenByDescending { it.startChar }
                )
                for (e in sorted) {
                    val s = indexInString(content, e.startLine, e.startChar)
                    val en = indexInString(content, e.endLine, e.endChar)
                    if (s < 0 || en < s) continue
                    content = content.substring(0, s) + e.newText + content.substring(en)
                    total++
                }
                workspace.write(target, content)
                touched += target
            }
            // 刷新已打开文件的内存内容（避免保存时用旧内容覆盖）
            for (of in openFiles) {
                if (touched.any { it.absolutePath == of.file.absolutePath }) {
                    of.text = workspace.read(of.file)
                    of.modified = false
                }
            }
            // 重新载入当前文件到编辑器
            val cur = openFiles.getOrNull(activeIndex)
            if (cur != null && touched.any { it.absolutePath == cur.file.absolutePath }) {
                editorRef.value?.let { runCatching { it.setText(cur.text) } }
            }
            log("已重命名：$renameOldName → $name（共 $total 处，涉及 ${touched.size} 个文件）")
        }
    }

    /** 编辑器内容变化 → 标记未保存 + 防抖后同步给 clangd 触发实时诊断 */
    fun onEditorContentChanged() {
        val of = openFiles.getOrNull(activeIndex) ?: return
        val text = editorRef.value?.text?.toString() ?: of.text
        of.text = text
        of.modified = true
        if (!diagnosticsOn) return
        diagJob?.cancel()
        diagJob = scope.launch {
            delay(700)
            runCatching { navigator.didChange(of.file, text) }
        }
    }

    /** 把 clangd 推送的诊断画到编辑器上（回调在后台线程，需切主线程） */
    fun applyDiagnostics(uri: String, items: List<LspDiagnostic>) {
        activity.runOnUiThread {
            // clangd 回传的 uri 可能经过 realpath（/data/data ↔ /data/user/0）或带 %xx 转义，
            // 必须解码 + 归一化后再比较，否则永远匹配不上、波浪线不出现。
            val path = runCatching { android.net.Uri.parse(uri).path }.getOrNull()
                ?: uri.removePrefix("file://")
            val of = openFiles.getOrNull(activeIndex) ?: return@runOnUiThread
            if (!sameFile(of.file, path)) {
                if (items.isNotEmpty() && !diagMismatchLogged) {
                    diagMismatchLogged = true
                    log("诊断文件不匹配：${File(path).name} ≠ ${of.file.name}")
                }
                return@runOnUiThread
            }
            val ed = editorRef.value ?: return@runOnUiThread
            if (!diagnosticsOn) {
                runCatching { ed.setDiagnostics(null) }
                return@runOnUiThread
            }
            val built = runCatching {
                val content = ed.text
                val total = content.length
                val regions = ArrayList<DiagnosticRegion>(items.size)
                var id = 1L
                for (d in items) {
                    val s = charIndexOf(content, d.startLine, d.startChar, total) ?: continue
                    var e = charIndexOf(content, d.endLine, d.endChar, total) ?: continue
                    if (e <= s) e = (s + 1).coerceAtMost(total)
                    if (e <= s) continue
                    val sev = when (d.severity) {
                        1 -> DiagnosticRegion.SEVERITY_ERROR
                        2 -> DiagnosticRegion.SEVERITY_WARNING
                        else -> DiagnosticRegion.SEVERITY_TYPO
                    }
                    val msg = d.message.ifBlank { "诊断" }
                    val detail = DiagnosticDetail(msg, msg, emptyList(), null)
                    regions += DiagnosticRegion(s, e, sev, id++, detail)
                }
                val container = DiagnosticsContainer()
                if (regions.isNotEmpty()) {
                    // Sora 的 addDiagnostics 不做排序，必须按 startIndex 有序，否则渲染会错乱
                    container.addDiagnostics(regions.sortedBy { it.startIndex })
                }
                container to regions.size
            }
            built.onSuccess { (container, count) ->
                runCatching { ed.setDiagnostics(container) }
                if (count > 0 && !diagOkLogged) {
                    diagOkLogged = true
                    log("实时诊断已就绪：$count 处问题已标出")
                }
            }.onFailure {
                log("诊断渲染失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    // ── 悬停信息：显示光标处符号的类型 / 签名（clangd）──
    fun showHoverInfo() {
        val of = openFiles.getOrNull(activeIndex) ?: run { log("请先打开一个源文件"); return }
        val ed = editorRef.value ?: return
        val (word, col, line) = EditorActions.wordAt(ed) ?: run {
            log("请先把光标放到一个标识符（函数名/变量名）上")
            return
        }
        if (!toolchain.isInstalled()) { log("编译器尚未安装完成，请稍候…"); return }

        showConsole = false
        log("正在查询「$word」的类型信息…")
        val isC = of.file.extension.equals("c", true)
        scope.launch {
            navigator.didOpen(of.file, of.text, if (isC) "c" else "cpp")
            val info = runCatching { navigator.hover(of.file, line, col) }.getOrNull()
            if (info.isNullOrBlank()) {
                log("没有找到「$word」的类型信息")
            } else {
                hoverInfo = info
            }
        }
    }

    fun jumpToNav(loc: NavLocation) {        val f = java.io.File(loc.file)
        if (!f.exists()) { log("文件不存在：${loc.file}"); return }
        val content = workspace.read(f)
        navResults = emptyList()
        val alreadyOpen = openFiles.any { it.file.absolutePath == f.absolutePath }
        openFile(f, content)
        scope.launch {
            // 等 LaunchedEffect(activeIndex) 把内容同步进编辑器
            var tries = 0
            val ed = editorRef.value
            while (tries < 60) {
                val cur = editorRef.value?.text?.toString()
                if (cur != null && cur == content) break
                delay(30)
                tries++
            }
            editorRef.value?.let { EditorActions.jumpTo(it, loc.line + 1, loc.character + 1) }
            log("已跳转到 ${f.name}:${loc.line + 1}")
        }
        if (alreadyOpen) showConsole = false
    }

    /** 跳到指定文件的指定行（line 为 1 基） */
    fun jumpToFileLine(path: String, line: Int) {
        jumpToNav(NavLocation(path, line - 1, 0))
    }

    // ── TODO / FIXME 扫描（放到 IO 线程，避免卡界面）──
    fun scanTodos() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val out = mutableListOf<TodoItem>()
                runCatching {
                    for (f in workspace.sourcesIn(workspace.root)) {
                        val text = runCatching { workspace.read(f) }.getOrNull() ?: continue
                        text.lineSequence().forEachIndexed { idx, line ->
                            if (TODO_REGEX.containsMatchIn(line)) {
                                out += TodoItem(f.absolutePath, idx + 1, line.trim())
                            }
                        }
                    }
                }
                out
            }
            todos = list
            log("扫描到 ${list.size} 条 TODO/FIXME")
        }
    }

    // ── 书签 ──
    fun addBookmarkHere() {
        val of = openFiles.getOrNull(activeIndex) ?: run { log("请先打开一个源文件"); return }
        val ed = editorRef.value ?: return
        val line = ed.cursor.leftLine + 1
        val path = of.file.absolutePath
        if (bookmarks.any { it.file == path && it.line == line }) {
            log("这一行已经有书签了")
            return
        }
        bookmarks += BookmarkItem(path, line)
        log("已加书签：${of.file.name}:$line")
    }

    // ── 定期自动保存（30 秒）──
    fun saveAllModified() {
        val active = openFiles.getOrNull(activeIndex)
        if (active != null) {
            editorRef.value?.let { active.text = it.text.toString() }
        }
        var saved = 0
        for (of in openFiles) {
            if (of.modified) {
                runCatching {
                    workspace.write(of.file, of.text)
                    of.modified = false
                    saved++
                }
            }
        }
        if (saved > 0) log("已自动保存 $saved 个文件")
    }

    // ── 代码导入（SAF 文件选择器，无需存储权限）。
    //   必须放在所有局部函数声明之后，否则引用不到 log / openFile ──
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val f = FileSharing.importFromUri(activity, uri, workspace)
            if (f != null) {
                log("已导入：${f.name}")
                openFile(f, workspace.read(f))
            } else {
                log("导入失败：无法读取该文件")
            }
        }
    }

    // ── 实时诊断：注册 clangd 回调 ──
    LaunchedEffect(Unit) {
        navigator.onDiagnostics = { uri, _, items -> applyDiagnostics(uri, items) }
    }
    DisposableEffect(Unit) {
        onDispose { navigator.onDiagnostics = null }
    }
    // ── 切换文件 / 开关诊断：清掉旧波浪线并按需重新诊断 ──
    LaunchedEffect(activeIndex, diagnosticsOn) {
        if (!diagnosticsOn) {
            runCatching { editorRef.value?.setDiagnostics(null) }
            return@LaunchedEffect
        }
        val of = openFiles.getOrNull(activeIndex) ?: return@LaunchedEffect
        var ed = editorRef.value
        var tries = 0
        while (tries < 50 && (ed == null || ed.text.toString() != of.text)) {
            delay(20); ed = editorRef.value; tries++
        }
        runCatching { ed?.setDiagnostics(null) }
        onEditorContentChanged()
    }

    // ── 切换文件后把断点红点重新贴上（新编辑器 / 新语言实例）──
    LaunchedEffect(activeIndex) {
        if (openFiles.getOrNull(activeIndex) == null) return@LaunchedEffect
        delay(250)
        runCatching { refreshBreakpointMarks() }
    }

    // ── 软键盘可见性：弹出时自动收起底部「编译/运行」栏和控制台，给编辑器让出空间 ──
    DisposableEffect(isLandscape) {
        val content = activity.findViewById<View>(android.R.id.content)
        if (content == null) {
            onDispose { }
        } else {
            var maxHeight = 0
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                // 优先用 IME insets；adjustResize 下 insets 可能被 DecorView 消费掉，
                // 再用「内容区高度骤降」兜底（键盘至少占屏高 15% 以上）。
                val byInsets = runCatching {
                    ViewCompat.getRootWindowInsets(content)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
                }.getOrDefault(false)
                val h = content.height
                if (h > maxHeight) maxHeight = h
                val byHeight = maxHeight > 0 && (maxHeight - h) > (maxHeight * 0.15f).toInt()
                val now = byInsets || byHeight
                if (now) imeSignalWorks = true
                if (now != imeVisible) imeVisible = now
            }
            val vto = content.viewTreeObserver
            vto.addOnGlobalLayoutListener(listener)
            onDispose {
                runCatching { vto.removeOnGlobalLayoutListener(listener) }
                imeVisible = false
            }
        }
    }

    // ── 每 30 秒自动保存一次有改动的文件 ──
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            runCatching { saveAllModified() }
        }
    }
    // ── 自动换行开关 ──
    LaunchedEffect(wordwrapOn) {
        runCatching { editorRef.value?.setWordwrap(wordwrapOn) }
    }

    // 键盘检测生效时以键盘为准；万一个别机型检测不到，用「编辑器获得焦点」兜底
    val hideBottomBar = imeVisible || (editorFocused && !imeSignalWorks)

    RedPandaTheme(darkTheme = dark) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                    FileTreePanel(
                        root = workspace.root,
                        currentFile = openFiles.getOrNull(activeIndex)?.file,
                        onOpen = { f ->
                            openFile(f, workspace.read(f))
                            scope.launch { drawerState.close() }
                        },
                        onImport = {
                            runCatching {
                                importLauncher.launch(arrayOf("*/*"))
                            }.onFailure { log("无法打开文件选择器") }
                        },
                        onShare = { f ->
                            val ok = FileSharing.share(activity, f)
                            if (!ok) log("分享失败：无法读取 ${f.name}")
                        },
                        onChanged = { }
                    )
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.size(42.dp)
                            ) {
                                Icon(Icons.Filled.Menu, "文件", modifier = Modifier.size(22.dp))
                            }
                        },
                        title = {
                            Text(
                                "小熊猫C++",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        actions = {
                            // 四个操作按钮收窄到 40dp，给标题让出空间
                            CompactActionIcon(Icons.Filled.Add, "从模板新建") { showTemplates = true }
                            CompactActionIcon(Icons.Filled.Save, "保存") {
                                saveActive(); log("已保存")
                            }
                            CompactActionIcon(Icons.Filled.BugReport, "环境自检") { showSelfCheck = true }
                            CompactActionIcon(Icons.Filled.Settings, "设置") { showSettings = true }
                        }
                    )
                },
                bottomBar = {
                    // 软键盘弹出 / 调试面板打开时收起，避免挡住正在编辑的代码
                    if (!hideBottomBar && !showDebug) {
                        Column {
                            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            RunBar(
                                busy = busy,
                                showConsole = showConsole,
                                onRun = { doCompile(true) },
                                onCompile = { doCompile(false) },
                                onDebug = { dbgStart() },
                                onToggleConsole = { showConsole = !showConsole },
                                onClear = { console.clear() }
                            )
                        }
                    }
                }
            ) { padding ->
                if (isLandscape) {
                    // ══════════ 横屏专用布局 ══════════
                    // 竖排工具栏在左，编辑器占中间，侧面板在右，充分利用横向空间
                    Row(modifier = Modifier.padding(padding).fillMaxSize()) {

                        // ① 左侧竖排工具栏
                        Surface(
                            tonalElevation = 2.dp,
                            modifier = Modifier.width(50.dp).fillMaxHeight()
                        ) {
                            Column(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                VBarIcon(Icons.Filled.Undo, "撤销") { editorRef.value?.undo() }
                                VBarIcon(Icons.Filled.Redo, "重做") { editorRef.value?.redo() }
                                VBarIcon(Icons.Filled.Search, "查找替换") { showFind = !showFind }
                                VBarIcon(Icons.Filled.Comment, "注释") {
                                    editorRef.value?.let { EditorActions.toggleLineComment(it) }
                                }
                                VBarIcon(Icons.Filled.FormatIndentIncrease, "增加缩进") {
                                    editorRef.value?.let { EditorActions.indent(it) }
                                }
                                VBarIcon(Icons.Filled.FormatIndentDecrease, "减少缩进") {
                                    editorRef.value?.let { EditorActions.unindent(it) }
                                }
                                VBarIcon(Icons.Filled.AutoFixHigh, "格式化") {
                                    editorRef.value?.let { EditorActions.reformat(it) }
                                    log("已按大括号层级重新缩进")
                                }
                                VBarIcon(Icons.Filled.Code, "插入代码片段") { showSnippets = true }
                                VBarIcon(Icons.Filled.ArrowForward, "跳转定义") { gotoDefinition() }
                                VBarIcon(Icons.Filled.ManageSearch, "查找引用") { findReferences() }
                                VBarIcon(Icons.Filled.DriveFileRenameOutline, "重命名符号") { startRename() }
                                VBarIcon(Icons.Filled.Info, "查看类型/签名") { showHoverInfo() }
                                VBarIcon(Icons.Filled.FormatListBulleted, "任务与书签") {
                                    scanTodos(); showTasks = true
                                }
                                VBarIcon(Icons.Filled.Circle, "添加/删除断点") { toggleBreakpointAtCursor() }
                                VBarIcon(Icons.Filled.Save, "保存") { saveActive(); log("已保存") }
                                VBarIcon(Icons.Filled.Keyboard, "符号栏", active = showSymbolBar) {
                                    showSymbolBar = !showSymbolBar
                                }
                            }
                        }

                        // ② 中间：标签页 + 编辑器 + 符号栏
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            if (installing) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "安装编译器中…",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    LinearProgressIndicator(
                                        modifier = Modifier.weight(1f).height(3.dp)
                                    )
                                }
                            }
                            if (openFiles.isNotEmpty()) {
                                ScrollableTabRow(
                                    selectedTabIndex = activeIndex.coerceAtLeast(0),
                                    edgePadding = 0.dp
                                ) {
                                    openFiles.forEachIndexed { i, of ->
                                        Tab(
                                            selected = i == activeIndex,
                                            onClick = {
                                                editorRef.value?.let { ed ->
                                                    openFiles.getOrNull(activeIndex)?.text =
                                                        ed.text.toString()
                                                }
                                                activeIndex = i
                                            },
                                            text = {
                                                Text(
                                                    if (of.modified) "${of.file.name} *"
                                                    else of.file.name,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                EditorCore(
                                    editorRef = editorRef,
                                    file = openFiles.getOrNull(activeIndex),
                                    fontSize = fontSize,
                                    modifier = Modifier.fillMaxSize(),
                                    onContentChanged = { onEditorContentChanged() },
                                    onFocusChanged = { editorFocused = it },
                                    breakpointLines = {
                                        val cur = openFiles.getOrNull(activeIndex)
                                        if (cur == null) emptySet()
                                        else bpLinesOf(cur.file.absolutePath, debugger.currentBreakpoints)
                                    },
                                    onBreakpointClick = { line0 -> toggleBreakpointAtLine(line0 + 1) },
                                    wordwrap = wordwrapOn,
                                    breakpointVersion = bpVersion
                                )
                                if (showFind) {
                                    FindOverlay(
                                        editorRef = editorRef,
                                        findInfo = findInfo,
                                        findCase = findCase,
                                        findRegex = findRegex,
                                        onFindInfoChange = { findInfo = it },
                                        onFindCaseChange = { findCase = it },
                                        onFindRegexChange = { findRegex = it },
                                        onClose = {
                                            runCatching { editorRef.value?.searcher?.stopSearch() }
                                            findInfo = ""
                                            showFind = false
                                        }
                                    )
                                }
                            }
                            if (showSymbolBar) {
                                SymbolBar(onSymbol = { s ->
                                    editorRef.value?.let { EditorActions.insertText(it, s) }
                                })
                            }
                        }

                        // ③ 右侧侧面板
                        if (showConsole || showDebug) {
                            Surface(
                                tonalElevation = 2.dp,
                                modifier = Modifier.width(300.dp).fillMaxHeight()
                            ) {
                                Column(modifier = Modifier.fillMaxHeight()) {
                                    if (showConsole && showDebug) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            FilterChip(
                                                selected = !sideIsDebug,
                                                onClick = { sideIsDebug = false },
                                                label = { Text("控制台", fontSize = 11.sp) }
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            FilterChip(
                                                selected = sideIsDebug,
                                                onClick = { sideIsDebug = true },
                                                label = { Text("调试", fontSize = 11.sp) }
                                            )
                                        }
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    }
                                    if (showDebug && (!showConsole || sideIsDebug)) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            DebugPanel(
                                                phase = debugger.phase,
                                                location = debugger.lastLocation,
                                                breakpoints = debugger.currentBreakpoints,
                                                variables = debugVars,
                                                stack = debugStack,
                                                onStart = ::dbgStart,
                                                onContinue = { dbgRun { it.cont(::dbgLog) } },
                                                onNext = { dbgRun { it.next(::dbgLog) } },
                                                onStepIn = { dbgRun { it.stepIn(::dbgLog) } },
                                                onStepOut = { dbgRun { it.stepOut(::dbgLog) } },
                                                onStop = ::dbgStop,
                                                onDeleteBreakpoint = { bp ->
                                                    scope.launch {
                                                        debugger.deleteBreakpoint(bp.file, bp.line)
                                                        refreshBreakpointMarks()
                                                    }
                                                },
                                                onEditBreakpoint = { bp ->
                                                    bpPendingFile = bp.file
                                                    bpPendingLine = bp.line
                                                    bpCondition = bp.condition
                                                    bpIsEdit = true
                                                    showBpDialog = true
                                                },
                                                onLocationClick = { loc ->
                                                    val of = openFiles.getOrNull(activeIndex)
                                                    val f = File(loc.file)
                                                    if (of != null && of.file.absolutePath == loc.file) {
                                                        editorRef.value?.let {
                                                            EditorActions.jumpTo(it, loc.line + 1, 1)
                                                        }
                                                    } else if (f.exists()) {
                                                        openFile(f, workspace.read(f))
                                                        editorRef.value?.let {
                                                            EditorActions.jumpTo(it, loc.line + 1, 1)
                                                        }
                                                    }
                                                },
                                                commandLog = debugConsole,
                                                onCommand = { text, raw -> runDebugCommand(text, raw) },
                                                console = console,
                                                onDismiss = { showDebug = false }
                                            )
                                        }
                                    } else {
                                        ConsolePanel(
                                            console = console,
                                            stdin = stdin,
                                            onStdinChange = { stdin = it },
                                            onLineClick = { line ->
                                                val d = compiler.parseDiagnostic(line)
                                                    ?: return@ConsolePanel
                                                val ed = editorRef.value ?: return@ConsolePanel
                                                EditorActions.jumpTo(ed, d.line, d.column)
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                Column(modifier = Modifier.padding(padding).fillMaxSize()) {

                    // 安装进度 / 状态
                    if (installing) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text("正在安装内置编译器，请勿退出…", fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(4.dp))
                            Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // 标签页
                    if (openFiles.isNotEmpty()) {
                        ScrollableTabRow(selectedTabIndex = activeIndex.coerceAtLeast(0), edgePadding = 0.dp) {
                            openFiles.forEachIndexed { i, of ->
                                Tab(
                                    selected = i == activeIndex,
                                    onClick = {
                                        editorRef.value?.let { ed ->
                                            openFiles.getOrNull(activeIndex)?.text = ed.text.toString()
                                        }
                                        activeIndex = i
                                    },
                                    text = {
                                        Text(
                                            if (of.modified) "${of.file.name} *" else of.file.name,
                                            fontSize = 13.sp
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // 编辑器 + 查找浮层
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        EditorCore(
                            editorRef = editorRef,
                            file = openFiles.getOrNull(activeIndex),
                            fontSize = fontSize,
                            modifier = Modifier.fillMaxSize(),
                            onContentChanged = { onEditorContentChanged() },
                            onFocusChanged = { editorFocused = it },
                            breakpointLines = {
                                val cur = openFiles.getOrNull(activeIndex)
                                if (cur == null) emptySet()
                                else bpLinesOf(cur.file.absolutePath, debugger.currentBreakpoints)
                            },
                            onBreakpointClick = { line0 -> toggleBreakpointAtLine(line0 + 1) },
                            wordwrap = wordwrapOn,
                            breakpointVersion = bpVersion
                        )
                        if (showFind) {
                            FindOverlay(
                                editorRef = editorRef,
                                findInfo = findInfo,
                                findCase = findCase,
                                findRegex = findRegex,
                                onFindInfoChange = { findInfo = it },
                                onFindCaseChange = { findCase = it },
                                onFindRegexChange = { findRegex = it },
                                onClose = {
                                    runCatching { editorRef.value?.searcher?.stopSearch() }
                                    findInfo = ""
                                    showFind = false
                                }
                            )
                        }
                    }

                    // 编辑工具栏
                    EditorToolbar(
                        onUndo = { editorRef.value?.undo() },
                        onRedo = { editorRef.value?.redo() },
                        onFind = { showFind = !showFind },
                        onComment = { editorRef.value?.let { EditorActions.toggleLineComment(it) } },
                        onIndent = { editorRef.value?.let { EditorActions.indent(it) } },
                        onUnindent = { editorRef.value?.let { EditorActions.unindent(it) } },
                        onFormat = {
                            editorRef.value?.let { EditorActions.reformat(it) }
                            log("已按大括号层级重新缩进")
                        },
                        onSnippets = { showSnippets = true },
                        onGotoDefinition = { gotoDefinition() },
                        onInfo = { showHoverInfo() },
                        onReferences = { findReferences() },
                        onRename = { startRename() },
                        onTasks = { scanTodos(); showTasks = true },
                        onBreakpoint = { toggleBreakpointAtCursor() },
                        onSave = { saveActive(); log("已保存") },
                        symbolBarOn = showSymbolBar,
                        onToggleSymbolBar = { showSymbolBar = !showSymbolBar }
                    )

                    // 符号快捷输入栏
                    if (showSymbolBar) {
                        SymbolBar(onSymbol = { s ->
                            editorRef.value?.let { EditorActions.insertText(it, s) }
                        })
                    }

                    // 控制台（软键盘弹出 / 调试面板打开时临时收起，之后自动恢复）
                    if (showConsole && !hideBottomBar && !showDebug) {
                        ConsolePanel(
                            console = console,
                            stdin = stdin,
                            onStdinChange = { stdin = it },
                            modifier = Modifier.height(170.dp),
                            onLineClick = { line ->
                                val d = compiler.parseDiagnostic(line) ?: return@ConsolePanel
                                val ed = editorRef.value ?: return@ConsolePanel
                                EditorActions.jumpTo(ed, d.line, d.column)
                            }
                        )
                    }

                    // 调试面板
                    if (showDebug) {
                        DebugPanel(
                            phase = debugger.phase,
                            location = debugger.lastLocation,
                            breakpoints = debugger.currentBreakpoints,
                            variables = debugVars,
                            stack = debugStack,
                            onStart = ::dbgStart,
                            onContinue = { dbgRun { it.cont(::dbgLog) } },
                            onNext = { dbgRun { it.next(::dbgLog) } },
                            onStepIn = { dbgRun { it.stepIn(::dbgLog) } },
                            onStepOut = { dbgRun { it.stepOut(::dbgLog) } },
                            onStop = ::dbgStop,
                            onDeleteBreakpoint = { bp ->
                                scope.launch {
                                    debugger.deleteBreakpoint(bp.file, bp.line)
                                    refreshBreakpointMarks()
                                }
                            },
                            onEditBreakpoint = { bp ->
                                bpPendingFile = bp.file
                                bpPendingLine = bp.line
                                bpCondition = bp.condition
                                bpIsEdit = true
                                showBpDialog = true
                            },
                            onLocationClick = { loc ->
                                val of = openFiles.getOrNull(activeIndex)
                                val f = java.io.File(loc.file)
                                if (of != null && of.file.absolutePath == loc.file) {
                                    editorRef.value?.let { EditorActions.jumpTo(it, loc.line, 1) }
                                } else if (f.exists()) {
                                    openFile(f, workspace.read(f))
                                    editorRef.value?.let { EditorActions.jumpTo(it, loc.line, 1) }
                                }
                            },
                            commandLog = debugConsole,
                            onCommand = { text, raw -> runDebugCommand(text, raw) },
                            console = console,
                            onDismiss = { showDebug = false }
                        )
                    }
                }
                }
            }
        }
    }

    // ── 代码片段 ──
    if (showSnippets) {
        SnippetPanel(
            onDismiss = { showSnippets = false },
            onPick = { s ->
                showSnippets = false
                editorRef.value?.let { EditorActions.insertText(it, s.code) }
                log("已插入片段：${s.name}")
            }
        )
    }

    // ── 模板 ──
    if (showTemplates) {
        TemplateDialog(
            templates = templates,
            onDismiss = { showTemplates = false },
            onPick = { t, cpp ->
                showTemplates = false
                val name = if (cpp) t.cppName else t.cName
                var f = File(workspace.root, name)
                var i = 1
                while (f.exists()) {
                    val base = name.substringBeforeLast('.')
                    val ext = name.substringAfterLast('.', "")
                    f = File(workspace.root, "$base${i++}.$ext")
                }
                workspace.write(f, templates.loadSource(t, cpp))
                openFile(f, workspace.read(f))
                log("已从模板创建：${f.name}（${t.name}）")
            }
        )
    }

    // ── 添加断点（可填条件）──
    if (showBpDialog) {
        AlertDialog(
            onDismissRequest = { showBpDialog = false },
            title = { Text(if (bpIsEdit) "编辑断点" else "添加断点", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        "第 ${bpPendingLine ?: 0} 行",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = bpCondition,
                        onValueChange = { bpCondition = it },
                        label = { Text("条件表达式（可留空）", fontSize = 12.sp) },
                        placeholder = { Text("例如 i == 100", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    )
                    Text(
                        "留空 = 每次执行到这行都停下\n" +
                            "填条件 = 只有条件为真时才停下，例如 i == 100 或 n % 2 == 0",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmBreakpoint(bpCondition) }) {
                    Text(if (bpIsEdit) "保存" else "添加")
                }
            },
            dismissButton = { TextButton(onClick = { showBpDialog = false }) { Text("取消") } }
        )
    }

    // ── 悬停信息 ──
    if (hoverInfo != null) {
        AlertDialog(
            onDismissRequest = { hoverInfo = null },
            title = { Text("类型信息", fontSize = 16.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        hoverInfo.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = { TextButton(onClick = { hoverInfo = null }) { Text("关闭") } }
        )
    }

    // ── 重命名符号 ──
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名符号", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        "当前名字：$renameOldName",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renameNewName,
                        onValueChange = { renameNewName = it },
                        label = { Text("新名字", fontSize = 12.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    )
                    Text(
                        "会同步修改所有引用到这个名字的位置（可能跨多个文件）",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmRename(renameNewName) }) { Text("重命名") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 跳转定义结果 ──
    if (navResults.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { navResults = emptyList() },
            title = { Text(navTitle, fontSize = 16.sp) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(navResults.size) { i ->
                        val loc = navResults[i]
                        val f = File(loc.file)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { jumpToNav(loc) }
                                .padding(vertical = 8.dp, horizontal = 2.dp)
                        ) {
                            Text(
                                f.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                "${f.nameWithoutExtension}  ·  第 ${loc.line + 1} 行 第 ${loc.character + 1} 列",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { navResults = emptyList() }) { Text("取消") } }
        )
    }

    // ── 任务与书签 ──
    if (showTasks) {
        TasksDialog(
            todos = todos,
            bookmarks = bookmarks,
            onJump = { path, line ->
                showTasks = false
                jumpToFileLine(path, line)
            },
            onAddBookmark = { addBookmarkHere() },
            onRemoveBookmark = { b -> bookmarks.remove(b) },
            onDismiss = { showTasks = false }
        )
    }

    // ── 设置 ──
    if (showSettings) {
        SettingsDialog(
            options = options,
            themeMode = themeMode,
            fontSize = fontSize,
            diagOn = diagnosticsOn,
            wrapOn = wordwrapOn,
            storage = storage,
            onAbout = { showAbout = true },
            onDismiss = { showSettings = false },
            onConfirm = { o, t, fs, dg, wr ->
                options = o; themeMode = t; fontSize = fs
                diagnosticsOn = dg; wordwrapOn = wr
                showSettings = false
            }
        )
    }

    // ── 关于 ──
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    // ── 环境自检 ──
    if (showSelfCheck) {
        SelfCheckDialog(
            toolchain = toolchain,
            compiler = compiler,
            runner = runner,
            workspace = workspace,
            onDismiss = { showSelfCheck = false }
        )
    }
}

/**
 * 编辑器本体。抽成独立 composable，是为了横竖屏切换时能重建编辑器并恢复内容。
 * onRelease 时把未保存的编辑内容写回 OpenFile，避免旋转屏幕丢失代码。
 */
@Composable
private fun EditorCore(
    editorRef: MutableState<CodeEditor?>,
    file: OpenFile?,
    fontSize: Float,
    modifier: Modifier = Modifier,
    onContentChanged: (() -> Unit)? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    breakpointLines: () -> Set<Int> = { emptySet() },
    onBreakpointClick: ((Int) -> Unit)? = null,
    wordwrap: Boolean = false,
    /** 断点集合变化时 +1，用于触发断点覆盖层重绘 */
    breakpointVersion: Int = 0
) {
    // 记住当前编辑的文件，销毁时把内容写回去
    val owner = remember { mutableStateOf(file) }
    SideEffect { owner.value = file }
    val changeCb = rememberUpdatedState(onContentChanged)
    val focusCb = rememberUpdatedState(onFocusChanged)
    val bpLinesCb = rememberUpdatedState(breakpointLines)
    val bpClickCb = rememberUpdatedState(onBreakpointClick)
    // 滚动 / 改字都会让行位置变化，需要重画断点覆盖层
    var drawTick by remember { mutableIntStateOf(0) }
    val dotRadius = with(LocalDensity.current) { 5.dp.toPx() }
    // 行号左边距加宽这么多，用来放断点红点（索娜数字右对齐，这段本来就是空白）
    val gutterMargin = with(LocalDensity.current) { 18.dp.toPx() }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                CodeEditor(ctx).apply {
                    typefaceText = Typeface.MONOSPACE
                    setTextSize(fontSize)
                    setLineNumberMarginLeft(gutterMargin)
                    runCatching { setWordwrap(wordwrap) }
                    runCatching {
                        colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                    }
                    editorRef.value = this
                    val f = owner.value
                    if (f != null) {
                        runCatching { setText(f.text) }
                        runCatching {
                            val isC = f.file.extension.equals("c", true)
                            val scopeName = if (isC) "source.c" else "source.cpp"
                            setEditorLanguage(
                                CppCompletionLanguage(TextMateLanguage.create(scopeName, true))
                            )
                        }
                    }
                    // 打字后触发实时诊断（编辑器级事件，setText 换 Content 后依然有效）
                    runCatching {
                        subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                            drawTick++
                            runCatching { changeCb.value?.invoke() }
                        }
                    }
                    // 焦点变化（点进编辑器 → 键盘弹出）作为键盘检测的兜底信号
                    runCatching {
                        subscribeEvent(EditorFocusChangeEvent::class.java) { e, _ ->
                            runCatching { focusCb.value?.invoke(e.isGainFocus) }
                        }
                    }
                    // 滚动时行位置变了，重画断点覆盖层
                    runCatching {
                        subscribeEvent(ScrollEvent::class.java) { _, _ -> drawTick++ }
                    }
                    // 点行号 → 切换断点（行号区没有空间放红点，点行号是主要操作入口）
                    val tapState = intArrayOf(0)      // 0=空闲 1=按下 2=已取消
                    val tapDownY = floatArrayOf(0f)
                    val tapDownT = longArrayOf(0L)
                    setOnTouchListener { _, ev ->
                        val gutterW = measureLineNumber() + paddingLeft
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                if (ev.x < gutterW) {
                                    tapState[0] = 1
                                    tapDownY[0] = ev.y
                                    tapDownT[0] = System.currentTimeMillis()
                                    true
                                } else false
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (tapState[0] == 1) {
                                    if (abs(ev.y - tapDownY[0]) > 24f) tapState[0] = 2
                                    true
                                } else false
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                val isTap = tapState[0] == 1 &&
                                    System.currentTimeMillis() - tapDownT[0] < 500
                                tapState[0] = 0
                                if (isTap && ev.actionMasked == MotionEvent.ACTION_UP) {
                                    runCatching {
                                        val pos = layout.getCharPositionForLayoutOffset(
                                            ev.x - paddingLeft + offsetX,
                                            ev.y - paddingTop + offsetY
                                        )
                                        bpClickCb.value?.invoke(IntPair.getFirst(pos))
                                    }
                                }
                                isTap
                            }
                            else -> false
                        }
                    }
                }
            },
            onRelease = { ed ->
                runCatching { owner.value?.text = ed.text.toString() }
                if (editorRef.value === ed) editorRef.value = null
            }
        )

        // ── 断点标记覆盖层 ──
        // 行号区只够放数字，所以点画在代码左沿 + 整行淡红底，不占布局空间，
        // 也不依赖 Sora 的样式管线（styles 还没就绪也照样显示）。
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawTick; breakpointVersion
            drawBreakpointMarks(
                editor = editorRef.value,
                lines = bpLinesCb.value(),
                dotRadiusPx = dotRadius
            )
        }
    }
}

/** 查找替换浮层（横竖屏共用） */
@Composable
private fun FindOverlay(
    editorRef: MutableState<CodeEditor?>,
    findInfo: String,
    findCase: Boolean,
    findRegex: Boolean,
    onFindInfoChange: (String) -> Unit,
    onFindCaseChange: (Boolean) -> Unit,
    onFindRegexChange: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        FindReplacePanel(
            matchInfo = findInfo,
            caseInsensitive = findCase,
            useRegex = findRegex,
            onSearch = { q, ci, rx ->
                val ed = editorRef.value ?: return@FindReplacePanel
                if (q.isEmpty()) {
                    runCatching { ed.searcher.stopSearch() }
                    onFindInfoChange("")
                } else {
                    val opts = EditorSearcher.SearchOptions(
                        if (rx) EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
                        else EditorSearcher.SearchOptions.TYPE_NORMAL,
                        ci
                    )
                    runCatching { ed.searcher.search(q, opts) }
                    refreshFindInfo(editorRef, onFindInfoChange)
                }
            },
            onNext = {
                runCatching { editorRef.value?.searcher?.gotoNext() }
                refreshFindInfo(editorRef, onFindInfoChange)
            },
            onPrev = {
                runCatching { editorRef.value?.searcher?.gotoPrevious() }
                refreshFindInfo(editorRef, onFindInfoChange)
            },
            onReplace = { r ->
                runCatching { editorRef.value?.searcher?.replaceCurrentMatch(r) }
                refreshFindInfo(editorRef, onFindInfoChange)
            },
            onReplaceAll = { r ->
                runCatching { editorRef.value?.searcher?.replaceAll(r) }
                refreshFindInfo(editorRef, onFindInfoChange)
            },
            onToggleCase = onFindCaseChange,
            onToggleRegex = onFindRegexChange,
            onClose = onClose
        )
    }
}

private fun refreshFindInfo(
    editorRef: MutableState<CodeEditor?>,
    onFindInfoChange: (String) -> Unit
) {
    val s = editorRef.value?.searcher ?: return
    onFindInfoChange(
        if (s.hasQuery() && s.matchedPositionCount > 0)
            "${s.currentMatchedPositionIndex + 1}/${s.matchedPositionCount}"
        else ""
    )
}

/** 横屏左侧竖排工具栏的图标按钮（44dp 触控） */
@Composable
private fun VBarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 底部运行条：编译并运行 / 仅编译 / 调试 / 控制台 / 清空 */
@Composable
private fun RunBar(
    busy: Boolean,
    showConsole: Boolean,
    onRun: () -> Unit,
    onCompile: () -> Unit,
    onDebug: () -> Unit,
    onToggleConsole: () -> Unit,
    onClear: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onRun,
                enabled = !busy,
                modifier = Modifier.weight(1f).height(44.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(3.dp))
                Text("编译并运行", fontSize = 13.sp, maxLines = 1)
            }
            OutlinedButton(
                onClick = onCompile,
                enabled = !busy,
                modifier = Modifier.height(44.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Filled.Build, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(3.dp))
                Text("仅编译", fontSize = 13.sp, maxLines = 1)
            }
            IconButton(
                onClick = onDebug,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Filled.Troubleshoot, "调试")
            }
            IconButton(
                onClick = onToggleConsole,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    if (showConsole) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    "控制台"
                )
            }
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Filled.DeleteSweep, "清空")
            }
        }
    }
}

/**
 * 顶栏用的紧凑图标按钮：40dp 触控区 + 22dp 图标。
 * 比默认 48dp 省出约 32dp，保证「小熊猫C++」六个字能单行完整显示。
 */
@Composable
private fun CompactActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** 控制台：stdin 输入 + 输出，错误行可点击跳转 */
@Composable
private fun ConsolePanel(
    console: List<String>,
    stdin: String,
    onStdinChange: (String) -> Unit,
    onLineClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(console.size) {
        if (console.isNotEmpty()) listState.scrollToItem(console.size - 1)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = stdin,
            onValueChange = onStdinChange,
            placeholder = { Text("stdin：程序要读取的内容填这里", fontSize = 11.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            maxLines = 2,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(ConsoleBg)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(6.dp)) {
                items(console.size) { i ->
                    val line = console[i]
                    // 带 file:line:col 的诊断行可点击跳转
                    val isDiag = Regex("""^.*?:\d+:\d+:\s*(error|warning|note):""").containsMatchIn(line)
                    Text(
                        text = line,
                        color = if (isDiag) ConsoleDiagFg else ConsoleFg,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = if (isDiag) {
                            Modifier.fillMaxWidth().clickable { onLineClick(line) }
                        } else Modifier
                    )
                }
            }
        }
    }
}

/** 模板选择 */
@Composable
private fun TemplateDialog(
    templates: TemplateManager,
    onDismiss: () -> Unit,
    onPick: (TemplateManager.TemplateInfo, Boolean) -> Unit
) {
    val all = remember { templates.list() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从模板新建", fontSize = 16.sp) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(all.size) { i ->
                    val t = all[i]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp, horizontal = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    t.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    "${t.category} · ${t.description}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            TextButton(
                                onClick = { onPick(t, false) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("C", fontSize = 12.sp) }
                            TextButton(
                                onClick = { onPick(t, true) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("C++", fontSize = 12.sp) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
