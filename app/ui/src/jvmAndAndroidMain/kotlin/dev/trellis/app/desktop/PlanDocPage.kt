package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 计划草稿的 id 前缀：草稿不是计划，跳转（[AppRuntime.openPlanDocs]）与本页都要先认出来。
 *
 * 前缀只是**提示**——真正的判据是「store 里有这份草稿，且它没有任何周文档」，见 [PlanDocPage]。
 * 计划 id 一律是 `pln_` 开头，不会和这个前缀撞。
 */
internal const val PLAN_DOC_DRAFT_PREFIX: String = "draft_"

/**
 * 知识库里的「计划文档」二级页：**只读**预览。
 *
 * 2026-11-17 起和普通笔记同一套壳（见 `KnowledgeScreen`）：
 * 左栏列表（计划和草稿）+ 正文 + 右栏目录；但只读 —— 没有编辑面、没有「AI 整理」、
 * 没有「本篇概念」那一栏。计划文档是「想法」层，改它要跟 AGENT 说，这样它也不会被
 * 笔记抽取、概念网络、自动完成那些流程当成一篇普通笔记。
 *
 * 计划工作流第一步会在计划本体建好前先写一份「总计划草稿」（[PlanDocStore.saveDraft]），
 * 草稿也走同一个壳：标题固定「总计划（草稿）」，副标题空，不查 plan 表标题。
 *
 * 目录跳转复用 [MarkdownText] 的 `onBlockLayout`：每个块报自己的 y，第 n 个标题落在
 * 第 n 个 Heading 块上（见 [headingBlockIndexes]），据此把滚动位置推到那个标题。
 */
@Composable
internal fun PlanDocPage(runtime: AppRuntime, planId: String, onClose: () -> Unit) {
    val store = runtime.planDocStore
    LaunchedEffect(Unit) { if (runtime.planAll.isEmpty()) runtime.refreshPlan() }
    val plans = runtime.planAll

    // 草稿不是计划：按前缀、或「有草稿且没有任何周文档」认出来。计划的 readDraft 永远为 null，
    // 所以这两个判据不会把计划误认成草稿。
    val draftDoc = remember(planId) { runCatching { store.readDraft(planId) }.getOrNull() }
    val isDraft = planId.startsWith(PLAN_DOC_DRAFT_PREFIX) ||
        (draftDoc != null && runCatching { store.weeks(planId) }.getOrDefault(emptyList()).isEmpty())

    if (isDraft) {
        PlanDocPreview(
            title = "总计划（草稿）",
            subtitle = null,
            onClose = onClose,
            markdown = draftDoc,
            emptyHint = "这份草稿不见了。",
            emptyHintDetail = "草稿只是计划工作流的中间产物：计划建好之后它会落成这份计划的总文档，然后删掉。",
            sidebarCount = plans.size,
        ) {
            SidebarSectionLabel("计划")
            plans.forEach { plan ->
                PlanDocListRow(
                    label = plan.title.ifBlank { "未命名计划" },
                    selected = false,
                    onClick = { runtime.openPlanDocs(plan.id) },
                )
            }
            Spacer(Modifier.height(10.dp))
            SidebarSectionLabel("草稿")
            PlanDocListRow(label = "总计划（草稿）", selected = true, onClick = {})
        }
        return
    }

    var week by remember(planId) { mutableStateOf<String?>(null) }
    val weeks = remember(planId, week) { runCatching { store.weeks(planId) }.getOrDefault(emptyList()) }
    val overallDoc = remember(planId, week) { runCatching { store.read(planId) }.getOrNull() }
    val weekDoc = week?.let { current ->
        remember(planId, current) { runCatching { store.readWeek(planId, current) }.getOrNull() }
    }
    val planTitle = plans.firstOrNull { it.id == planId }?.title
    val markdown = if (week == null) overallDoc else weekDoc

    PlanDocPreview(
        title = "计划文档",
        subtitle = planTitle,
        onClose = onClose,
        markdown = markdown,
        emptyHint = if (week == null) "这份计划还没有计划文档。" else "这一周还没有计划文档。",
        emptyHintDetail = "要改的话直接跟 AGENT 说——计划文档只在这里看，不在这里编。",
        sidebarCount = weeks.size + 1,
    ) {
        SidebarSectionLabel("计划")
        plans.forEach { plan ->
            PlanDocListRow(
                label = plan.title.ifBlank { "未命名计划" },
                selected = plan.id == planId,
                onClick = { runtime.openPlanDocs(plan.id) },
            )
        }
        Spacer(Modifier.height(10.dp))
        SidebarSectionLabel("这份计划的文档")
        PlanDocListRow(label = "总览", selected = week == null, onClick = { week = null })
        weeks.forEach { current ->
            PlanDocListRow(label = current, selected = week == current, onClick = { week = current })
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

/**
 * 计划文档 / 笔记共用的只读预览壳：左栏列表 + 正文 + 右栏目录。
 *
 * 和 `KnowledgeScreen` 的编辑页刻意保持同构（同样的左栏、同样的正文缩进、同样的
 * 右栏宽度与收起态），差别只有三处：标题不可编辑、正文是 [MarkdownText]、右栏只画目录。
 */
@Composable
private fun PlanDocPreview(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    markdown: String?,
    emptyHint: String,
    emptyHintDetail: String,
    sidebarCount: Int,
    sidebar: @Composable () -> Unit,
) {
    val body = markdown.orEmpty()
    val outlineItems = remember(body) { parseOutline(body) }
    val headingBlocks = remember(body) { headingBlockIndexes(body) }
    // 目录的文档顺序（含子级）→ 第 n 个标题；跳转靠它换算成渲染块下标。
    val outlineOrder = remember(outlineItems) { flattenOutline(outlineItems, emptySet()) }
    var collapsedOutline by remember(body) { mutableStateOf(emptySet<Int>()) }
    val pageScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // 块在窗口里的 y：MarkdownText 每块报一次；滚动容器自己的 y 用来换算成「内容内偏移」。
    val blockYs = remember(body) { mutableStateMapOf<Int, Float>() }
    var viewportTop by remember { mutableStateOf(0f) }

    fun jumpTo(item: OutlineItem) {
        val ordinal = outlineOrder.indexOfFirst { it.item.offset == item.offset }
        val block = headingBlocks.getOrNull(ordinal) ?: return
        val y = blockYs[block] ?: return
        val target = (pageScroll.value + (y - viewportTop)).toInt().coerceIn(0, pageScroll.maxValue)
        scope.launch { pageScroll.animateScrollTo(target) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(top = 16.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChromeSecondaryHeader(
            title = title,
            onBack = onClose,
            subtitle = subtitle,
            trailing = {
                Text(
                    "只读",
                    style = LocalTrellisTokens.current.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(end = 6.dp),
                )
                EditorChip(
                    label = "目录",
                    active = MarkdownSidebarState.visible,
                    onClick = { MarkdownSidebarState.visible = !MarkdownSidebarState.visible },
                )
            },
        )
        Row(modifier = Modifier.fillMaxSize()) {
            ListSidebar(
                title = "计划文档",
                count = sidebarCount,
                actionLabel = "",
                onAction = null,
            ) {
                Column(
                    modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    sidebar()
                }
            }
            ListSidebarSplitter()
            // 正文：和笔记页同一套缩进 —— 左 18dp；右栏可见时不留右边距（右栏自己贴窗口右边界）。
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(
                    start = 18.dp,
                    end = if (MarkdownSidebarState.visible) 0.dp else 4.dp,
                ),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned { viewportTop = it.positionInRoot().y },
                ) {
                    Column(Modifier.fillMaxSize().verticalScroll(pageScroll)) {
                        if (body.isBlank()) {
                            Text(
                                emptyHint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                emptyHintDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            MarkdownText(
                                body,
                                modifier = Modifier.fillMaxWidth(),
                                showLinkUrl = false,
                                onBlockLayout = { index, y -> blockYs[index] = y },
                            )
                            Spacer(Modifier.height(20.dp))
                        }
                    }
                }
            }
            // 右栏：只有目录（计划文档没有概念，也没有 AI 整理）；收起态留一条窄边，和笔记页一致。
            val showSidebar = MarkdownSidebarState.visible
            Spacer(Modifier.width(if (showSidebar) 12.dp else 6.dp))
            if (showSidebar) {
                Column(
                    Modifier.width(MarkdownSidebarState.width).fillMaxHeight(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(end = 6.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditorChip(
                            label = "›",
                            active = false,
                            onClick = { MarkdownSidebarState.visible = false },
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    MarkdownOutlinePanel(
                        roots = outlineItems,
                        collapsed = collapsedOutline,
                        modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp),
                        onToggle = { item ->
                            collapsedOutline = if (item.offset in collapsedOutline) {
                                collapsedOutline - item.offset
                            } else {
                                collapsedOutline + item.offset
                            }
                        },
                        onCollapseAll = {
                            collapsedOutline = parentOutlineOffsets(outlineItems).toSet()
                        },
                        onExpandAll = { collapsedOutline = emptySet() },
                        onJump = { item -> jumpTo(item) },
                        onHide = { MarkdownSidebarState.visible = false },
                    )
                }
            } else {
                Column(
                    Modifier.fillMaxHeight().width(36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(2.dp))
                    EditorChip(
                        label = "‹",
                        active = false,
                        onClick = { MarkdownSidebarState.visible = true },
                    )
                }
            }
        }
    }
}

/** 左栏的小节标题（「计划」「这份计划的文档」）。 */
@Composable
private fun SidebarSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

/** 目录「全部收起」要收的节点：所有带子标题的标题 offset（和笔记页同一份逻辑）。 */
private fun parentOutlineOffsets(items: List<OutlineItem>): List<Int> = items.flatMap { item ->
    if (item.children.isEmpty()) emptyList() else listOf(item.offset) + parentOutlineOffsets(item.children)
}
