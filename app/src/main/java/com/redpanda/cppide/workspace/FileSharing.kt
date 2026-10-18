package com.redpanda.cppide.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/**
 * 代码文件的导入 / 分享。
 *
 * - 分享走 FileProvider，**不需要任何存储权限**
 * - 导入走 SAF 系统文件选择器（ACTION_OPEN_DOCUMENT），**也不需要权限**
 */
object FileSharing {

    /** 把一个工作区文件分享出去（微信/QQ/蓝牙/邮件…） */
    fun share(context: Context, file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
        }.getOrNull() ?: return false

        val mime = when (file.extension.lowercase()) {
            "cpp", "cc", "cxx", "hpp", "hxx" -> "text/x-c++src"
            "c", "h" -> "text/x-csrc"
            "s", "asm" -> "text/plain"
            else -> "text/plain"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(intent, "分享 ${file.name}"))
            true
        }.getOrDefault(false)
    }

    /**
     * 从 SAF 返回的 Uri 导入到工作区。
     * 重名时自动加序号，返回导入后的文件；失败返回 null。
     */
    fun importFromUri(context: Context, uri: Uri, workspace: Workspace): File? {
        return runCatching {
            val raw = queryDisplayName(context, uri) ?: "imported.cpp"
            val safe = sanitizeName(raw)
            var target = File(workspace.root, safe)
            var i = 1
            while (target.exists()) {
                val base = safe.substringBeforeLast('.', safe)
                val ext = safe.substringAfterLast('.', "cpp")
                target = File(workspace.root, "$base($i).$ext")
                i++
            }
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { i2 ->
                target.outputStream().use { o -> i2.copyTo(o) }
            }
            target
        }.getOrNull()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return@use c.getString(idx)
                }
                null
            }
        }.getOrNull()
    }

    /** 去掉路径非法字符，保证有可识别的源码后缀 */
    private fun sanitizeName(raw: String): String {
        var name = raw.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_").trim()
        if (name.isEmpty()) name = "imported.cpp"
        val ext = name.substringAfterLast('.', "").lowercase()
        val known = setOf("c", "cpp", "cc", "cxx", "h", "hpp", "hxx", "s", "asm", "txt")
        if (ext.isEmpty() || ext !in known) name = "$name.cpp"
        return name
    }
}
