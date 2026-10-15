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
     * 取一个值。
     *
     * 返回 null 表示「这个路径不存在」；抛异常表示「取值时出错了」。
     * **两种情况都会被渲染层隔离成占位符**，不会让整张卡片崩掉。
     */
    suspend fun resolve(path: String): String?
}
