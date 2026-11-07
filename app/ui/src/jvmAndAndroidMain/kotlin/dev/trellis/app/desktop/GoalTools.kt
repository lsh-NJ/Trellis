package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 目标 / 项目的手。
 *
 * 计划回答「这周做什么」，目标回答「为什么做、做完算什么样」。工具只做两件事：
 * 把用户说的目标/验收落下来，以及把计划挂到目标上；周表、逾期、复盘仍归计划工具。
 */
class GoalTools(private val runtime: AppRuntime) {

    val guidance: String = listOf(
        "<how_to_use_goals>",
        "1. 用户说「我想学 X / 我想做一个 Y」时，先分清是**目标**（为什么要、怎么算做到）还是**一次性任务**：",
        "   要跨周推进、能说清验收的 → 先 create_goal 把目标/验收落下来，再 create_learning_plan，最后 link_plan_to_goal；",
        "   只是做一件事 → 用 add_tasks，别硬造目标。",
        "2. 目标里最重要的是 success（怎么算做到了）。用户没说就替他补一句合理的验收，但要在回复里说清你补了什么。",
        "3. 里程碑是阶段验收（「跑通最小链路」「过一遍真题」），不是每天的安排；每天做哪件在计划页。",
        "4. 写操作都要 confirmed=true：用户已经把这件事交给你了才动手；拿不准先问一句。",
        "5. list_goals 是只读的：不确定现在有没有目标、目标下挂没挂计划，先看一眼再决定。",
        "</how_to_use_goals>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(
        ListGoals(),
        CreateGoal(),
        AddMilestone(),
        CompleteMilestone(),
        LinkPlanToGoal(),
        ArchiveGoal(),
    )

    private fun confirmed(arguments: JsonObject): Boolean =
        arguments["confirmed"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

    private inner class ListGoals : AgentTool {
        override val name: String = "list_goals"
        override val description: String = "列出目标和项目（含里程碑、关联的计划、状态）。只读。"
        override val parameters: String = "无参数。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            runtime.refreshGoals()
            val goals = runtime.goals
            if (goals.isEmpty()) return ToolResult("现在还没有目标。")
            return ToolResult(
                goals.joinToString("\n") { goal ->
                    buildString {
                        append("- [").append(goal.id).append("] ").append(goal.title)
                        append("（").append(if (goal.kind == "project") "项目" else "目标")
                        append("/").append(goal.status).append("）")
                        goal.deadline?.let { append("，截止 ").append(it) }
                        if (goal.success.isNotBlank()) append("；验收：").append(goal.success)
                        if (goal.milestones.isNotEmpty()) {
                            append("；里程碑：").append(
                                goal.milestones.joinToString("、") { m ->
                                    "[${m.id}] ${m.title}${if (m.status == "done") "✓" else ""}"
                                },
                            )
                        }
                        if (goal.plans.isNotEmpty()) {
                            append("；计划：").append(goal.plans.joinToString("、") { it.title })
                        }
                    }
                },
            )
        }
    }

    private inner class CreateGoal : AgentTool {
        override val name: String = "create_goal"
        override val description: String =
            "新建一个目标或项目（标题、验收必填；可选截止日期）。会改用户数据：用户交代了长期想做成的事、且你能说清验收时调用。"
        override val parameters: String =
            "title：字符串，这件事叫什么；" +
                "success：字符串，怎么算做到了（用户没说就替他补一句合理验收，并在回复里说明）；" +
                "kind：可选，goal 或 project；" +
                "intent：可选，为什么要做；" +
                "deadline：可选，YYYY-MM-DD；" +
                "confirmed：布尔，用户明确同意建这个目标时才传 true。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            if (!confirmed(arguments)) {
                return ToolResult(
                    "还没得到用户同意，没有建目标。\n" +
                        "先确认这件事是长期目标（不是一次性待办），再带 confirmed=true 调一次。",
                    isError = true,
                )
            }
            val title = arguments["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val success = arguments["success"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isEmpty()) return ToolResult("title 不能为空。", isError = true)
            if (success.isEmpty()) return ToolResult("success（验收）不能为空：说清怎么算做到了。", isError = true)
            val kind = arguments["kind"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "goal" }
            val intent = arguments["intent"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val deadline = arguments["deadline"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            val goalId = runtime.createGoal(title, kind, intent, success, deadline)
                ?: return ToolResult(runtime.goalMessage ?: "目标没建成。", isError = true)
            return ToolResult(
                "目标建好了：[${goalId}] ${title}\n" +
                    "验收：${success}\n" +
                    "下一步：用 create_learning_plan 排学习计划，再用 link_plan_to_goal 挂到这个目标上。",
            )
        }
    }

    private inner class AddMilestone : AgentTool {
        override val name: String = "add_milestone"
        override val description: String =
            "给目标/项目加一条里程碑（阶段验收）。会改用户数据：能说清「做完什么算迈过一步」时调用。"
        override val parameters: String =
            "goalId：字符串，目标 id（list_goals 返回的方括号里的值）；" +
                "title：字符串，里程碑内容；" +
                "deadline：可选，YYYY-MM-DD；" +
                "confirmed：布尔。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            if (!confirmed(arguments)) return ToolResult("还没得到用户同意，没有加里程碑。", isError = true)
            val goalId = arguments["goalId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val title = arguments["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (goalId.isEmpty()) return ToolResult("goalId 不能为空（先用 list_goals 查）。", isError = true)
            if (title.isEmpty()) return ToolResult("title 不能为空。", isError = true)
            val deadline = arguments["deadline"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            runtime.addGoalMilestone(goalId, title, deadline)
            return ToolResult(runtime.goalMessage ?: "里程碑加上了：$title")
        }
    }

    private inner class CompleteMilestone : AgentTool {
        override val name: String = "complete_milestone"
        override val description: String = "把一条里程碑勾成完成。会改用户数据：用户说这件事做完了时才调用。"
        override val parameters: String =
            "milestoneId：字符串，里程碑 id（list_goals 返回的方括号里的值）；confirmed：布尔。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            if (!confirmed(arguments)) return ToolResult("还没得到用户确认，没有勾里程碑。", isError = true)
            val milestoneId = arguments["milestoneId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (milestoneId.isEmpty()) return ToolResult("milestoneId 不能为空（先用 list_goals 查）。", isError = true)
            runtime.completeGoalMilestone(milestoneId)
            return ToolResult(runtime.goalMessage ?: "里程碑完成。")
        }
    }

    private inner class LinkPlanToGoal : AgentTool {
        override val name: String = "link_plan_to_goal"
        override val description: String =
            "把学习计划挂到目标/项目上（goalId 传空字符串表示解绑）。会改用户数据：计划刚建好、或用户要求挂起来时调用。"
        override val parameters: String =
            "planId：字符串，" + "计划 id（create_learning_plan 的返回或 get_learning_plan 里能看到）；" +
                "goalId：字符串，目标 id；传空字符串表示解绑；" +
                "confirmed：布尔。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            if (!confirmed(arguments)) return ToolResult("还没得到用户同意，没有改关联。", isError = true)
            val planId = arguments["planId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (planId.isEmpty()) return ToolResult("planId 不能为空。", isError = true)
            val goalId = arguments["goalId"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            runtime.linkPlanToGoal(planId, goalId)
            return ToolResult(runtime.goalMessage ?: "关联已更新。")
        }
    }

    private inner class ArchiveGoal : AgentTool {
        override val name: String = "archive_goal"
        override val description: String = "归档一个目标/项目（不再接新计划，历史保留）。会改用户数据。"
        override val parameters: String =
            "goalId：字符串，目标 id；confirmed：布尔。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            if (!confirmed(arguments)) return ToolResult("还没得到用户确认，没有归档。", isError = true)
            val goalId = arguments["goalId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (goalId.isEmpty()) return ToolResult("goalId 不能为空（先用 list_goals 查）。", isError = true)
            runtime.setGoalStatus(goalId, "archived")
            return ToolResult(runtime.goalMessage ?: "已归档。")
        }
    }
}
