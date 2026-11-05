plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
}

/**
 * 最小的网络出口：一次 HTTP GET 取文本。
 *
 * 为什么单独一个模块：`features:activity`（ActivityWatch）与 `:app:ui`（B 站工具）
 * 都要发 GET，而 `:app:ui` 依赖 `:features:activity`，反过来不行。放谁家都会绕圈，
 * 所以下沉成这个只有一份实现的小模块。
 *
 * 更重要的是它把 `java.net.http` 关在了桌面侧 —— 安卓上那个包不存在，
 * 留在共享源码里就是启动即崩。
 */
kotlin {
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "dev.trellis.net"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
