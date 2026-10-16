package com.redpanda.cppide

import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.redpanda.cppide.compiler.*
import com.redpanda.cppide.debugger.DebugController
import com.redpanda.cppide.debugger.DebugPhase
import com.redpanda.cppide.debugger.StackFrame
import com.redpanda.cppide.debugger.Variable
import com.redpanda.cppide.editor.CppCompletionLanguage
import com.redpanda.cppide.editor.EditorActions
import com.redpanda.cppide.navigator.NavLocation
import com.redpanda.cppide.navigator.NavigationController
import com.redpanda.cppide.ui.*
import com.redpanda.cppide.workspace.StorageManager
import com.redpanda.cppide.workspace.TemplateManager
import com.redpanda.cppide.workspace.Workspace
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
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
    var navResults by remember { mutableStateOf<List<NavLocation>>(emptyList()) }
    /** 横屏右侧面板当前显示哪个（false=控制台, true=调试） */
    var sideIsDebug by remember { mutableStateOf(false) }

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
        val scopeName = if (of.file.extension.equals("c", true)) "source.c" else "source.cpp"
        // 包一层，在 TextMate 基础上追加 C/C++ 关键字与片段补全
        runCatching {
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

    fun toggleBreakpointAtCursor() {
        val of = openFiles.getOrNull(activeIndex) ?: run { log("请先打开一个源文件"); return }
        val ed = editorRef.value ?: return
        val path = of.file.absolutePath
        val line = ed.cursor.leftLine + 1
        scope.launch {
            if (debugger.hasBreakpoint(path, line)) {
                debugger.deleteBreakpoint(path, line)
                log("已删除断点 ${of.file.name}:$line")
            } else {
                debugger.setBreakpoint(path, line)
                log("已添加断点 ${of.file.name}:$line")
            }
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
                navResults = results
            }
        }
    }

    fun jumpToNav(loc: NavLocation) {
        val f = java.io.File(loc.file)
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
                                    modifier = Modifier.fillMaxSize()
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
                                                    scope.launch { debugger.deleteBreakpoint(bp.file, bp.line) }
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
                            modifier = Modifier.fillMaxSize()
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

                    // 控制台
                    if (showConsole) {
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
                                scope.launch { debugger.deleteBreakpoint(bp.file, bp.line) }
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

    // ── 跳转定义结果 ──
    if (navResults.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { navResults = emptyList() },
            title = { Text("跳转到定义", fontSize = 16.sp) },
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

    // ── 设置 ──
    if (showSettings) {
        SettingsDialog(
            options = options,
            themeMode = themeMode,
            fontSize = fontSize,
            storage = storage,
            onAbout = { showAbout = true },
            onDismiss = { showSettings = false },
            onConfirm = { o, t, fs ->
                options = o; themeMode = t; fontSize = fs
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
    modifier: Modifier = Modifier
) {
    // 记住当前编辑的文件，销毁时把内容写回去
    val owner = remember { mutableStateOf(file) }
    SideEffect { owner.value = file }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CodeEditor(ctx).apply {
                typefaceText = Typeface.MONOSPACE
                setTextSize(fontSize)
                runCatching {
                    colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                }
                editorRef.value = this
                val f = owner.value
                if (f != null) {
                    runCatching { setText(f.text) }
                    runCatching {
                        val isC = f.file.extension.equals("c", true)
                        setEditorLanguage(
                            CppCompletionLanguage(
                                TextMateLanguage.create(
                                    if (isC) "source.c" else "source.cpp",
                                    true
                                )
                            )
                        )
                    }
                }
            }
        },
        onRelease = { ed ->
            runCatching { owner.value?.text = ed.text.toString() }
            if (editorRef.value === ed) editorRef.value = null
        }
    )
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
