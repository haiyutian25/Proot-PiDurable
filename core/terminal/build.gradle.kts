// core:terminal —— 终端引擎：VT 模拟器 + 渲染 View + PTY 原生桥。
//
// 移植自 Noxs（已逐行 diff 确认与原版无实质差异）。它与 Linux 发行版无关，
// 只依赖一个"能读写的字节流"，所以可以独立成模块复用。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lhzkml.spike.core.terminal"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            // 终端用的原生 PTY 桥（term-pty.c）
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(project(":core:common"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
