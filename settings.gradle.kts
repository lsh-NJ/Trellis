pluginManagement {
    repositories {
        mavenCentral()
        // Google 的官方源在国内不通；Compose Multiplatform 依赖 androidx.lifecycle 等构件，
        // 那些只在 Google Maven 上，所以这里挂一个可达的镜像。
        maven("https://maven.aliyun.com/repository/google")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
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
include(":ui-kernel")
include(":app:desktop")
