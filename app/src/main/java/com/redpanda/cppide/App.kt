package com.redpanda.cppide

import android.app.Application
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IThemeSource

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        setupTextMate()
    }

    /** 注册 TextMate 语法与主题（全局只需一次） */
    private fun setupTextMate() {
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))

        val themeRegistry = ThemeRegistry.getInstance()
        listOf("quietlight" to false, "dark_vs" to true).forEach { (name, dark) ->
            val path = "textmate/themes/$name.json"
            runCatching {
                themeRegistry.loadTheme(
                    ThemeModel(
                        IThemeSource.fromInputStream(
                            FileProviderRegistry.getInstance().tryGetInputStream(path), path, null
                        ),
                        name
                    ).apply { isDark = dark }
                )
            }
        }
        themeRegistry.setTheme("quietlight")

        runCatching {
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        }
    }
}
