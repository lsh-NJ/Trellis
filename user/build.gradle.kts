plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * 账号系统内核：用户模型、PBKDF2 密码哈希、会话 token、文件存储、本地账号缓存、
 * 客户端 HTTP API。
 *
 * 不依赖界面，也不依赖同步引擎；桌面 App、Android App、`:sync-server` 共用同一份规则，
 * 避免「客户端一套校验、服务端一套校验」。
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
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "dev.trellis.user"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
