plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":plugin-dsl"))
    implementation(project(":sdk-domain"))
    implementation(project(":features:plan-core"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("dev.trellis.demo.MainKt")
}
