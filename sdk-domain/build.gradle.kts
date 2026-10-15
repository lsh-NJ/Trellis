plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            // 领域契约需要 ExtensionPoint 来声明自己的扩展点，所以依赖内核；
            // 反过来内核绝不依赖这里  这是不变量 I2，由 verifyKernelPurity 守着。
            api(project(":kernel"))
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
