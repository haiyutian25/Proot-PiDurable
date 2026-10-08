// feature:terminal:api —— 对外契约层（导航入口）。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lhzkml.spike.feature.terminal.api"
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
