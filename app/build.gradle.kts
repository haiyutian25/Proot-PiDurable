// app —— 应用壳。
//
// 这里只保留「装配」职责：Application、宿主 Activity、前台服务、以及把各 feature
// 的实现模块聚合成一个 APK。业务逻辑一律下沉到 feature:impl 与 core:*。
//
// 原生库与 CMake 已随 core:data / core:terminal 走，本模块不再声明 externalNativeBuild。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lhzkml.spike"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lhzkml.spike"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // 本项目只支持 arm64：proot 与 rootfs 均为 aarch64
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // 关键：proot 必须从 nativeLibraryDir 以未压缩形式释放，否则无法 exec。
            // 各模块的 jniLibs 最终都汇入同一个 APK，此处统一约束即可。
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // 功能层（impl 会间接带入各自的 api）
    implementation(project(":feature:runtime:impl"))
    implementation(project(":feature:terminal:impl"))
    implementation(project(":feature:diagnostics:impl"))

    // 核心层
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":core:terminal"))
    implementation(project(":core:common"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
