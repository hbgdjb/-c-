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
        request("initialize", init, timeoutMs = 30_000) ?: run {
            stop()
            return@withContext false
        }
        notify("initialized", JSONObject())
        true
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
                    if (id > 0) {
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

    /** 把文档内容同步给 clangd（必须） */
    suspend fun didOpen(file: File, text: String, languageId: String) =
        withContext(Dispatchers.IO) {
            val item = JSONObject().apply {
                put("uri", "file://${file.absolutePath}")
                put("languageId", languageId)
                put("version", 1)
                put("text", text)
            }
            notify(
                "textDocument/didOpen",
                JSONObject().apply { put("textDocument", item) }
            )
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
    }
}
