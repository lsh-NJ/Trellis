pluginManagement {
    repositories {
        // Maven Central 在国内常被 TLS 重置（Remote host terminated the handshake）。
        // 阿里云 public 聚合了 Maven Central，放最前面；官方源留作兜底。
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        // Google 的官方源在国内不通；Compose Multiplatform 依赖 androidx.lifecycle 等构件，
        // 那些只在 Google Maven 上，所以这里挂一个可达的镜像。
        maven("https://maven.aliyun.com/repository/google")
        // Android Gradle Plugin 自己也在 Google 仓库里。阿里云的 gradle-plugin 镜像
        // 聚合了 AGP 与常见的 Gradle 插件，放在 gradlePluginPortal 前面能少走一次跨境。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        // 同上：阿里云 public（聚合 Maven Central）优先，官方源兜底。
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        google()
    }
}

rootProject.name = "trellis"

include(":kernel")
include(":demo")
include(":ai")
include(":plugin-dsl")
include(":plugin-script")
include(":sdk-domain")
include(":features:plan-core")
include(":features:learning-process")
include(":features:activity")
// 最小的 HTTP 出口：Java 11 的 java.net.http 安卓没有，必须按平台分叉。
include(":net")
include(":ui-kernel")
// 桌面与手机共用的运行时 + 界面（两套源集：jvm / android）。
include(":app:ui")
// 桌面壳：只剩一个 main() 与打包配置。
include(":app:desktop")
// 手机端安卓壳：Activity + 清单 + 图标 + edge-to-edge。
include(":app:mobile")
// 手机界面的桌面预览器：把同一份 Compose 代码按 390×844 渲染出来看。
include(":app:mobile-preview")
