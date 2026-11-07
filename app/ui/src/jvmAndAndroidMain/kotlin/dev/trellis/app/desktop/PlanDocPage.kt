package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 知识库里的「计划文档」二级页：只读。
 *
 * 计划页只负责跳转（`AppRuntime.openPlanDocs`）；文档是「想法」层，改它要跟 AGENT 说，
 * 这样「计划文档」就不会被笔记抽取、概念网络、自动完成那些流程当成一篇普通笔记。
 * 左栏 = 计划 → 周文档；右栏 = 只读预览（`MarkdownText`）。
 */
@Composable
internal fun PlanDocPage(runtime: AppRuntime, planId: String, onClose: () -> Unit) {
    val store = runtime.planDocStore
    var week by remember(planId) { mutableStateOf<String?>(null) }
    LaunchedEffect(planId) { if (runtime.planAll.isEmpty()) runtime.refreshPlan() }
    val plans = runtime.planAll
    val weeks = remember(planId, week) { runCatching { store.weeks(planId) }.getOrDefault(emptyList()) }
    val overallDoc = remember(planId, week) { runCatching { store.read(planId) }.getOrNull() }
    val weekDoc = week?.let { current ->
        remember(planId, current) { runCatching { store.readWeek(planId, current) }.getOrNull() }
    }
    val planTitle = plans.firstOrNull { it.id == planId }?.title

    Column(
        modifier = Modifier.fillMaxSize().padding(top = 16.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChromeSecondaryHeader(title = "计划文档", onBack = onClose, subtitle = planTitle)
        Row(modifier = Modifier.fillMaxSize()) {
            ListSidebar(
                title = "计划文档",
                count = weeks.size + 1,
                actionLabel = "",
                onAction = null,
            ) {
                Column(
                    modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "计划",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                    plans.forEach { plan ->
                        PlanDocEntry(
                            label = plan.title,
                            selected = plan.id == planId,
                            onClick = { runtime.openPlanDocs(plan.id) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "这份计划的文档",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                    PlanDocEntry(label = "总览", selected = week == null, onClick = { week = null })
                    weeks.forEach { current ->
                        PlanDocEntry(label = current, selected = week == current, onClick = { week = current })
                    }
                    if (weeks.isEmpty()) {
                        Text(
                            "还没有按周写的文档：每周排计划时会写在这里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                }
            }
            ListSidebarSplitter()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val markdown = if (week == null) overallDoc else weekDoc
                if (markdown.isNullOrBlank()) {
                    Text(
                        if (week == null) "这份计划还没有计划文档。" else "这一周还没有计划文档。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "要改的话直接跟 AGENT 说——计划文档只在这里看，不在这里编。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MarkdownText(markdown, showLinkUrl = false)
                }
            }
        }
    }
}

@Composable
private fun PlanDocEntry(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .listRowSurface(selected, hovered)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListRowSelectionBar(selected)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
