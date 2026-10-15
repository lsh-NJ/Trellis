plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

/**
 * 不变量 I2（设计文档 4.8）：内核不认识领域。
 *
 * 「:kernel 源码里搜不到 Plan / Task / Goal 这些词」必须是机器验证的，
 * 不能靠自觉。否则半年后一定有人在内核里写一个 TaskStore，然后说「就这一次」。
 *
 * 只扫代码、跳过注释：注释里提一句 Agent 不构成依赖，但 TaskStore 是实打实的耦合。
 */
val kernelForbiddenWords = listOf(
    "Plan", "Task", "Goal", "Session", "Streak", "Habit",
    "Education", "Student", "Lesson", "Course", "Homework",
    "AI", "LLM", "Agent", "Prompt", "Rag",
)

val verifyKernelPurity by tasks.registering {
    group = "verification"
    description = "断言内核没有泄漏领域概念（不变量 I2）"

    val srcDir = layout.projectDirectory.dir("src/commonMain/kotlin")
    inputs.dir(srcDir)

    doLast {
        val blockComment = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        val lineComment = Regex("//.*")
        // 匹配规则：左边界不是字母，且右边不接小写字母。
        //   TaskStore   -> 命中（右边是 S，不是小写）
        //   checkTask   -> 漏（左边是 r） 可接受，我们要抓的是「领域类型泄漏」
        //   MAIN        -> 不命中（左边是 M）
        //   AI / LLM    -> 命中独立的 AI，不会把 MAIN / available 误伤
        val patterns = kernelForbiddenWords.map {
            it to Regex("(?<![A-Za-z])" + it + "(?![a-z])")
        }
        val offenders = mutableListOf<String>()

        srcDir.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sorted()
            .forEach { file ->
                // 把块注释替换成等量空行，保持行号不漂移
                val withoutBlocks = blockComment.replace(file.readText()) { m ->
                    "\n".repeat(m.value.count { it == '\n' })
                }
                val code = lineComment.replace(withoutBlocks, "")

                code.lines().forEachIndexed { index, line ->
                    for ((word, pattern) in patterns) {
                        if (pattern.containsMatchIn(line)) {
                            offenders += "${file.name}:${index + 1}  命中 [$word]  ->  ${line.trim()}"
                        }
                    }
                }
            }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "内核里出现了领域词汇，不变量 I2 被破坏。\n" +
                    "正确做法是把它做成扩展点，而不是写进内核：\n" +
                    offenders.joinToString("\n"),
            )
        }
        logger.lifecycle("verifyKernelPurity: 内核保持领域中立")
    }
}

tasks.named("check") {
    dependsOn(verifyKernelPurity)
}