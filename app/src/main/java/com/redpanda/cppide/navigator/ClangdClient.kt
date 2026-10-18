package com.redpanda.cppide.navigator

import com.redpanda.cppide.compiler.ToolchainManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 一个定义位置 */
data class NavLocation(val file: String, val line: Int, val character: Int)

/**
 * 一条 LSP 诊断（行/列均为 0 基）。
 * severity：1=Error 2=Warning 3=Information 4=Hint（LSP 规范值）
 */
data class LspDiagnostic(
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
    val severity: Int,
    val message: String
)

/** 一处文本编辑（重命名用），行/列均为 0 基 */
data class TextEditRange(
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
    val newText: String
)

/** prepareRename 的结果：占位名（可能为空串，表示用光标处单词） */
data class RenamePrep(val placeholder: String)

/**
 * clangd 的 LSP 客户端（跑在安卓设备上的 aarch64 clangd）。
 *
 * 协议：stdin/stdout + `Content-Length: N\r\n\r\n<json>` 分帧的 JSON-RPC。
 * 相比文本解析，JSON 结构化，稳定性高得多。
 *
 * clangd 按需启动，空闲后自动关闭，避免常驻吃内存。
 */
class ClangdClient(private val toolchain: ToolchainManager) {

    fun clangd(): File = File(toolchain.ndkDir, "bin/clangd")
    fun isAvailable(): Boolean = clangd().exists()

    private var proc: Process? = null
    private var writer: OutputStream? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    @Volatile private var alive = false

    /** 服务端要求的文档同步方式：1=全文 2=增量（initialize 后确定） */
    @Volatile private var syncKind = 1

    /** clangd 推送诊断时的回调（在读取线程上触发，调用方需自行切回主线程） */
    @Volatile var onDiagnostics: ((uri: String, version: Int, items: List<LspDiagnostic>) -> Unit)? = null

    /** 已 didOpen 的文档 uri（clangd 要求先 open 才能 change） */
    private val openedUris = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 每个文档的当前版本号（必须单调递增） */
    private val docVersions = ConcurrentHashMap<String, Int>()

    /** 每个文档上一次同步给 clangd 的全文（增量同步时用于算替换范围） */
    private val docTexts = ConcurrentHashMap<String, String>()

    fun isRunning(): Boolean = alive

    /** 启动 clangd。fallbackFlags 让它认识我们的交叉编译 sysroot。 */
    suspend fun start(workDir: File): Boolean = withContext(Dispatchers.IO) {
        if (alive) return@withContext true
        if (!isAvailable()) return@withContext false
        clangd().setExecutable(true, false)

        val ndk = toolchain.ndkDir.absolutePath
        val env = toolchain.environment()
        val pb = ProcessBuilder(
            clangd().absolutePath,
            "--background-index=false",
            "--compile-commands-dir=${workDir.absolutePath}"
        ).redirectErrorStream(false)
        pb.environment().clear()
        pb.environment().putAll(env)

        val p = runCatching { pb.start() }.getOrNull() ?: return@withContext false
        proc = p
        writer = p.outputStream
        alive = true
        startReader(p)

        val init = JSONObject().apply {
            put("processId", android.os.Process.myPid())
            put("rootUri", "file://${workDir.absolutePath}")
            put(
                "capabilities",
                JSONObject().apply {
                    put("textDocument", JSONObject().apply {
                        put("definition", JSONObject().apply { put("dynamicRegistration", false) })
                        put("hover", JSONObject().apply { put("dynamicRegistration", false) })
                        put("references", JSONObject().apply { put("dynamicRegistration", false) })
                        put("rename", JSONObject().apply { put("dynamicRegistration", false) })
                        put("synchronization", JSONObject().apply {
                            put("dynamicRegistration", false)
                            put("willSave", false)
                            put("willSaveWaitUntil", false)
                            put("didSave", false)
                        })
                        put("publishDiagnostics", JSONObject().apply {
                            put("relatedInformation", true)
                        })
                    })
                }
            )
            put(
                "initializationOptions",
                JSONObject().apply {
                    put(
                        "fallbackFlags",
                        JSONArray(
                            listOf(
                                "--target=aarch64-linux-android24",
                                "--sysroot=$ndk/sysroot",
                                "-resource-dir=$ndk/lib/clang/21",
                                "-I$ndk/sysroot/usr/include",
                                "-I$ndk/lib/clang/21/include",
                                "-std=c++17"
                            )
                        )
                    )
                }
            )
        }
        val initResp = request("initialize", init, timeoutMs = 30_000) ?: run {
            stop()
            return@withContext false
        }
        syncKind = parseSyncKind(initResp)
        notify("initialized", JSONObject())
        true
    }

    /** 从 initialize 响应里读服务端要求的同步方式；读不到就按全文同步 */
    private fun parseSyncKind(resp: JSONObject): Int {
        val caps = resp.optJSONObject("result")?.optJSONObject("capabilities") ?: return 1
        return when (val sync = caps.opt("textDocumentSync")) {
            is Int -> sync
            is JSONObject -> sync.optInt("change", 1)
            else -> 1
        }
    }

    private fun startReader(p: Process) {
        Thread {
            try {
                val br = p.inputStream.bufferedReader()
                while (alive) {
                    // 读响应头
                    val headers = HashMap<String, String>()
                    while (true) {
                        val line = br.readLine() ?: return@Thread
                        if (line.isEmpty()) break
                        val idx = line.indexOf(':')
                        if (idx > 0) {
                            headers[line.substring(0, idx).trim().lowercase()] =
                                line.substring(idx + 1).trim()
                        }
                    }
                    val len = headers["content-length"]?.toIntOrNull() ?: continue
                    val buf = CharArray(len)
                    var read = 0
                    while (read < len) {
                        val n = br.read(buf, read, len - read)
                        if (n < 0) return@Thread
                        read += n
                    }
                    val text = String(buf, 0, len)
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    val id = msg.optInt("id", -1)
                    val method = msg.optString("method", "")
                    if (method.isNotEmpty()) {
                        if (id > 0) {
                            // 服务端 → 客户端的请求（如 window/workDoneProgress/create），
                            // 必须回一个结果，否则 clangd 会一直等，可能卡住后续能力。
                            replyEmpty(id)
                        } else if (method == "textDocument/publishDiagnostics") {
                            dispatchDiagnostics(msg.optJSONObject("params"))
                        }
                    } else if (id > 0) {
                        // 响应
                        pending.remove(id)?.complete(msg)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                alive = false
                pending.values.forEach { it.cancel() }
                pending.clear()
            }
        }.also { it.isDaemon = true }.start()
    }

    /** 发送请求并等待响应 */
    private suspend fun request(
        method: String,
        params: JSONObject,
        timeoutMs: Long = 15_000
    ): JSONObject? {
        val w = writer ?: return null
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val body = payload.toString()
        val header = "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n"
        return try {
            synchronized(w) {
                w.write(header.toByteArray(Charsets.UTF_8))
                w.write(body.toByteArray(Charsets.UTF_8))
                w.flush()
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (_: Throwable) {
            null
        } finally {
            pending.remove(id)
        }
    }

    /** 回复服务端发来的请求：result 为 null（表示不支持该能力） */
    private fun replyEmpty(id: Int) {
        val w = writer ?: return
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", JSONObject.NULL)
        }.toString()
        val header = "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n"
        runCatching {
            synchronized(w) {
                w.write(header.toByteArray(Charsets.UTF_8))
                w.write(body.toByteArray(Charsets.UTF_8))
                w.flush()
            }
        }
    }

    private fun notify(method: String, params: JSONObject) {
        val w = writer ?: return
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }.toString()
        val header = "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n"
        runCatching {
            synchronized(w) {
                w.write(header.toByteArray(Charsets.UTF_8))
                w.write(body.toByteArray(Charsets.UTF_8))
                w.flush()
            }
        }
    }

    /**
     * 把文档内容同步给 clangd。
     * 首次调用发 didOpen；之后再次调用自动转为 didChange 刷新内容（避免重复 open）。
     */
    suspend fun didOpen(file: File, text: String, languageId: String): Unit =
        withContext(Dispatchers.IO) {
            val uri = "file://${file.absolutePath}"
            if (openedUris.contains(uri)) {
                didChange(file, text)
                return@withContext
            }
            openedUris.add(uri)
            docVersions[uri] = 1
            docTexts[uri] = text
            val item = JSONObject().apply {
                put("uri", uri)
                put("languageId", languageId)
                put("version", 1)
                put("text", text)
            }
            notify(
                "textDocument/didOpen",
                JSONObject().apply { put("textDocument", item) }
            )
        }

    /**
     * 把编辑后的全文同步给 clangd（触发实时诊断）。
     *
     * 兼容两种同步方式：服务端要增量时，用「覆盖整个文档」的 range 做整篇替换，
     * 这在增量与全文两种模式下都合法。
     */
    suspend fun didChange(file: File, text: String): Unit =
        withContext(Dispatchers.IO) {
            if (syncKind == 0) return@withContext
            val uri = "file://${file.absolutePath}"
            // clangd 要求先 didOpen 才能 didChange
            if (!openedUris.contains(uri)) {
                val lang = if (file.extension.equals("c", true)) "c" else "cpp"
                didOpen(file, text, lang)
                return@withContext
            }
            val version = (docVersions[uri] ?: 1) + 1
            docVersions[uri] = version
            val oldText = docTexts[uri] ?: ""
            val change = JSONObject().apply {
                if (syncKind == 2) {
                    // 增量模式：用一个覆盖「旧文档全文」的 range 做整篇替换。
                    // range 必须按旧文档坐标计算，否则 clangd 会认为越界。
                    val oldLines = oldText.split('\n')
                    val endLine = (oldLines.size - 1).coerceAtLeast(0)
                    val endChar = oldLines.getOrNull(endLine)?.length ?: 0
                    put(
                        "range",
                        JSONObject().apply {
                            put("start", JSONObject().put("line", 0).put("character", 0))
                            put(
                                "end",
                                JSONObject().put("line", endLine).put("character", endChar)
                            )
                        }
                    )
                }
                put("text", text)
            }
            docTexts[uri] = text
            val params = JSONObject().apply {
                put(
                    "textDocument",
                    JSONObject().apply {
                        put("uri", uri)
                        put("version", version)
                    }
                )
                put("contentChanges", JSONArray().put(change))
            }
            notify("textDocument/didChange", params)
        }

    /** 关闭文档（离开编辑器时调用，避免 clangd 留着过期内容） */
    suspend fun didClose(file: File) = withContext(Dispatchers.IO) {
        val uri = "file://${file.absolutePath}"
        openedUris.remove(uri)
        docVersions.remove(uri)
        docTexts.remove(uri)
        notify(
            "textDocument/didClose",
            JSONObject().apply {
                put("textDocument", JSONObject().put("uri", uri))
            }
        )
    }

    /** 查找引用：返回所有引用位置 */
    suspend fun references(
        file: File,
        line: Int,
        character: Int,
        includeDeclaration: Boolean
    ): List<NavLocation> = withContext(Dispatchers.IO) {
        val params = JSONObject().apply {
            put("textDocument", JSONObject().put("uri", "file://${file.absolutePath}"))
            put("position", JSONObject().put("line", line).put("character", character))
            put("context", JSONObject().put("includeDeclaration", includeDeclaration))
        }
        val resp = request("textDocument/references", params) ?: return@withContext emptyList()
        val result = resp.opt("result") ?: return@withContext emptyList()
        parseLocations(result)
    }

    /** 准备重命名：确认该处可重命名并拿到当前占位名；不可重命名返回 null */
    suspend fun prepareRename(file: File, line: Int, character: Int): RenamePrep? =
        withContext(Dispatchers.IO) {
            val params = JSONObject().apply {
                put("textDocument", JSONObject().put("uri", "file://${file.absolutePath}"))
                put("position", JSONObject().put("line", line).put("character", character))
            }
            val resp = request("textDocument/prepareRename", params) ?: return@withContext null
            when (val result = resp.opt("result")) {
                is JSONObject ->
                    if (result.has("placeholder")) RenamePrep(result.optString("placeholder"))
                    else if (result.has("start")) RenamePrep("")
                    else null
                is JSONArray -> RenamePrep("")
                else -> null
            }
        }

    /**
     * 重命名：返回「文件绝对路径 -> 编辑列表」。
     * clangd 可能返回 changes 或 documentChanges 两种形式，都做兼容。
     */
    suspend fun rename(
        file: File,
        line: Int,
        character: Int,
        newName: String
    ): Map<String, List<TextEditRange>> = withContext(Dispatchers.IO) {
        val params = JSONObject().apply {
            put("textDocument", JSONObject().put("uri", "file://${file.absolutePath}"))
            put("position", JSONObject().put("line", line).put("character", character))
            put("newName", newName)
        }
        val resp = request("textDocument/rename", params, timeoutMs = 20_000)
            ?: return@withContext emptyMap()
        val result = resp.optJSONObject("result") ?: return@withContext emptyMap()

        val out = LinkedHashMap<String, List<TextEditRange>>()

        // 形式一：changes: { "file://...": [TextEdit] }
        result.optJSONObject("changes")?.let { changes ->
            val keys = changes.keys()
            while (keys.hasNext()) {
                val uri = keys.next()
                if (!uri.startsWith("file://")) continue
                val arr = changes.optJSONArray(uri) ?: continue
                out[uri.removePrefix("file://")] = parseTextEdits(arr)
            }
        }

        // 形式二：documentChanges: [ { textDocument: {uri}, edits: [...] } ]
        result.optJSONArray("documentChanges")?.let { arr ->
            for (i in 0 until arr.length()) {
                val doc = arr.optJSONObject(i) ?: continue
                val uri = doc.optJSONObject("textDocument")?.optString("uri") ?: continue
                if (!uri.startsWith("file://")) continue
                val edits = doc.optJSONArray("edits") ?: continue
                out[uri.removePrefix("file://")] = parseTextEdits(edits)
            }
        }
        out
    }

    private fun parseTextEdits(arr: JSONArray): List<TextEditRange> {
        val edits = ArrayList<TextEditRange>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val range = e.optJSONObject("range") ?: continue
            val s = range.optJSONObject("start") ?: continue
            val en = range.optJSONObject("end") ?: continue
            edits += TextEditRange(
                startLine = s.optInt("line"),
                startChar = s.optInt("character"),
                endLine = en.optInt("line"),
                endChar = en.optInt("character"),
                newText = e.optString("newText")
            )
        }
        return edits
    }

    /** 把 publishDiagnostics 通知解析成结构化数据并回调出去 */
    private fun dispatchDiagnostics(params: JSONObject?) {
        params ?: return
        val uri = params.optString("uri", "")
        val version = params.optInt("version", -1)
        val arr = params.optJSONArray("diagnostics") ?: JSONArray()
        val items = ArrayList<LspDiagnostic>(arr.length())
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val range = d.optJSONObject("range") ?: continue
            val s = range.optJSONObject("start") ?: continue
            val e = range.optJSONObject("end") ?: continue
            items += LspDiagnostic(
                startLine = s.optInt("line"),
                startChar = s.optInt("character"),
                endLine = e.optInt("line"),
                endChar = e.optInt("character"),
                severity = d.optInt("severity", 1),
                message = d.optString("message", "")
            )
        }
        runCatching { onDiagnostics?.invoke(uri, version, items) }
    }

    /** 跳转定义：返回可跳转的位置列表 */
    suspend fun definition(
        file: File,
        line: Int,
        character: Int
    ): List<NavLocation> = withContext(Dispatchers.IO) {
        val params = JSONObject().apply {
            put(
                "textDocument",
                JSONObject().put("uri", "file://${file.absolutePath}")
            )
            put("position", JSONObject().put("line", line).put("character", character))
        }
        val resp = request("textDocument/definition", params) ?: return@withContext emptyList()
        val result = resp.opt("result") ?: return@withContext emptyList()
        parseLocations(result)
    }

    /** 悬停信息（显示符号的类型/签名） */
    suspend fun hover(file: File, line: Int, character: Int): String? =
        withContext(Dispatchers.IO) {
            val params = JSONObject().apply {
                put(
                    "textDocument",
                    JSONObject().put("uri", "file://${file.absolutePath}")
                )
                put("position", JSONObject().put("line", line).put("character", character))
            }
            val resp = request("textDocument/hover", params) ?: return@withContext null
            val contents = resp.optJSONObject("result")?.opt("contents")
            when (contents) {
                is JSONObject -> contents.optString("value").ifBlank { null }
                is String -> contents.ifBlank { null }
                is JSONArray -> {
                    val parts = mutableListOf<String>()
                    for (i in 0 until contents.length()) {
                        val e = contents.optJSONObject(i)
                        if (e != null) parts += e.optString("value")
                    }
                    parts.filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { null }
                }
                else -> null
            }
        }

    private fun parseLocations(result: Any): List<NavLocation> {
        val out = mutableListOf<NavLocation>()
        val items = when (result) {
            is JSONArray -> (0 until result.length()).mapNotNull { result.opt(it) }
            is JSONObject -> listOf(result)
            else -> emptyList()
        }
        for (item in items) {
            val obj = item as? JSONObject ?: continue
            val uri = obj.optString("uri").ifBlank { null } ?: continue
            if (!uri.startsWith("file://")) continue
            val range = obj.optJSONObject("range") ?: obj.optJSONObject("targetRange") ?: continue
            val start = range.optJSONObject("start") ?: continue
            out += NavLocation(
                file = uri.removePrefix("file://"),
                line = start.optInt("line"),
                character = start.optInt("character")
            )
        }
        return out
    }

    fun stop() {
        alive = false
        runCatching { proc?.destroyForcibly() }
        proc = null
        writer = null
        // clangd 重启后文档状态清空，需要重新 didOpen
        openedUris.clear()
        docVersions.clear()
        docTexts.clear()
    }
}
