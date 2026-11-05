plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.composeMultiplatform) apply false

    // AGP 必须**同时**在根工程声明（apply false），不能只在子工程里 `alias`。
    //
    // 原因不是洁癖，是类加载器：根工程 plugins 块里的插件会被装进根的项目级
    // classloader，子工程再 apply 时复用同一个实例；如果 AGP 只出现在子工程的
    // plugins 块里，它会落在另一层 classloader 中，而 Kotlin 插件（在根那层）
    // 反射读 `CurrentAndroidGradlePluginVersion` 时就看不见它，报的错是：
    //   > Can't infer current AndroidGradlePluginVersion: Is the Android plugin applied?
    // 看起来像版本不兼容，其实是「两个插件不在同一个类加载器里」。
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinAndroid) apply false
}
