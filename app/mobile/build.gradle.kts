plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.composeMultiplatform)
}

/**
 * 手机端应用（安卓壳）。
 *
 * 这里只剩「安卓特有的那一层」：Activity、清单、图标、edge-to-edge。
 * 界面与运行时都在 `:app:ui` 的 `jvmAndAndroidMain` 里，和桌面共用同一份源码。
 */
android {
    namespace = "dev.trellis.app.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.trellis.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            // 不混淆：内核和插件都靠序列化按名字找东西，混淆会把它们变成谜语。
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":app:ui"))

    implementation(libs.androidx.activity.compose)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(libs.kotlinx.coroutines.core)

    debugImplementation(compose.uiTooling)
}
