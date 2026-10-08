pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "proot-pi"

// ---- 应用壳 ----
include(":app")

// ---- 核心层 ----
// common：无 Android 依赖的通用工具
include(":core:common")
// domain：UDF 的核心（model + repository 接口 + usecase），纯 Kotlin
include(":core:domain")
// data：仓库实现与 PRoot 运行时
include(":core:data")
// ui：Compose 主题与通用组件
include(":core:ui")
// terminal：终端引擎（VT 模拟器 + 渲染 View + PTY JNI）
include(":core:terminal")

// ---- 功能层（api / impl 分离：api 只暴露导航入口，impl 含页面与 ViewModel）----
include(":feature:runtime:api")
include(":feature:runtime:impl")
include(":feature:terminal:api")
include(":feature:terminal:impl")
include(":feature:diagnostics:api")
include(":feature:diagnostics:impl")
