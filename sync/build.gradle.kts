plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
}

/**
 * 多端数据同步内核。
 *
 * 这里只放「与界面无关」的东西：HLC、对象/head/blob 协议、传输抽象、本地状态、
 * 文件域适配器、同步引擎。它不依赖 :app:ui 的任何 store，方便单测和未来复用
 * （桌面、Android、以后可能的网页端/CLI）。
 *
 * 平台差异只有文件 IO 与 HTTP：文件 IO 两端都是 java.io/java.nio，所以主体放在
 * jvmAndAndroidMain；以后加 WebDAV 时，HTTP 出口复用 :net 的平台分叉。
 */
kotlin {
    jvm()
    androidTarget()

    sourceSets {
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "dev.trellis.sync"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
