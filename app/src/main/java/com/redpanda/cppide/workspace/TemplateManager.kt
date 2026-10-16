package com.redpanda.cppide.workspace

import android.content.Context
import java.io.File

/**
 * 读取小熊猫C++ 官方模板（复用 RedPanda-C++ 的 templates 目录，GPLv3）。
 *
 * 每个模板目录包含 info.template（INI 元数据）以及若干 .txt 源码文件。
 */
class TemplateManager(private val context: Context) {

    data class TemplateInfo(
        val dir: String,
        val name: String,
        val category: String,
        val description: String,
        val cName: String,
        val cppName: String,
        val cFile: String?,
        val cppFile: String?
    )

    private var cache: List<TemplateInfo>? = null

    fun list(): List<TemplateInfo> {
        cache?.let { return it }
        val result = mutableListOf<TemplateInfo>()
        val dirs = context.assets.list("templates") ?: emptyArray()
        for (d in dirs.sorted()) {
            val infoText = runCatching {
                context.assets.open("templates/$d/info.template").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            val ini = parseIni(infoText)
            val unit = ini["Unit0"] ?: emptyMap()
            result += TemplateInfo(
                dir = d,
                name = pick(ini["Template"], "Name[zh_CN]") ?: pick(ini["Template"], "Name") ?: d,
                category = pick(ini["Template"], "Category[zh_CN]") ?: pick(ini["Template"], "Category") ?: "其他",
                description = pick(ini["Template"], "Description[zh_CN]") ?: pick(ini["Template"], "Description") ?: "",
                cName = unit["CName"] ?: "main.c",
                cppName = unit["CppName"] ?: "main.cpp",
                cFile = unit["C"],
                cppFile = unit["Cpp"]
            )
        }
        cache = result
        return result
    }

    fun categories(): List<String> = list().map { it.category }.distinct().sorted()

    /** 读取模板源码文本 */
    fun loadSource(t: TemplateInfo, cpp: Boolean): String {
        val fileName = if (cpp) t.cppFile else t.cFile
        val fallback = if (cpp) t.cppFile ?: t.cFile else t.cFile ?: t.cppFile
        val name = fileName ?: fallback ?: return defaultSource(cpp)
        return runCatching {
            context.assets.open("templates/${t.dir}/$name").bufferedReader().use { it.readText() }
        }.getOrElse { defaultSource(cpp) }
    }

    fun defaultSource(cpp: Boolean): String = if (cpp) {
        """
        #include <iostream>

        using namespace std;

        int main() {
            cout << "世界，你好！" << endl;
            return 0;
        }
        """.trimIndent()
    } else {
        """
        #include <stdio.h>

        int main() {
            printf("世界，你好！\n");
            return 0;
        }
        """.trimIndent()
    }

    private fun pick(map: Map<String, String>?, key: String): String? = map?.get(key)?.takeIf { it.isNotBlank() }

    private fun parseIni(text: String): Map<String, Map<String, String>> {
        val result = LinkedHashMap<String, MutableMap<String, String>>()
        var section = ""
        result[section] = mutableMapOf()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") || line.startsWith(";") -> {}
                line.startsWith("[") && line.endsWith("]") -> {
                    section = line.substring(1, line.length - 1).trim()
                    result.getOrPut(section) { mutableMapOf() }
                }
                line.contains('=') -> {
                    val idx = line.indexOf('=')
                    val k = line.substring(0, idx).trim()
                    val v = line.substring(idx + 1).trim()
                    result.getOrPut(section) { mutableMapOf() }[k] = v
                }
            }
        }
        return result
    }
}
