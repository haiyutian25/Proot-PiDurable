// 顶层构建文件
// 注意：AGP 9.0+ 已内置 Kotlin 支持，不要再声明 org.jetbrains.kotlin.android
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
