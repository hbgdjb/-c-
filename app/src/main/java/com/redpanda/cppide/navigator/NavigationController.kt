package com.redpanda.cppide.navigator

import com.redpanda.cppide.compiler.ToolchainManager
import com.redpanda.cppide.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导航控制器：管理 clangd 生命周期（按需启动、空闲自动关闭）。
 */
class NavigationController(
    private val toolchain: ToolchainManager,
    private val workspace: Workspace
) {
    private val client = ClangdClient(toolchain)
    private var lastUse = 0L

    /** 空闲 3 分钟后自动关闭 clangd，避免常驻吃内存 */
    private fun shouldStop(): Boolean = System.currentTimeMillis() - lastUse > 180_000

    /** clangd 推送诊断时的回调（在后台线程触发） */
    var onDiagnostics: ((uri: String, version: Int, items: List<LspDiagnostic>) -> Unit)?
        get() = client.onDiagnostics
        set(value) { client.onDiagnostics = value }

    fun isRunning(): Boolean = client.isRunning()

    suspend fun gotoDefinition(
        file: java.io.File,
        line: Int,
        character: Int
    ): List<NavLocation> = withContext(Dispatchers.IO) {
        if (!ensureStarted()) return@withContext emptyList()
        lastUse = System.currentTimeMillis()
        client.definition(file, line, character)
    }

    suspend fun hover(
        file: java.io.File,
        line: Int,
        character: Int
    ): String? = withContext(Dispatchers.IO) {
        if (!ensureStarted()) return@withContext null
        lastUse = System.currentTimeMillis()
        client.hover(file, line, character)
    }

    /** 调用方在即将跳转前同步文档内容 */
    suspend fun didOpen(
        file: java.io.File,
        text: String,
        languageId: String
    ) = withContext(Dispatchers.IO) {
        if (ensureStarted()) client.didOpen(file, text, languageId)
    }

    /** 编辑后同步全文，触发 clangd 重新诊断 */
    suspend fun didChange(
        file: java.io.File,
        text: String
    ) = withContext(Dispatchers.IO) {
        if (ensureStarted()) {
            lastUse = System.currentTimeMillis()
            client.didChange(file, text)
        }
    }

    suspend fun didClose(file: java.io.File) = withContext(Dispatchers.IO) {
        if (client.isRunning()) client.didClose(file)
    }

    /** 查找引用 */
    suspend fun references(
        file: java.io.File,
        line: Int,
        character: Int,
        includeDeclaration: Boolean = true
    ): List<NavLocation> = withContext(Dispatchers.IO) {
        if (!ensureStarted()) return@withContext emptyList()
        lastUse = System.currentTimeMillis()
        client.references(file, line, character, includeDeclaration)
    }

    suspend fun prepareRename(
        file: java.io.File,
        line: Int,
        character: Int
    ): RenamePrep? = withContext(Dispatchers.IO) {
        if (!ensureStarted()) return@withContext null
        lastUse = System.currentTimeMillis()
        client.prepareRename(file, line, character)
    }

    suspend fun rename(
        file: java.io.File,
        line: Int,
        character: Int,
        newName: String
    ): Map<String, List<TextEditRange>> = withContext(Dispatchers.IO) {
        if (!ensureStarted()) return@withContext emptyMap()
        lastUse = System.currentTimeMillis()
        client.rename(file, line, character, newName)
    }

    private suspend fun ensureStarted(): Boolean = withContext(Dispatchers.IO) {
        if (shouldStop()) client.stop()
        // 生成 compile_commands.json，让 clangd 拿到准确的 sysroot / include 参数。
        // 这是跳转定义能否解析系统头的关键，fallbackFlags 只作兜底。
        // 只在 clangd 尚未运行时重建，避免每次按键都写文件。
        if (!client.isRunning()) {
            runCatching {
                val sources = workspace.sourcesIn(workspace.root)
                if (sources.isNotEmpty()) {
                    workspace.writeCompileCommands(sources, toolchain.ndkDir.absolutePath)
                }
            }
        }
        client.start(workspace.root)
    }

    fun stop() = client.stop()
}
