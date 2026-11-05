package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工作流清单的手。
 *
 * agent 干活最难受的一点是**用户看不见它打算怎么干**：过程行是「第 3 步：调了
 * read_plugin」这种事后日志，等人读到的时候它已经在跑了。清单提前一步：
 * 它先把打算做的 2 到 12 件事写出来，之后逐条打勾，清单显示在右侧常驻栏里。
 *
 * 为什么是工具而不是让模型在回答里写清单：回答是给「结果」用的，清单是给
 * 「过程」用的 —— 两者混在一段文字里，界面就没法把勾选状态画出来，也没法
 * 在它跑偏时把某一步标红。工具调用的参数能落成结构化数据，界面才有的可画。
 */
class WorkflowTools(private val runtime: AppRuntime) {

    val guidance: String = listOf(
        "<how_to_plan>",
        "1. 一件事超过两步（先查清楚 → 再动手 → 再核对），就先调 set_plan 把计划列出来。",
        "2. steps 是 2 到 12 句人话，每句 6–18 个字、动词开头（查现有插件 / 调整首页布局 / 装上去 / 核对结果），别写内部黑话。",
        "3. 每步都可以带 impact：一句话说清这步要改动什么（改哪个卡片、哪个槽位、哪个插件），会显示在清单当前步的下方。",
        "4. 步骤之间如果有关联（后面这步依赖前面某步成功了才能做），用 dependsOn 写清是第几步（从 0 开始）；前一步失败，依赖它的步骤会自动标成「跳过」。",
        "5. 每完成一步立刻调 step_done：index 从 0 开始，ok=true 表示做成了；做不成也照样调，ok=false 并在 note 里写一句为什么。",
        "6. 清单会实时出现在右侧栏；用户关掉右栏时它会缩成输入框上方的常驻条，所以别在回答里再抄一遍清单。",
        "7. 中途计划要变（少一步/多一步），再调一次 set_plan 覆盖：已经打勾的步骤会保留勾。",
        "8. 需要用户拍板（做待办还是排计划、要不要改某个界面）时调 ask_choices：把 2–5 个选项摆到输入框上方，然后停下等用户点；不要自己替用户挑，也不要只在文字里口头问。",
        "</how_to_plan>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(SetPlan(), StepDone(), AskChoices())

    private inner class SetPlan : AgentTool {
        override val name: String = "set_plan"
        override val description: String =
            "开工前把计划列成清单（2 到 12 步）。清单显示在右侧常驻栏，之后用 step_done 逐条打勾。"
        override val parameters: String =
            "steps：数组，2..6 项。每项可以是字符串（一步的话），也可以是对象：{title：6–18 字、动词开头；" +
                "impact：可选，这步要改动什么；undoable：可选，这步能不能撤销；dependsOn：可选，依赖第几步（从 0 开始）}。" +
                "例：[\"查现有插件\",{\"title\":\"调整首页布局\",\"impact\":\"slot.home.section 排成两行\"}]"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val specs = parseSpecs(arguments["steps"])
            if (specs.size < 2 || specs.size > 12) {
                return ToolResult("set_plan 的 steps 需要 2 到 12 步，现在有 " + specs.size + " 步", isError = true)
            }
            return ToolResult(runtime.beginPlanSpecs(specs))
        }
    }

    private inner class StepDone : AgentTool {
        override val name: String = "step_done"
        override val description: String =
            "把清单里的某一步打勾（或标成没做成）。每做完一步就调一次，别攒到最后。"
        override val parameters: String =
            "index：第几步，从 0 开始；ok：true=做成了，false=没做成；note：可选，一句话说明（失败时必填）。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val index = arguments["index"]?.jsonPrimitive?.intOrNull
                ?: return ToolResult("step_done 需要 index（从 0 开始）", isError = true)
            val ok = arguments["ok"]?.jsonPrimitive?.booleanOrNull ?: true
            val note = arguments["note"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val text = runtime.finishPlanStep(index, ok, note)
            return ToolResult(text, isError = text.startsWith("清单里没有"))
        }
    }

    /**
     * 请用户拍板：把选项竖着摆到输入框上方。
     *
     * 为什么做成工具而不是运行时自己猜：要不要动待办/计划、要不要改界面，是用户的
     * 选择。运行时按「刚才查过资源」之类痕迹自动弹按钮，等于替用户做决定，之前就
     * 因此出过一次越界。把「问不问」的决定权交回模型，它问才弹。
     */
    private inner class AskChoices : AgentTool {
        override val name: String = "ask_choices"
        override val description: String =
            "需要用户拍板时，把 2–5 个选项竖着摆到输入框上方，等用户点。调完就停下，别继续动工具。"
        override val parameters: String =
            "question：一句话说清在问什么；options：数组 2..5 项，每项一句短话（≤12 字），" +
                "例：[\"落成待办\",\"排成学习计划\"]"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val question = arguments["question"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val options = (arguments["options"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            if (options.size < 2 || options.size > 5) {
                return ToolResult("ask_choices 的 options 需要 2 到 5 项，现在有 " + options.size + " 项", isError = true)
            }
            runtime.offerChoices(options)
            val head = if (question.isEmpty()) "" else "问的是：$question。"
            // 调完这一只之后不要再动别的工具；但也不能一句 JSON 都不吐，否则外层会判「格式不对」
            // 再要一轮，过程行里多出一条噪音。所以直接把「下一步该输出什么」写在这句话里。
            return ToolResult(
                head + "已经把 " + options.size + " 个选项竖着摆到输入框上方：" +
                    options.joinToString(" / ") + "。停下来等用户点。\n" +
                    "现在用一句话说清你为什么这么选、用户点每个会发生什么，" +
                    "然后用 {\"done\": true, \"message\": \"…\"} 结束这一轮（不要再调工具）。",
            )
        }
    }

    private fun parseSpecs(raw: JsonElement?): List<AppRuntime.PlanStepSpec> =
        (raw as? JsonArray)?.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { AppRuntime.PlanStepSpec(title = it) }

                is JsonObject -> {
                    val title = element["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (title.isEmpty()) {
                        null
                    } else {
                        AppRuntime.PlanStepSpec(
                            title = title,
                            impact = element["impact"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                            undoable = element["undoable"]?.jsonPrimitive?.booleanOrNull ?: false,
                            dependsOn = when (val dep = element["dependsOn"]) {
                                null -> emptyList()
                                is JsonPrimitive -> listOfNotNull(dep.intOrNull)
                                is JsonArray -> dep.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                                else -> emptyList()
                            },
                        )
                    }
                }

                else -> null
            }
        } ?: emptyList()
}
