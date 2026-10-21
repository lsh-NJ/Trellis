package dev.trellis.kernel.slot

import dev.trellis.kernel.api.ActionOutcome
import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.rule.ActionCall

/** 文字只有三档。多一档就有一个档位永远没人用。 */
enum class UiTextStyle { Title, Body, Caption }

/**
 * 一块界面。
 *
 * 这是**封闭词汇表**：插件只能从这几个形状里挑，不能自己画。
 * 理由一句话：宿主得先看懂，才谈得上审查、降级和主题一致。
 * 画什么由插件决定，怎么画由宿主决定。
 */
sealed interface UiNode {
    data class Column(val children: List<UiNode>, val gap: Int = 8) : UiNode
    data class Row(val children: List<UiNode>, val gap: Int = 8) : UiNode
    data class Text(val text: String, val style: UiTextStyle = UiTextStyle.Body) : UiNode
    data class Button(val label: String, val onTap: UiTap) : UiNode
    data class Input(val hint: String, val submitLabel: String, val onSubmit: UiTap) : UiNode
    data class Notice(val text: String) : UiNode
    data object Divider : UiNode
}

/**
 * 点下去会发生什么。只有两种可能。
 *
 * Host 是点名宿主已有的原子动作，照样过审批门；
 * Plugin 是把值交回插件自己的处理函数，它只能返回「要存什么」，不能直接写。
 */
sealed interface UiTap {
    data class Host(val call: ActionCall) : UiTap
    data class Plugin(val handler: String, val args: Map<String, String> = emptyMap()) : UiTap
}

/**
 * 由**代码**生成的卡片。
 *
 * 和 TemplateSlot 的区别：模板卡片只会把占位符换成数字，
 * 这种卡片能看数据、能按条件决定画什么。代价是它要跑代码，
 * 所以宿主必须给它设预算，并且**永远不在渲染线程上跑它**。
 */
interface DynamicSlot : SlotContribution {
    suspend fun build(context: DataContext): UiNode
    suspend fun perform(handler: String, payload: Map<String, String>): ActionOutcome
}
