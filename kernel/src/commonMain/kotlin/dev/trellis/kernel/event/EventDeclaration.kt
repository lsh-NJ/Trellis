package dev.trellis.kernel.event

/**
 * 事件声明。
 *
 * 领域插件用它告诉内核「我会发出哪些事件、载荷里有什么字段」。
 *
 * 没有这个声明，自动生成的规则只能靠猜事件名 —— 一条写成 `task.done` 的规则
 * 能通过所有校验、装得上、跑得起来，然后**永远不触发**。
 * 这种静默失败比报错难查一百倍，所以事件必须和挂载点、动作一样被登记。
 *
 * 内核依然不认识 `task` 是什么：它只知道自己有个叫 event 的挂载点，
 * 谁来登记都行。领域中立没有被破坏。
 */
interface EventDeclaration {

    /** 事件类型，如 `task.completed`。规则里的 `on` 必须逐字匹配。 */
    val type: String

    /** 一句人话：什么时候会发出这个事件。直接进给模型看的说明书。 */
    val label: String

    /** 载荷里有什么。规则参数里写 `{{ event.字段 }}` 取到它。 */
    val payload: List<PayloadField>

    /** 载荷里的一个字段。 */
    data class PayloadField(val name: String, val description: String)
}
