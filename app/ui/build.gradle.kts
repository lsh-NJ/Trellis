plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * 桌面与手机**共用的那一整套界面与运行时**。
 *
 * 这里是 80 多个文件：今日、计划、复盘、聊天、知识库、知识网络、活动、插件、诊断、设置，
 * 加上 `AppRuntime` 那套装配（内核、计划后端、AI、会话、主题、管家）。
 *
 * 为什么不是「手机再写一套」：两套界面意味着每个功能要改两次，而且必然漂移 ——
 * 手机端会永远缺一个版本。所以这份代码编两次：一次给 jvm（桌面），
 * 一次给 android（手机）。真正平台相关的只剩很小的一圈，见 `HostPlatform`。
 *
 * 平台差异只有这几处，都集中在 `HostPlatform` / `AppFonts` / `PlatformMarkdownEditor`：
 * 剪贴板、截屏/点击/按键（AI 的「看屏幕」工具）、导出文件的保存位置、字体加载、WebView 编辑器。
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
            api(project(":kernel"))
            api(project(":sdk-domain"))
            api(project(":ui-kernel"))
            api(project(":features:plan-core"))
            api(project(":features:learning-process"))
            api(project(":features:activity"))
            // B 站工具与活动采集要发 GET；java.net.http 只有桌面有，所以走平台出口。
            implementation(project(":net"))
            api(project(":ai"))
            api(project(":plugin-dsl"))
            api(project(":plugin-script"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            // 桌面端 Dispatchers.Main 由 swing 变体提供；安卓由 coroutines-android 提供。
            implementation(libs.kotlinx.coroutines.swing)

            // 笔记编辑器的 WebView 路线（TRELLIS_EDITOR=webview 时启用）：
            // Compose 没有 CSS 引擎、段间距只能在 1x/2x 两档里选，所以用 JavaFX WebView 跑
            // Tiptap（浏览器内核 + CSS margin），段间距才能精确到 1.25–1.5 倍行距。
            // JavaFX 的 jar 全在 Maven 上，不需要运行时下载；macOS/Linux/Windows 各带一份 natives。
            val javafxVersion = "21.0.4"
            val javafxClassifier = run {
                val os = System.getProperty("os.name").lowercase()
                val arch = System.getProperty("os.arch").lowercase()
                when {
                    os.contains("mac") && arch.contains("aarch64") -> "mac-aarch64"
                    os.contains("mac") -> "mac"
                    os.contains("win") -> "win"
                    else -> "linux"
                }
            }
            listOf("base", "graphics", "controls", "web", "media", "swing").forEach { module ->
                implementation("org.openjfx:javafx-$module:$javafxVersion")
                implementation("org.openjfx:javafx-$module:$javafxVersion:$javafxClassifier")
            }
        }
        // 测试跟着代码走：这 60 多个用例直接调 AppRuntime 的 internal 方法
        // （dueLabel、endOfDayMs…），留在 :app:desktop 里就跨模块看不见了。
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}

android {
    namespace = "dev.trellis.app.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
