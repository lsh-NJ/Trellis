package dev.trellis.domain.plan

import kotlinx.serialization.Serializable

/**
 * 读接口的 view model（`plan-ui-contract.md` §2）。
 *
 * 两条硬规则写在这里：
 * - 只有 [ReviewView] 携带统计数字；其余视图不出现完成率/百分比/连续天数。
 * - 数字全部由服务端算好（[DayView.headline]、[WeekView.rangeLabel] 等文案也是），
 *   UI 不拼、不算、不四舍五入。
 */

@Serializable
data class MinimalVersion(val available: Boolean, val minutes: Int)

@Serializable
data class OriginLabel(val kind: String, val label: String)

@Serializable
data class DayOccurrence(
    val id: String,
    val title: String,
    val chapter: String,
    val minutes: Int,
    val mode: String,
    val status: String,
    val minimal: MinimalVersion,
    val movedCount: Int,
    val carried: Boolean,
    val origin: OriginLabel,
    val actions: List<String>,
)

@Serializable
data class CompletedOccurrence(
    val id: String,
    val title: String,
    val note: String? = null,
)

@Serializable
data class TodoItem(
    val id: String,
    val title: String,
    val status: String,
)

@Serializable
data class DayView(
    val day: String,
    val weekdayLabel: String,
    val headline: String,
    val occurrences: List<DayOccurrence> = emptyList(),
    val completed: List<CompletedOccurrence> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val undo: UndoView? = null,
)

@Serializable
data class UndoView(val token: String, val label: String, val expiresAt: String)

@Serializable
data class CommitmentUnitView(
    val id: String,
    val title: String,
    val chapter: String,
    val estimateMinutes: Int,
    val state: String,
    val stateLabel: String,
)

@Serializable
data class CommitmentView(
    val label: String,
    val units: List<CommitmentUnitView>,
    val summary: String,
)

@Serializable
data class WeekDayItem(
    val occurrenceId: String,
    val title: String,
    val status: String,
    val minutes: Int,
    val carried: Boolean = false,
)

@Serializable
data class WeekDay(
    val day: String,
    val label: String,
    val today: Boolean,
    val items: List<WeekDayItem> = emptyList(),
)

@Serializable
data class EntryView(val available: Boolean, val label: String, val at: String? = null, val enabled: Boolean = true)

@Serializable
data class WeekView(
    val week: String,
    val rangeLabel: String,
    val commitment: CommitmentView,
    val days: List<WeekDay>,
    val reviewEntry: EntryView,
    val changeCommitmentEntry: EntryView,
    /**
     * 这周的承诺有没有铺到天。承诺了但还没铺的周（未来周）是 false：
     * 页面上显示成灰色「待安排」，等它变成当周（或周日提前一天）再自动铺。
     */
    val scheduled: Boolean = true,
)

@Serializable
data class LearningPlanInfo(
    val id: String,
    val title: String,
    val status: String,
)

/** 还没被任何一周承诺收进去的单元：计划页上「待安排」的池子。 */
@Serializable
data class PoolUnit(
    val id: String,
    val title: String,
    val chapter: String,
    val estimateMinutes: Int,
)

@Serializable
data class MapUnit(val title: String, val state: String)

@Serializable
data class MapWeek(val week: String, val state: String, val label: String, val units: List<MapUnit>)

@Serializable
data class Milestone(val label: String, val state: String)

@Serializable
data class ContinueEntry(val occurrenceId: String?, val label: String)

@Serializable
data class MapView(
    val planTitle: String,
    val routeLabel: String,
    val weeks: List<MapWeek>,
    val milestone: Milestone,
    val continueEntry: ContinueEntry,
)

@Serializable
data class SuggestionView(val action: String, val cost: String, val expectedEffect: String)

@Serializable
data class FactView(val label: String, val value: String)

@Serializable
data class ReviewView(
    val week: String,
    val state: String,
    val narrative: String,
    val question: String,
    val suggestion: SuggestionView? = null,
    val facts: List<FactView> = emptyList(),
    val planDiff: PlanDiff? = null,
)

@Serializable
data class PlanStateView(
    val planId: String,
    val status: String,
    val currentVersion: String?,
    val week: String,
    val pendingDraft: Boolean,
    val pendingReview: Boolean,
    val carryPending: Int,
    val title: String = "",
)

@Serializable
data class TodoView(
    val id: String,
    val title: String,
    val status: String,
    val estimateMinutes: Int? = null,
)
