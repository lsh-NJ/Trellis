package dev.trellis.kernel.extension

/**
 * 数据提供者：把「一个命名空间下的若干字段」暴露给**声明式插件**。
 *
 * 内核不认识 `summary` 是什么  它只认识「有个叫 summary 的提供者，
 * 问它 todo 是多少，它给一个字符串」。这是声明式插件拿到真实数据的**唯一通道**。
 *
 * 为什么不让声明式插件直接读存储：那等于把存储布局变成公开契约，
 * 一旦换后端或改表结构，所有插件全废。走提供者，插件只知道「有个字段叫 todo」。
 *
 * [OrderedContribution.id] 就是命名空间  模板里 `{{ summary.todo }}` 那一段。
 */
interface DataProvider : OrderedContribution {

    /**
     * 我提供哪些字段，以及它们是什么意思。
     *
     * 这是**给人和 Agent 看的清单**：界面上的数据源浏览器、以及生成插件时的 prompt
     * 都从这里取。不声明的话，模板里能写什么就只能靠猜。
     *
     * 默认空 —— 老的数据源不改也能编译。
     */
    val fields: List<DataField> get() = emptyList()

    /**
     * 取一个值。
     *
     * 返回 null 表示「这个路径不存在」；抛异常表示「取值时出错了」。
     * **两种情况都会被渲染层隔离成占位符**，不会让整张卡片崩掉。
     */
    suspend fun resolve(path: String, context: DataContext): String?
}

/** 数据源的一个字段：路径（不含命名空间）和一句人话解释。 */
data class DataField(val path: String, val description: String)

/**
 * 求值时的「现在」和「今天」。
 *
 * ### 为什么要有这个
 *
 * 「今天」需要时区，而时区是**展示层**的事实  内核和领域层都不该猜。
 * 但数据源又确实需要它（`{{ summary.dueToday }}` 就是典型的例子）。
 *
 * 解决办法不是砍掉概念，而是**把上下文往下传**：
 * 渲染层用用户的时区算好三个时刻，传给求值器，再传给数据源。
 *
 * 这样每一层都只做自己知道的事：
 *  - 展示层知道时区  算出「今天」的边界
 *  - 数据源知道怎么按边界统计  但不猜边界在哪
 *  - 内核只管把值塞进模板  什么都不知道
 */
data class DataContext(
    /** 现在。 */
    val nowMs: Long,
    /** 今天开始（含）。 */
    val dayStartMs: Long,
    /** 明天开始（不含）。 */
    val dayEndMs: Long,
) {
    companion object {
        /**
         * 退化版本：只有「现在」，把「今天」当作「未来 24 小时」。
         *
         * **只给测试和没有时区概念的场景用。** 真实界面请用时区算好的那个。
         */
        fun ofNow(nowMs: Long, dayLengthMs: Long = 24L * 60L * 60L * 1000L): DataContext =
            DataContext(nowMs, nowMs, nowMs + dayLengthMs)
    }
}
