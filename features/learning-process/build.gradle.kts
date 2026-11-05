plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()
    androidTarget()

    sourceSets {
        // 事件溯源 + SQL 全部共用：整块后端是标准 SQL，不该为了换平台重写。
        // 唯一的平台分叉是「谁来提供 JDBC 驱动」，见 Sqlite.kt。
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        commonMain.dependencies {
            api(project(":sdk-domain"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            // 存储适配器只在这层出现：领域与契约都不认识 JDBC。
            implementation(libs.sqlite.jdbc)
        }
        androidMain.dependencies {
            // 安卓上没有 sqlite-jdbc（带本地库的桌面 jar）；SQLDroid 把 JDBC 翻译成
            // android.database.sqlite。
            implementation(libs.sqldroid)
        }
    }
}

android {
    namespace = "dev.trellis.feature.learning"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
