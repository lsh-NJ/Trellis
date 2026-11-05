import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.composeMultiplatform)
}

/**
 * 发布签名的密钥参数。
 *
 * 文件不存在时不报错，见下面 `signingConfig` 的退化逻辑 —— `assembleRelease`
 * 在任何机器上都应该能跑出**可安装**的包，而不是因为缺一个本地文件直接失败。
 */
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

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

    /**
     * 发布签名。
     *
     * 密钥在仓库里（`app/mobile/trellis-release.jks`），密码在根目录的
     * `keystore.properties` —— 这是**演示用**密钥，目的很实际：任何人 clone 下来
     * 都能直接 `assembleRelease` 出可安装、可覆盖升级的包。
     * 真要上架请换成自己的密钥，并把 `keystore.properties` 移出版本控制。
     */
    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            /**
             * 不开混淆，这是权衡后的结论，不是省事。
             *
             * 内核、插件、声明式插件 DSL 全都靠**名字**找东西：脚本插件通过反射调宿主
             * API，`graph.user.json` / 清单 / 事件都按字符串键读写类名。混淆之后这些名字
             * 变成 a.b.c，已经装好的插件会成片失效 —— 而插件失效是不报错的，只是「点了没反应」。
             *
             * 体积代价可以接受（APK 约 13 MB，其中大头是 Compose 与 Rhino）。
             * 真要压缩，先给插件 API 面写完整的 keep 规则，再开 R8。
             */
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                // 没有密钥就退回 debug 签名：能装、能覆盖升级，只是不能被商店接受。
                signingConfigs.getByName("debug")
            }
        }
        debug {
            // debug 包也加上 applicationIdSuffix 之外的区分：这里不加后缀，
            // 保证 debug 与 release 共用同一个数据目录（filesDir 按 applicationId 分配），
            // 换包测试时笔记和计划不会各存一份。
            applicationIdSuffix = ""
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
