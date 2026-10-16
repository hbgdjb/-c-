plugins {
    id("com.android.application") version "8.7.3" apply false
    // Kotlin 必须 >= 2.2：Sora Editor 0.24.4 由 Kotlin 2.2.21 编译，metadata 2.2.0
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
