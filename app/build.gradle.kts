plugins {
    id("com.android.application")
    // Compose 编译器随 Kotlin 版本管理（AGP 9 内置 Kotlin，仍需显式声明此插件）
    id("org.jetbrains.kotlin.plugin.compose")
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
            // proot 必须从 nativeLibraryDir 以可执行形式释放
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            // 终端用的原生 PTY 桥（移植自 Noxs）
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    // Compose（用 BOM 统一版本）
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.5")

    // 生命周期 / ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
