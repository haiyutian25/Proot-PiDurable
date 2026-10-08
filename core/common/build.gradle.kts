// core:common —— 无 Android 依赖的通用工具与基础类型。
// 刻意保持极小：只放被多个模块共用的东西，避免它变成"什么都往里塞"的杂物间。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lhzkml.spike.core.common"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
