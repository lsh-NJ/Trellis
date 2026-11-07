package dev.trellis.app.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.trellis.domain.plan.GoalView
import dev.trellis.domain.plan.MilestoneView
import dev.trellis.domain.plan.LearningPlanInfo

/**
 * 目标 / 项目二级页：从计划页头部的「目标」按钮进来。
 *
 * 这里只做三件事：看目标（含里程碑、关联的计划）、勾里程碑、建新目标/加里程碑。
 * 不做甘特图、不做进度百分比——目标的意义是「这件事为什么做、做完算什么样」，
 * 具体哪天做哪一件在计划页。
 */
@Composable
internal fun GoalScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    val tokens = LocalTrellisTokens.current
    val colors = tokens.colors
    val goals = runtime.goals
    val active = goals.filter { it.status != "archived" }
    val archived = goals.filter { it.status == "archived" }
    var showCreate by remember { mutableStateOf(false) }
    var milestoneFor by remember { mutableStateOf<GoalView?>(null) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(tokens.space.m),
    ) {
        ChromeSecondaryHeader(
            title = "目标",
            subtitle = "先想清楚要什么，再让计划按这个目标排。",
            onBack = { runtime.closeGoalScreen() },
            trailing = {
                ChromeTextLink("新建目标", onClick = { showCreate = true })
            },
        )
        runtime.goalMessage?.let { message ->
            Text(message, style = tokens.type.caption, color = colors.ink2)
        }
        if (goals.isEmpty()) {
            Text(
                "还没有目标。目标是「为什么要学、做完算什么样」；计划和周表挂在目标下面。",
                style = tokens.type.body,
                color = colors.ink2,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(tokens.space.m)) {
                Text("进行中 · ${active.size}", style = tokens.type.meta, color = colors.ink2)
                active.forEach { goal ->
                    GoalCard(goal, runtime, onAddMilestone = { milestoneFor = goal })
                }
            }
            if (archived.isNotEmpty()) {
                Spacer(Modifier.height(tokens.space.s))
                Column(verticalArrangement = Arrangement.spacedBy(tokens.space.m)) {
                    Text("已归档 · ${archived.size}", style = tokens.type.meta, color = colors.ink3)
                    archived.forEach { goal -> GoalCard(goal, runtime, onAddMilestone = null) }
                }
            }
        }
        Spacer(Modifier.height(tokens.space.l))
    }

    if (showCreate) {
        CreateGoalDialog(
            onSubmit = { title, kind, success, deadline ->
                runtime.createGoal(title = title, kind = kind, intent = "", success = success, deadline = deadline)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
    milestoneFor?.let { goal ->
        AddMilestoneDialog(
            goalTitle = goal.title,
            onSubmit = { title, deadline ->
                runtime.addGoalMilestone(goal.id, title, deadline)
                milestoneFor = null
            },
            onDismiss = { milestoneFor = null },
        )
    }
}

@Composable
private fun GoalCard(
    goal: GoalView,
    runtime: AppRuntime,
    onAddMilestone: (() -> Unit)?,
) {
    val tokens = LocalTrellisTokens.current
    val colors = tokens.colors
    val doneCount = goal.milestones.count { it.status == "done" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 980.dp)
            .clip(RoundedCornerShape(tokens.radius.card))
            .border(1.dp, colors.rule, RoundedCornerShape(tokens.radius.card))
            .padding(tokens.space.l),
        verticalArrangement = Arrangement.spacedBy(tokens.space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 标题占满中间、最多两行：目标名常常很长，不给它 weight 会把右边的
            // 「加里程碑 / 归档」挤到 0 宽（窄窗口下甚至完全点不到）。
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    goal.title,
                    style = tokens.type.title,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.space.s)) {
                    Text(
                        if (goal.kind == "project") "项目" else "目标",
                        style = tokens.type.caption,
                        color = colors.ink3,
                    )
                    goal.deadline?.let { Text("截止 $it", style = tokens.type.caption, color = colors.ink3) }
                }
            }
            Spacer(Modifier.width(tokens.space.m))
            if (onAddMilestone != null) {
                ChromeTextLink("加里程碑", onClick = onAddMilestone)
                Spacer(Modifier.width(tokens.space.s))
            }
            ChromeTextLink(
                if (goal.status == "archived") "恢复" else "归档",
                onClick = {
                    runtime.setGoalStatus(goal.id, if (goal.status == "archived") "active" else "archived")
                },
            )
        }
        if (goal.success.isNotBlank()) {
            Text("验收：" + goal.success, style = tokens.type.body, color = colors.ink2)
        }
        if (goal.intent.isNotBlank() && goal.intent.trim() != goal.title.trim() && goal.intent.trim() != goal.success.trim()) {
            Text("为了：" + goal.intent, style = tokens.type.body, color = colors.ink2)
        }
        if (goal.milestones.isNotEmpty()) {
            Text(
                "里程碑 $doneCount/${goal.milestones.size}",
                style = tokens.type.meta,
                color = colors.ink2,
            )
            goal.milestones.forEach { milestone ->
                MilestoneRow(milestone, runtime)
            }
        }
        if (goal.plans.isNotEmpty()) {
            Text("关联计划", style = tokens.type.meta, color = colors.ink3)
            goal.plans.forEach { plan -> PlanRow(plan, runtime) }
        }
        if (goal.status == "archived") {
            Text("已归档：不再接新计划，历史保留。", style = tokens.type.caption, color = colors.ink3)
        }
    }
}

@Composable
private fun MilestoneRow(milestone: MilestoneView, runtime: AppRuntime) {
    val tokens = LocalTrellisTokens.current
    val colors = tokens.colors
    val done = milestone.status == "done"
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = done,
            onCheckedChange = {
                if (done) runtime.uncompleteGoalMilestone(milestone.id)
                else runtime.completeGoalMilestone(milestone.id)
            },
        )
        Text(
            milestone.title,
            style = tokens.type.body,
            color = if (done) colors.ink3 else colors.ink,
        )
        milestone.deadline?.let {
            Spacer(Modifier.width(tokens.space.s))
            Text(it, style = tokens.type.caption, color = colors.ink3)
        }
        Spacer(Modifier.weight(1f))
        ChromeTextLink("删除", onClick = { runtime.removeGoalMilestone(milestone.id) })
    }
}

@Composable
private fun PlanRow(plan: LearningPlanInfo, runtime: AppRuntime) {
    val tokens = LocalTrellisTokens.current
    val colors = tokens.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radius.card))
            .clickable { runtime.openPlanNode(plan.id) }
            .padding(vertical = tokens.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(plan.title, style = tokens.type.body, color = colors.ink)
        Spacer(Modifier.weight(1f))
        // 状态用中文：卡片上直接露 `active` 会被当成没翻译的漏网字段（验收 P1）。
        Text(planStatusLabel(plan.status), style = tokens.type.caption, color = colors.ink3)
    }
}

private fun planStatusLabel(status: String): String = when (status) {
    "active" -> "进行中"
    "paused" -> "暂停"
    "ended" -> "已结束"
    "abandoned" -> "已放弃"
    "planned" -> "已排"
    else -> status
}

@Composable
private fun CreateGoalDialog(
    onSubmit: (title: String, kind: String, success: String, deadline: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("goal") }
    var success by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建目标") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题（这件事叫什么）") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChromeTextLink(
                        if (kind == "goal") "· 目标" else "目标",
                        onClick = { kind = "goal" },
                    )
                    ChromeTextLink(
                        if (kind == "project") "· 项目" else "项目",
                        onClick = { kind = "project" },
                    )
                }
                OutlinedTextField(
                    value = success,
                    onValueChange = { success = it },
                    label = { Text("验收（怎么算做到了）") },
                    minLines = 2,
                )
                OutlinedTextField(
                    value = deadline,
                    onValueChange = { deadline = it },
                    label = { Text("截止日期（可选，2027-05-01）") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            ChromeTextLink(
                "建好",
                onClick = {
                    if (title.isNotBlank()) onSubmit(title.trim(), kind, success.trim(), deadline.trim().ifBlank { null })
                },
            )
        },
        dismissButton = { ChromeTextLink("取消", onClick = onDismiss) },
    )
}

@Composable
private fun AddMilestoneDialog(
    goalTitle: String,
    onSubmit: (title: String, deadline: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给「$goalTitle」加里程碑") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("里程碑（做完什么算迈过一步）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = deadline,
                    onValueChange = { deadline = it },
                    label = { Text("截止日期（可选）") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            ChromeTextLink(
                "加上",
                onClick = {
                    if (title.isNotBlank()) onSubmit(title.trim(), deadline.trim().ifBlank { null })
                },
            )
        },
        dismissButton = { ChromeTextLink("取消", onClick = onDismiss) },
    )
}
