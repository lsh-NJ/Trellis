plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * 题库：练习卡的数据模型 + JSONL 读取/校验 + 查询。
 *
 * 刻意保持「纯数据 + 纯逻辑」：只依赖 kotlinx.serialization，不依赖 `:net`、SQLite、
 * 内核或领域契约。这样它可以被桌面 UI、手机 UI 或一条离线脚本用同一种方式加载。
 *
 * 种子题库是 checked-in 的资源（`src/jvmMain/resources/exam-seed/exercises.jsonl`，
 * 由 `tools/exams/` 的管线生成）；运行时从 `<home>/exams/` 目录里的 jsonl 导入的入口见
 * `ExerciseFileStore`（jvmMain）。
 */
kotlin {
    jvm()
    androidTarget()

    sourceSets {
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        jvmAndAndroidMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        // 测试按 AGENTS 约定放在 src/jvmTest 下（没有 kotlin/ 中间层），这里补一个源码根。
        getByName("jvmTest").kotlin.srcDir("src/jvmTest")
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "dev.trellis.exams"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
