plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * ES5 脚本插件（Rhino 沙箱）。
 *
 * Rhino 是纯 Java 实现，桌面与安卓共用同一份字节码，所以这里没有平台分叉。
 * 唯一的要求是它不能碰任何 JVM-only 的 API —— `ScriptSandbox` 里已经按这个约束写了。
 */
kotlin {
    jvm()
    androidTarget()

    sourceSets {
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        jvmAndAndroidMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":plugin-dsl"))
            implementation(project(":sdk-domain"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.rhino)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "dev.trellis.script"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
