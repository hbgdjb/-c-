# 保留 Sora Editor 与必要类
-keep class io.github.rosemoe.** { *; }
-dontwarn io.github.rosemoe.**

# TextMate 引擎走反射，必须保留（若开启混淆）
-keep class org.eclipse.tm4e.** { *; }
-dontwarn org.eclipse.tm4e.**

-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
