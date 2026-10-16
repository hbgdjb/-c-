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

    private suspend fun ensureStarted(): Boolean = withContext(Dispatchers.IO) {
        if (shouldStop()) client.stop()
        // 生成 compile_commands.json，让 clangd 拿到准确的 sysroot / include 参数。
        // 这是跳转定义能否解析系统头的关键，fallbackFlags 只作兜底。
        runCatching {
            val sources = workspace.sourcesIn(workspace.root)
            if (sources.isNotEmpty()) {
                workspace.writeCompileCommands(sources, toolchain.ndkDir.absolutePath)
            }
        }
        client.start(workspace.root)
    }

    fun stop() = client.stop()
}
