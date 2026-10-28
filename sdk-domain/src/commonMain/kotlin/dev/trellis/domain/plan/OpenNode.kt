package dev.trellis.domain.plan

import kotlinx.serialization.Serializable

/**
 * 一次「还没做完、已经到期」的排期。
 *
 * 待办板要两拨节点：今天排到的，和之前排到却没做的。两拨都落在 occurrence 上，
 * 所以这里给的是 occurrence 而不是 unit —— 计时器的 session 只能挂在 occurrence 上。
 *
 * [day] 是排到的那一天（`yyyy-MM-dd`）。逾期几天由界面层按「今天」算，服务层不猜时区；
 * 待办板问的时候把自己算出的今天当截止日传进来。
 */
@Serializable
data class OpenNode(
    val occurrenceId: String,
    val title: String,
    val chapter: String,
    val minutes: Int,
    val day: String,
)
