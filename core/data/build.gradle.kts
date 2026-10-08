// core:data —— 数据层：仓库实现 + PRoot 运行时。
//
// proot 家族的原生库（libproot.so / libproot-loader*.so / libtalloc.so /
// libandroid-shmem.so）随本模块走 jniLibs。打包后它们与 term-pty 一起落在同一个
// nativeLibraryDir，所以 RuntimePaths 无需关心它们来自哪个模块。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lhzkml.spike.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        // 本项目只支持 arm64：proot 与 rootfs 均为 aarch64
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs {
            // 关键：proot 必须从 nativeLibraryDir 以**未压缩**形式释放，否则无法 exec
            useLegacyPackaging = true
        }
    }
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:common"))
    implementation(project(":core:terminal"))

    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
