package com.redpanda.cppide.compiler

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File

/**
 * 管理内置的 C/C++ 编译器工具链（termux-ndk r29 aarch64，静态 musl 构建）。
 *
 * 工具链以 tar.xz 形式打包在 APK 的 assets/toolchain 下，首次启动时解压到
 * 应用私有目录 filesDir/ndk，并设置可执行权限。
 *
 * 注：由于 targetSdk=28，应用私有目录下的文件可以正常 execve。
 */
class ToolchainManager(private val context: Context) {

    /** 工具链根目录：/data/data/<pkg>/files/ndk */
    val ndkDir: File get() = File(context.filesDir, "ndk")

    private val marker: File get() = File(ndkDir, ".installed")

    /**
     * 工具链打包版本。改动内置工具链内容时**必须**递增，
     * 这样老用户升级 App 后会自动重新解压，拿到新版本（例如瘦身后的）。
     */
    private val revision = "4"

    fun isInstalled(): Boolean {
        if (!marker.exists() || !clangxx().exists()) return false
        val content = runCatching { marker.readText() }.getOrDefault("")
        return content.startsWith("installed:$revision:")
    }

    /** C++ 编译器可执行文件 */
    fun clangxx(): File = File(ndkDir, "bin/clang++")

    /** C 编译器可执行文件 */
    fun clang(): File = File(ndkDir, "bin/clang")

    fun sysroot(): File = File(ndkDir, "sysroot")

    /** clang 资源目录（lib/clang/<版本>），用于定位内建头文件与运行时库 */
    fun resourceDir(): File {
        val base = File(ndkDir, "lib/clang")
        return base.listFiles()?.firstOrNull { it.isDirectory } ?: base
    }

    fun versionName(): String = resourceDir().name

    /** 编译工作目录 */
    fun workDir(): File = File(context.filesDir, "work").apply { mkdirs() }

    /**
     * 首次安装：从 assets 解压工具链并赋予可执行权限。
     * 耗时操作，需在后台线程执行。
     */
    fun install(onProgress: (String) -> Unit) {
        if (isInstalled()) {
            onProgress("编译器已就绪（clang ${versionName()}）")
            return
        }
        ndkDir.deleteRecursively()
        ndkDir.mkdirs()

        onProgress("正在释放编译器文件…")
        context.assets.open("toolchain/ndk-aarch64.tar.xz").use { raw ->
            XZCompressorInputStream(raw, true).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var count = 0
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val target = File(ndkDir, entry.name)
                        if (!target.canonicalPath.startsWith(ndkDir.canonicalPath)) continue

                        when {
                            entry.isDirectory -> target.mkdirs()

                            entry.isSymbolicLink -> {
                                target.parentFile?.mkdirs()
                                target.delete()
                                // 先尝试创建符号链接；失败则稍后由 fixupLinks 兜底
                                runCatching { Os.symlink(entry.linkName, target.absolutePath) }
                            }

                            else -> {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { out -> tar.copyTo(out) }
                                if (entry.mode and 0b001_001_001 != 0) {
                                    target.setExecutable(true, false)
                                }
                                count++
                                if (count % 1000 == 0) onProgress("正在释放编译器文件… $count")
                            }
                        }
                    }
                }
            }
        }

        onProgress("正在配置编译器…")
        fixupLinks()
        File(ndkDir, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        prepareRuntimeLibs()

        marker.writeText("installed:$revision:${versionName()}")
        onProgress("编译器安装完成（clang ${versionName()}）")
    }

    /**
     * 运行时动态库目录：只放 libc++_shared.so。
     *
     * 编译默认用 -static-libstdc++ 静态链接 libc++，理论上不需要它；
     * 但若链接实际为动态，运行时会缺 libc++_shared.so，故此处做兜底。
     *
     * 注意：**不能**把整个 sysroot/lib 目录加进 LD_LIBRARY_PATH——
     * 那里面的 libc.so/libm.so 是链接期用的 stub，运行时加载会崩溃。
     */
    fun runtimeLibDir(): File = File(context.filesDir, "libs")

    private fun prepareRuntimeLibs() {
        val src = File(sysroot(), "usr/lib/aarch64-linux-android/libc++_shared.so")
        if (!src.exists()) return
        val dst = File(runtimeLibDir(), "libc++_shared.so")
        runCatching {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            dst.setReadable(true, true)
        }
    }

    /**
     * 保证 bin 目录下的关键命令可用。
     * 若符号链接创建失败（部分设备限制），则退化为复制真实文件。
     */
    private fun fixupLinks() {
        val bin = File(ndkDir, "bin")
        val links = mapOf(
            "clang" to "clang-21",
            "clang++" to "clang-21",
            "cc" to "clang-21",
            "ld.lld" to "lld"
        )
        for ((link, target) in links) {
            val linkFile = File(bin, link)
            val targetFile = File(bin, target)
            val usable = linkFile.exists() && runCatching { linkFile.length() }.getOrDefault(0L) > 0L
            if (usable) continue
            if (!targetFile.exists()) continue
            linkFile.delete()
            val linked = runCatching { Os.symlink(target, linkFile.absolutePath) }.isSuccess
            if (!linked) {
                runCatching { targetFile.copyTo(linkFile, overwrite = true) }
            }
            linkFile.setExecutable(true, false)
        }
    }

    /** 供 ProcessBuilder 使用的环境变量 */
    fun environment(): Map<String, String> = mapOf(
        "PATH" to "${File(ndkDir, "bin").absolutePath}:/system/bin:/system/xbin",
        "HOME" to context.filesDir.absolutePath,
        "TMPDIR" to File(context.cacheDir, "tmp").apply { mkdirs() }.absolutePath,
        // 兜底：若编译产物动态依赖 libc++_shared.so，从私有库目录加载
        "LD_LIBRARY_PATH" to runtimeLibDir().absolutePath,
        "LC_ALL" to "C",
        "LANG" to "C",
        "TERM" to "xterm"
    )
}
