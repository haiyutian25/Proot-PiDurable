// core:domain —— UDF 的核心层。
//
// 方案文档硬性要求：UI → Intent → ViewModel → UseCase → Repository → Runtime。
// 因此 model / repository 接口 / usecase 必须独立于实现存在，且**不依赖 Android**
// （不引 android.*，纯 Kotlin + 协程），这样上层规则不会被平台细节污染。
//
// 注意：参考项目把 model 与 repository 接口放在 core:data 里且没有 UseCase 层，
// 这里为满足方案文档的 UDF 要求而有意不同。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lhzkml.spike.core.domain"
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
    api(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
}
