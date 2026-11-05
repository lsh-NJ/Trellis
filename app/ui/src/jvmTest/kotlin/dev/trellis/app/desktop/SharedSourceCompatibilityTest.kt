package dev.trellis.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 共享源集（jvm + android 编同一份）里，不许出现只有桌面 JVM 才有的 JDK 包。
 *
 * 这条测试是一次真实事故的产物：`BilibiliClient` 直接 import 了 `java.net.http.HttpClient`。
 * 桌面编得过、跑得好，安卓上只要那个类被加载就 `NoClassDefFoundError`——而
 * `NoClassDefFoundError.message` 恰好是类名，于是用户看到的报错是
 * 「出错了：dev.trellis.app.desktop.BilibiliClient」，既没有「网络」也没有「B 站」。
 *
 * 编译器不会拦这种错：KMP 的 expect/actual 只管「声明在哪」，管不了「JDK 有没有这个包」。
 * 所以只能靠静态扫描。加一个新的平台依赖时，如果它确实只有桌面有，就把它放进
 * `jvmMain` 的 actual（参考 `:net` 的 `httpGetText`），并把这里的规则放宽一格。
 *
 * 手机端接手时这一条尤其重要：只要共享层里出现一个 `java.awt`，APK 一启动就是闪退，
 * 而报错里只有一个类名。
 */
class SharedSourceCompatibilityTest {

    /** 安卓（ART）没有的包。命中即失败。 */
    private val forbiddenPackages = listOf(
        "java.net.http",      // Java 11 的 HttpClient，安卓一直没有
        "java.awt",           // 桌面窗口/鼠标/截图
        "javax.swing",        // EDT
        "javafx",             // 笔记编辑器的 WebView 路线（只有桌面）
        "javax.imageio",      // 图片解码（安卓用 BitmapFactory）
        "javax.script",       // 脚本引擎（脚本插件用 Rhino 自己的 API）
        "java.beans",
        "java.rmi",
        "javax.naming",
        "java.lang.management",
        "com.sun.",           // JDK 内部实现
    )

    /** 安卓上存在但 API 级别偏高的方法。 */
    private val forbiddenCalls = listOf(
        // API 34 才有；安卓侧统一用 Files.readAllBytes / Files.write。
        "Files.readString(",
        "Files.writeString(",
    )

    @Test
    fun `共享源集不引用安卓没有的 JDK 包`() {
        // Gradle 跑测试时的工作目录是模块目录（app/ui）。
        val roots = listOf(
            "src/jvmAndAndroidMain",
            "../../features/activity/src/jvmAndAndroidMain",
            "../../features/learning-process/src/jvmAndAndroidMain",
            "../../ai/src/jvmAndAndroidMain",
            "../../plugin-script/src/jvmAndAndroidMain",
            "../../plugin-dsl/src/jvmAndAndroidMain",
        ).map(::File)

        val offenders = mutableListOf<String>()
        var scanned = 0

        roots.filter { it.isDirectory }.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    scanned++
                    val text = file.readText()
                    text.lineSequence().forEachIndexed { index, line ->
                        val at = "${file.path}:${index + 1}"
                        val imported = line.trim().removePrefix("import ").substringBefore(' ')
                        val badPackage = forbiddenPackages.firstOrNull { pkg ->
                            line.trimStart().startsWith("import ") && imported.startsWith(pkg)
                        }
                        if (badPackage != null) {
                            offenders += "$at  导入了 $badPackage（安卓没有这个包）"
                        }
                        val badCall = forbiddenCalls.firstOrNull { line.contains(it) }
                        if (badCall != null) {
                            offenders += "$at  调用了 $badCall（安卓 API 级别不够）"
                        }
                    }
                }
        }

        assertTrue(scanned > 40, "扫描到的文件太少（$scanned），路径可能不对，测试没有真的生效")
        assertTrue(
            offenders.isEmpty(),
            "共享源码里出现了平台专属 API —— 桌面能编，安卓一加载类就崩：\n" +
                offenders.joinToString("\n"),
        )
    }
}
