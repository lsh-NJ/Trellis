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
        "1. **先问再写**：加待办会改用户的数据。没得到用户明确同意（「加待办」「记下来」「建成任务」）之前，",
        "   不要调 add_tasks；先说明你挑了什么，然后调 ask_choices 让用户选：只看建议 / 落成待办 / 排成学习计划。",
        "   选项会竖着摆到输入框上方，调完就停下等用户点，别自己替用户挑。",
        "2. 用户同意后，用 add_tasks 把每一条建成待办：标题就是那一集/那一章的名字（截到 24 字内），",
        "   必须带 confirmed=true —— 这个参数就是「用户确实要了」的证据，别默认填。",
        "3. 一次 2–12 条，别把 80 集一次全建；先建用户这一周真会看的那几条。",
        "4. set_plan 只写这一轮的动作（搜索 / 挑集 / 建任务），待办才是留给用户勾的东西；",
        "   用户要的是「排成学习计划」而不是待办时，用 create_learning_plan（那是计划页的入口）。",
        "</how_to_add_tasks>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(AddTasks())

    private inner class AddTasks : AgentTool {
        override val name: String = "add_tasks"
        override val description: String =
            "把几条待办任务加进列表（标题必填，一次最多 12 条）。会改用户数据：只有用户明确说要加待办时才调用。"
        override val parameters: String =
            "titles：字符串数组，每条一个待办标题（2–12 条，每条不超过 24 字）；" +
                "confirmed：布尔，只有用户明确同意加待办时才传 true。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val confirmed = arguments["confirmed"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            if (!confirmed) {
                return ToolResult(
                    "还没得到用户同意，没有加待办。\n" +
                        "先别调这个工具：调 ask_choices 让用户选「只看建议 / 落成待办 / 排成计划」，调完停下等用户点。\n" +
                        "用户点了、或者明确说「加待办」之后，再带 confirmed=true 调一次。",
                    isError = true,
                )
            }
            val raw = arguments["titles"] as? JsonArray
                ?: return ToolResult("titles 要写成字符串数组，比如 [\"P2 操作系统的概念\", \"P3 操作系统的特征\"]", isError = true)
            val titles = raw.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                .filter { it.isNotEmpty() }
                .map { if (it.length > 24) it.take(24) else it }
            if (titles.size < 2) {
                return ToolResult("add_tasks 至少要 2 条，现在有 " + titles.size + " 条", isError = true)
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
