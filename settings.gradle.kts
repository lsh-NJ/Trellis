pluginManagement {
    repositories {
        // Maven Central 在国内常被 TLS 重置（Remote host terminated the handshake）。
        // 阿里云 public 聚合了 Maven Central，放最前面；官方源留作兜底。
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        // Google 的官方源在国内不通；Compose Multiplatform 依赖 androidx.lifecycle 等构件，
        // 那些只在 Google Maven 上，所以这里挂一个可达的镜像。
        maven("https://maven.aliyun.com/repository/google")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // 同上：阿里云 public（聚合 Maven Central）优先，官方源兜底。
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
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
include(":features:goal-planning")
include(":ui-kernel")
include(":app:desktop")
