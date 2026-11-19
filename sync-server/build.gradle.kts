plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

/**
 * 同步服务端：一个只有 GET/PUT/LIST 的薄 HTTP 对象服务，给 VPS 或家里常开设备用。
 *
 * 设计上故意没有数据库、没有账号、没有 TLS：TLS 和 token 由前面的网关负责
 * （见 docs/multi-device-sync.md §12 / docs/cloud-roadmap.md 的隧道试跑）。
 * 它只做「按路径读写对象 + 列目录」，正好对应 `SyncTransport` 的三个方法。
 */
application {
    mainClass.set("dev.trellis.sync.server.MainKt")
}

dependencies {
    implementation(project(":sync"))
    implementation(project(":user"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":user"))
}
