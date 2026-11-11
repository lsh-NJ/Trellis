package dev.trellis.app.desktop

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.ResourceDraft
import dev.trellis.feature.learning.PlanDocStore
import java.util.UUID

/**
 * 阶段资源 → 单元资源：**节点找资源**。
 *
 * 总文档阶段里写的 `- 资源：` 在排周 / 写阶段之后要落到节点上（`unit_resource`），
 * 否则「总计划里写了学习资源」只活在文档里，用户点开节点看不到资料。
 *
 * 规则（2026-11-09 用户定的）：
 * - 按标题给每个节点挑最相关的几条（完全相同 > 包含 > 两个词重叠）；
 * - **分不到具体节点的整份资源**（最常见的是一整本书）挂到这一周的每个节点上，
 *   让用户自己从里面找，不因为「对不上某一讲」就丢掉；
 * - 只追加缺的（同标题 + 同链接算已挂），不覆盖用户/AGENT 已经指定的资源；
 * - 每条节点最多 [MAX_MATCHED_RESOURCES_PER_UNIT] 条匹配资源，整份资源不占这个名额。
 *
 * 这套逻辑原来只在 [PlanEditTools] 的 `schedule_week` 里用（文档阶段资源 → 单元）；
 * 现在 `research_plan`、`update_plan` 写阶段后也会调用（见 [attachStageResourcesToPlan]）。
 */
internal suspend fun attachStageResourcesToWeek(
    service: LearningPlanService,
    docStore: PlanDocStore?,
    planId: String,
    week: String,
    origin: String = "tool.plan.edit.attach",
): Int {
    val stage = docStore?.stageForWeek(planId, week) ?: return 0
    // 没有链接的书也算资源（书名就是全部线索）：排到节点上，用户至少知道该读哪本。
    val resources = stage.resources.filter { it.title.isNotBlank() }
    if (resources.isEmpty()) return 0
    val units = runCatching { service.week(planId, week) }.getOrNull()?.commitment?.units.orEmpty()
    if (units.isEmpty()) return 0

    // 只要能和本周任意节点对上，就是「有归属」的；全都对不上的才当整份资源铺到每个节点。
    val wholeWeekResources = resources.filter { resource ->
        units.none { unit -> stageResourceScore(resource.title, unit.title) > 0 }
    }

    var attached = 0
    for (unit in units) {
        val matched = resources
            .map { resource -> resource to stageResourceScore(resource.title, unit.title) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(MAX_MATCHED_RESOURCES_PER_UNIT)
            .map { it.first }
        val targets = (matched + wholeWeekResources).distinctBy { it.title to it.url }
        if (targets.isEmpty()) continue

        val existing = unit.resources.map { it.title to it.url }.toSet()
        val additions = targets
            .filter { (it.title to it.url) !in existing }
            .map {
                ResourceDraft(
                    kind = stageResourceKind(it.url),
                    title = it.title,
                    url = it.url,
                    locator = "",
                    source = "plan_doc",
                )
            }
        if (additions.isEmpty()) continue
        // setUnitResources 是整表替换：要把节点上已有的资源一起再写回去。
        val merged = (unit.resources.map {
            ResourceDraft(kind = it.kind, title = it.title, url = it.url, locator = it.locator)
        } + additions).take(MAX_RESOURCES_ON_A_UNIT)
        val envelope = CommandEnvelope(
            commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
            actor = Actor.AGENT,
            planId = planId,
            origin = origin,
        )
        val result = runCatching { service.setUnitResources(envelope, planId, unit.id, merged) }
        if (result.getOrNull()?.ok == true) attached++
    }
    return attached
}

/**
 * 写阶段（`plan_stage` + 总文档）之后，把**已经存在**的节点补上阶段资源。
 *
 * 阶段可能跨好几周：逐周交给 [attachStageResourcesToWeek]，没有单元的周直接跳过。
 * 幂等：已经挂过的不重复写。
 */
internal suspend fun attachStageResourcesToPlan(
    service: LearningPlanService,
    docStore: PlanDocStore?,
    planId: String,
    origin: String = "tool.plan.edit.attach",
): Int {
    if (docStore == null) return 0
    val weeks = runCatching { service.map(planId).weeks.map { it.week } }.getOrDefault(emptyList())
    if (weeks.isEmpty()) return 0
    return weeks.sumOf { week -> attachStageResourcesToWeek(service, docStore, planId, week, origin) }
}

/** 标题匹配：完全相同 > 包含 > 两个以上词重叠；阈值保证不会把整个阶段硬塞给每个节点。 */
internal fun stageResourceScore(resourceTitle: String, unitTitle: String): Int {
    val a = stageResourceNormalize(resourceTitle)
    val b = stageResourceNormalize(unitTitle)
    if (a.isBlank() || b.isBlank()) return 0
    if (a == b) return 100
    if (a.contains(b) || b.contains(a)) return 60
    val tokensA = stageResourceTokens(a)
    val tokensB = stageResourceTokens(b)
    val overlap = tokensA.intersect(tokensB).count { it.length >= 2 }
    return if (overlap >= 2) 40 else 0
}

/** 资源类型：B 站单独一类，书/PDF 算 doc，其余外链都算 web。 */
internal fun stageResourceKind(url: String): String = when {
    url.contains("bilibili.com") -> "bilibili"
    url.isBlank() -> "doc"
    url.contains(".pdf") || url.contains("douban.com") || url.contains("/book") -> "doc"
    else -> "web"
}

private fun stageResourceNormalize(value: String): String =
    value.lowercase().replace(Regex("[\\s\\p{Punct}，。、：；（）【】《》]"), "")

private fun stageResourceTokens(value: String): Set<String> =
    value.split(Regex("[·\\s/]+")).filter { it.isNotBlank() }.toSet()

/** 一个节点按标题最多挂几条阶段资源；整份资源（书）不占这个名额。 */
private const val MAX_MATCHED_RESOURCES_PER_UNIT = 3

/** 后端 `unit_resource` 每节点上限（PlanCommands.MAX_UNIT_RESOURCES）；合并已有资源时别越过。 */
private const val MAX_RESOURCES_ON_A_UNIT = 20
