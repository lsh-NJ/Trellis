plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":plugin-dsl"))
    implementation(project(":sdk-domain"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.mozilla:rhino:1.7.15")

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
