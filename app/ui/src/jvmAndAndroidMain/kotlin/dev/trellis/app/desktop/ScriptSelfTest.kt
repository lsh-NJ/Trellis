package dev.trellis.app.desktop

import dev.trellis.ai.flow.Issue
import dev.trellis.ai.flow.SelfTestTool
import dev.trellis.script.ScriptSandbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把插件真跑一遍，看它到底画得出来画不出来。
 *
 * 这是整个工作流的地基。没有它，「让模型自己改到对」就是一句空话：
 * 模型只能看到「格式对不对」，看不到「画出来是什么」。
 * 装上之后才发现的错，用户已经坐在电脑前了。
 */
class ScriptSelfTest : SelfTestTool {

    override suspend fun test(files: Map<String, String>): List<Issue> {
        // 声明式插件没有脚本，也就没有「跑一遍」这回事。
        val source = files["main.js"] ?: return emptyList()
        val problems = mutableListOf<Issue>()
        val sandbox = ScriptSandbox(pluginId = "selftest", source = source)
        try {
            withContext(Dispatchers.Default) { sandbox.boot() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            return listOf(
                Issue(
                    code = "BOOT_FAILED",
                    where = "main.js",
                    message = "一启动就崩了：" + explain(t),
                    suggestion = "先检查语法，再看 trellis.register 的用法",
                ),
            )
        }

        try {
            val cards = sandbox.declaredCards()
            if (cards.isEmpty()) {
                problems += Issue(
                    code = "NO_CARD",
                    where = "main.js",
                    message = "一张卡片都没注册",
                    suggestion = "至少用 trellis.register 注册一张卡片",
                )
            }
            for (card in cards) {
                try {
                    withContext(Dispatchers.Default) { sandbox.render(card.id, sampleData()) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    problems += Issue(
                        code = "RENDER_FAILED",
                        where = "main.js",
                        message = "卡片 " + card.id + " 画不出来：" + explain(t),
                        suggestion = "render 必须返回一个对象，字段用宿主认识的节点名",
                    )
                }
            }
            for (handler in sandbox.declaredHandlers()) {
                try {
                    withContext(Dispatchers.Default) {
                        sandbox.runHandler(handler, sampleData(), emptyMap())
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    problems += Issue(
                        code = "HANDLER_FAILED",
                        where = "main.js",
                        message = "点「" + handler + "」的时候崩了：" + explain(t),
                        suggestion = "处理函数要扛得住空参数和空数据",
                    )
                }
            }
        } finally {
            runCatching { sandbox.close() }
        }
        return problems
    }

    private fun explain(t: Throwable): String =
        t.message ?: (t::class.simpleName ?: "未知错误")

    /**
     * 试跑用的假数据。
     *
     * 键名必须和 ScriptPlugin.read() 里给的**一模一样**，
     * 否则试跑通过、真跑白屏，等于没试。
     */
    private fun sampleData(): Map<String, Any?> {
        val now = System.currentTimeMillis()
        return linkedMapOf(
            "now" to now,
            "dayStart" to now,
            "dayEnd" to now,
            "summary" to linkedMapOf(
                "total" to 3,
                "done" to 1,
                "doing" to 0,
                "todo" to 2,
                "overdue" to 0,
                "dueToday" to 1,
                "spentMinutes" to 75,
            ),
            "tasks" to listOf(
                linkedMapOf(
                    "id" to "sample-task-1",
                    "title" to "示例任务",
                    "status" to "TODO",
                    "spentMinutes" to 0,
                ),
            ),
            "store" to linkedMapOf<String, String>(),
        )
    }
}
