plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinCompose)
}

/**
 * 手机界面预览器（桌面端）。
 *
 * 存在的理由很朴素：**安卓界面在电脑上看不见**。
 * 每改一次布局都要装一次 APK 才能确认，这个循环会让人放弃做自适应。
 *
 * 两种用法：
 *   ./gradlew :app:mobile-preview:run
 *       开一个 390×844 的窗口（Pixel 尺寸），可以真的点、真的输；
 *   TRELLIS_SHOT_DIR=build/preview ./gradlew :app:mobile-preview:run
 *       不开窗，直接离屏渲染成 PNG，用来在提交前逐张看。
 */
kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "dev.trellis.app.mobile.preview.PreviewMainKt"
    }
}

dependencies {
    implementation(project(":app:ui"))
    // 预览器要造「学习计划」的数据。走 :features:learning-process 的公开入口
    // （LearningPlanStore.open）而不是运行时的 internal 方法 —— 预览器是另一个模块，
    // 本来就不该看见运行时内部；同一份文件 + 同一套命令造出来的数据才和真机一致。
    implementation(project(":features:learning-process"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
}
