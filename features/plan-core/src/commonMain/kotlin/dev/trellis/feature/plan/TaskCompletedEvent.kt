package dev.trellis.feature.plan

import dev.trellis.kernel.event.EventDeclaration

/**
 * 「完成一个任务」。计划核心唯一对外广播的事件。
 *
 * 载荷带 title 是刻意的：规则最常写的就是「照刚才那个任务再建一条」，
 * 而规则没有查询能力 —— 不给 title，它就只能在参数里写死一条标题。
 */
object TaskCompletedEvent : EventDeclaration {

    const val TYPE = "task.completed"

    override val type = TYPE

    override val label = "完成一个任务（只在第一次从没完成变成完成时发一次，重复点不会重复发）"

    override val payload = listOf(
        EventDeclaration.PayloadField("taskId", "刚完成的任务 id"),
        EventDeclaration.PayloadField("title", "刚完成的任务标题"),
    )
}
