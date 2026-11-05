package dev.trellis.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 待办的手：分集要真的变成能勾的任务。
 *
 * 这不是「顺手多一个工具」——资源类回答如果只把分集写在正文里，
 * 用户拿到的是十行文字，不是十件能打勾的事。
 */
class TaskToolsTest {

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-tasks").toFile().apply { deleteOnExit() }

    @Test
    fun titlesBecomeRealTasks() = runTest {
        val runtime = AppRuntime.create(tempHome())
        val tool = TaskTools(runtime).all.single { it.name == "add_tasks" }

        val result = tool.call(
            buildJsonObject {
                put("confirmed", true)
                putJsonArray("titles") {
                    add("P2 操作系统的概念")
                    add("P3 操作系统的特征")
                    add("P4 操作系统的发展与分类")
                }
            },
        )

        assertTrue(!result.isError, result.content)
        assertEquals(3, runtime.tasks.size)
        assertTrue(runtime.tasks.any { it.title == "P3 操作系统的特征" })
        runtime.shutdown()
    }

    @Test
    fun badArgumentsAreExplainedInsteadOfCrashing() = runTest {
        val runtime = AppRuntime.create(tempHome())
        val tool = TaskTools(runtime).all.single { it.name == "add_tasks" }

        val missing = tool.call(buildJsonObject { put("confirmed", true) })
        assertTrue(missing.isError)
        assertTrue(missing.content.contains("titles"))

        val tooFew = tool.call(
            buildJsonObject {
                put("confirmed", true)
                put("titles", JsonArray(listOf()))
            },
        )
        assertTrue(tooFew.isError)
        assertEquals(0, runtime.tasks.size, "参数不对时不能落一半")

        val tooMany = tool.call(
            buildJsonObject {
                put("confirmed", true)
                put("titles", buildJsonArray { repeat(13) { add("第 $it 集") } })
            },
        )
        assertTrue(tooMany.isError)
        assertEquals(0, runtime.tasks.size)
        runtime.shutdown()
    }

    /**
     * 没得到用户同意，一条都不许加。
     *
     * 这是被用户当场指出的问题：agent 查到课就直接替人建了待办，用户问
     * 「你为什么没问我要待办还是计划」。写操作要先问，工具这边必须也有闸。
     */
    @Test
    fun withoutUserConsentNothingIsWritten() = runTest {
        val runtime = AppRuntime.create(tempHome())
        val tool = TaskTools(runtime).all.single { it.name == "add_tasks" }

        val result = tool.call(
            buildJsonObject {
                putJsonArray("titles") {
                    add("P2 操作系统的概念")
                    add("P3 操作系统的特征")
                }
            },
        )
        assertTrue(result.isError, "没带 confirmed 不该成功")
        assertTrue(result.content.contains("同意"), result.content)
        assertEquals(0, runtime.tasks.size)
        runtime.shutdown()
    }

    @Test
    fun longTitlesAreCutToWhatFitsInARow() = runTest {
        val runtime = AppRuntime.create(tempHome())
        val tool = TaskTools(runtime).all.single { it.name == "add_tasks" }
        tool.call(
            buildJsonObject {
                put("confirmed", true)
                putJsonArray("titles") {
                    add("一个特别特别特别特别特别特别特别特别特别长的分集标题用来测试截断行为")
                    add("短标题")
                }
            },
        )
        assertTrue(runtime.tasks.all { it.title.length <= 24 }, runtime.tasks.map { it.title }.toString())
        runtime.shutdown()
    }
}
