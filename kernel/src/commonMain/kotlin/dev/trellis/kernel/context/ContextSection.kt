package dev.trellis.kernel.context

import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.extension.Cardinality
import dev.trellis.kernel.extension.ExtensionPoint
import dev.trellis.kernel.extension.OrderedContribution

/**
 * 往「宿主对外说话时的上下文」里加一段**事实**。
 *
 * 关键边界：插件交的是**数据块**，不是措辞原文。
 * 「这段该怎么写、放在第几层、超了砍哪里」由宿主决定；插件只负责把此刻的真实状态
 * 摊成一段文本。这样换模型、换措辞、换压缩策略都不用改插件，插件也没法借这段
 * 给自己加权限（它能写的只有事实，写不了「你应该优先调用 xx」这类指令）。
 *
 * 命名刻意不带业务含义：内核只认识「上下文片段」这个位置，
 * 不认识目标、计划、打卡这些概念，也不该知道读这段文字的是谁。
 *
 * 名字里不能出现 Prompt 之类的词：内核源码里出现那些词，
 * `verifyKernelPurity`（不变量 I2）会直接判红。
 */
interface ContextSection : OrderedContribution {

    /**
     * 这段最多占多少字符。0 表示不限（宿主仍会按全局预算截）。
     *
     * 插件自己先声明上限，比让宿主猜要准：只有它知道哪一段砍掉会失真。
     */
    val budgetChars: Int

    /** 现在这一刻该注入什么。**必须是纯函数**：同样的数据渲染同样的文本。 */
    suspend fun render(): String
}

/**
 * 宿主上下文贡献点。
 *
 * 只有一个位置：`<current_state>` 之后、`<task:...>` 之前。放在历史之前，
 * 是因为它和 `current_state` 一样属于「此刻的事实」，而不是「发生过的事」。
 */
object Contexts {

    val Host: ExtensionPoint<ContextSection> = ExtensionPoint(
        ExtensionPointId("context.host"),
        Cardinality.MANY,
        "宿主上下文片段",
    )

    val all: List<ExtensionPoint<ContextSection>> = listOf(Host)

    private val byId: Map<ExtensionPointId, ExtensionPoint<ContextSection>> =
        all.associateBy { it.id }

    fun byId(id: ExtensionPointId): ExtensionPoint<ContextSection>? = byId[id]

    fun isKnown(id: ExtensionPointId): Boolean = id in byId

    val knownIds: Set<ExtensionPointId> get() = byId.keys
}
