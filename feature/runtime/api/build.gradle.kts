// feature:runtime:api —— 极薄的对外契约层。
//
// 对齐参考项目的划分：api 只暴露「别的模块需要知道的东西」（这里是导航入口），
// 不含任何 UI 与实现细节，所以它不依赖 Compose，依赖成本几乎为零。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lhzkml.spike.feature.runtime.api"
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
    implementation(project(":core:common"))
}
