import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// 只用 Compose 的 application 配置，**不要**再叠加 Gradle 的 `application` 插件 
// 两个插件都会注册一个叫 `run` 的任务，直接冲突（"Cannot add task 'run'"）。
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinCompose)
}

dependencies {
    implementation(project(":ai"))
    implementation(project(":kernel"))
    implementation(project(":ui-kernel"))
    implementation(project(":sdk-domain"))
    implementation(project(":features:plan-core"))
    implementation(project(":plugin-dsl"))
    implementation(project(":plugin-script"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * 把运行时 classpath 收集到一个目录里，好让 jpackage 直接打包。
 *
 * 为什么要绕过 Compose 的 `createDistributable`：它在 Windows 上会去 GitHub 下 WiX，
 * 而 GitHub 在这台机器上不通。但 jlink 出来的运行时其实已经建好了，
 * 直接调 jpackage 就能出 exe。
 */
val stageForJpackage by tasks.registering(Copy::class) {
    from(configurations.runtimeClasspath)
    from(tasks.named("jar"))
    into(layout.buildDirectory.dir("jpackage-input"))
}

compose.desktop {
    application {
        mainClass = "dev.trellis.app.desktop.MainKt"

        // WSLg 之类没有硬件 OpenGL 的环境要切软件渲染。
        // 真实 Windows / macOS / Linux 桌面上不设置这个变量，让 Skiko 自己挑（通常是 D3D / Metal / GL）。
        val renderApi = System.getenv("SKIKO_RENDER_API")
        if (!renderApi.isNullOrBlank()) {
            jvmArgs += "-Dskiko.renderApi=" + renderApi
        }

        nativeDistributions {
            // AppImage = 一个自带 JVM 的目录 + 一个 exe，双击即用，不需要机器上装 Java。
            targetFormats(TargetFormat.AppImage)
            packageName = "Trellis"
            packageVersion = "1.0.0"
            description = "计划设定与追踪"
            vendor = "Trellis"

            // jlink 默认只打包「它推断需要」的模块，java.net.http 不在里面。
            // 不加这一行：编得过、测得过，但打出来的 exe 一点「生成」就
            // NoClassDefFoundError: java/net/http/HttpClient。
            modules("java.net.http")
        }
    }
}
