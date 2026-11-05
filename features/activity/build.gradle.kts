plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * 活动采集与整理（ActivityWatch → 分类 → 日汇总）。
 *
 * 取数、分类、SQL 全部共用；平台差异只有两处：
 * - HTTP：走 `:net`（安卓没有 `java.net.http`）；
 * - SQLite 驱动：SQLDroid 在安卓上把 JDBC 翻译成 `android.database.sqlite`。
 */
kotlin {
    jvm()
    androidTarget()

    sourceSets {
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        jvmAndAndroidMain.dependencies {
            implementation(project(":net"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            // 原始事件落地成本机 SQLite。这是这个模块存在的理由，不是可替换的实现细节。
            implementation(libs.sqlite.jdbc)
        }
        androidMain.dependencies {
            implementation(libs.sqldroid)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "dev.trellis.activity"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
