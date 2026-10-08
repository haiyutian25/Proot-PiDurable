// 顶层构建文件：只声明插件，不 apply。
// AGP 9.0+ 已内置 Kotlin 支持，无需再声明 org.jetbrains.kotlin.android。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
