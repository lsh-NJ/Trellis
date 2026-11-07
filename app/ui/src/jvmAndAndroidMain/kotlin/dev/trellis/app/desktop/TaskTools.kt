package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 任务的手。
 *
 * 「把 B 站某门课的分集排成计划」这件事，清单（set_plan）只能装这一轮的**动作**：
 * 搜、挑、写成任务。真正给用户逐条打勾的是待办列表 —— 所以分集要被建成任务，
 * 否则用户拿不到任何可勾选的东西，分集名就只是一段回答里的文字。
 */
class TaskTools(private val runtime: AppRuntime) {

    val guidance: String = listOf(
        "<how_to_add_tasks>",
        "1. **不用问「待办还是计划」**：自己判断——一次性、几条能勾完的 → 用 add_tasks；",
        "   长期、要按周推进的 → 用 create_learning_plan。用户已经在要你做这件事时直接动手，别摆选项让他再选一遍；",
        "   只有「这件事到底要不要现在做」不清楚时才问一句。",
        "2. 判断成待办之后，用 add_tasks 把每一条建成待办：标题就是那一集/那一章的名字（截到 24 字内），",
        "   必须带 confirmed=true —— 用户已经把这件事交给你了，这就是「要了」的证据；完全没头绪时才先问一句。",
        "3. 一次 1–12 条，别把 80 集一次全建；先建用户这一周真会看的那几条。",
        "4. set_plan 只写这一轮的动作（搜索 / 挑集 / 建任务），待办才是留给用户勾的东西；",
        "   用户要的是「排成学习计划」而不是待办时，用 create_learning_plan（那是计划页的入口）。",
        "</how_to_add_tasks>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(AddTasks())

    private inner class AddTasks : AgentTool {
        override val name: String = "add_tasks"
        override val description: String =
            "把几条待办任务加进列表（标题必填，一次最多 12 条）。会改用户数据：用户要这件事变成可勾的待办（或你判断这是一次性任务）时调用。"
        override val parameters: String =
            "titles：字符串数组，每条一个待办标题（1–12 条，每条不超过 24 字）；" +
                "confirmed：布尔，只有用户明确同意加待办时才传 true。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val confirmed = arguments["confirmed"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            if (!confirmed) {
                return ToolResult(
                    "还没得到用户同意，没有加待办。\n" +
                        "先判断形态：一次性、几条能勾完的 → 这就是待办，确认用户确实要这件事后带 confirmed=true 再调一次；\n" +
                        "长期、要按周推进的 → 改用 create_learning_plan，别在这里加待办。",
                    isError = true,
                )
            }
            val raw = arguments["titles"] as? JsonArray
                ?: return ToolResult("titles 要写成字符串数组，比如 [\"P2 操作系统的概念\", \"P3 操作系统的特征\"]", isError = true)
            val titles = raw.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                .filter { it.isNotEmpty() }
                .map { if (it.length > 24) it.take(24) else it }
            if (titles.isEmpty()) {
                // 以前下限是 2，用户只说「加一条待办」时模型只能自己编一条凑数
                // （实测它造了个「买牛奶（备用）」）。一条也是正常请求。
                return ToolResult("titles 不能为空：至少要 1 条。", isError = true)
            }
            if (titles.size > 12) {
                return ToolResult("add_tasks 一次最多 12 条，现在有 " + titles.size + " 条", isError = true)
            }
            titles.forEach { runtime.addTask(it) }
            val total = runtime.tasks.size
            return ToolResult(
                "加了 " + titles.size + " 条待办（现在共 " + total + " 条）：" + titles.joinToString("、") +
                    "\n用户可以在「任务」页逐条勾掉。",
            )
        }
    }
}
