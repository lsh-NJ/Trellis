package dev.trellis.kernel.event

import dev.trellis.kernel.core.PluginId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一条事件。
 *
 * 载荷全是字符串：和模板、动作参数保持同一种形状，
 * 这样 `{{ event.title }}` 在规则里和 `{{ summary.todo }}` 在卡片里是同一套语法。
 */
data class KernelEvent(
    val type: String,
    val payload: Map<String, String> = emptyMap(),
)

/**
 * 事件总线。领域层发，规则引擎收。
 *
 * 订阅要带 owner：插件被卸载时按 owner 一次性摘干净，
 * 不然卸载之后规则还在跑，改的是已经不存在的东西。
 */
interface EventBus {
    suspend fun emit(event: KernelEvent)

    /** 订阅。返回一个取消函数。 */
    suspend fun subscribe(owner: PluginId, listener: suspend (KernelEvent) -> Unit): () -> Unit

    suspend fun unsubscribeAll(owner: PluginId)
}

class DefaultEventBus : EventBus {

    private val listeners = mutableMapOf<PluginId, MutableList<suspend (KernelEvent) -> Unit>>()
    private val lock = Mutex()

    override suspend fun emit(event: KernelEvent) {
        val snapshot = lock.withLock { listeners.values.flatten().toList() }
        for (listener in snapshot) {
            try {
                listener(event)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                // 一个订阅者炸了，不能连累其它订阅者，更不能让发事件的人失败
            }
        }
    }

    override suspend fun subscribe(
        owner: PluginId,
        listener: suspend (KernelEvent) -> Unit,
    ): () -> Unit {
        lock.withLock { listeners.getOrPut(owner) { mutableListOf() }.add(listener) }
        return {
            listeners[owner]?.remove(listener)
            Unit
        }
    }

    override suspend fun unsubscribeAll(owner: PluginId) {
        lock.withLock { listeners.remove(owner) }
    }
}

/** 什么都不做的总线。给「还没接线」的场景兜底，行为是明确的：事件发出去没人收。 */
object NoEventBus : EventBus {
    override suspend fun emit(event: KernelEvent) = Unit
    override suspend fun subscribe(
        owner: PluginId,
        listener: suspend (KernelEvent) -> Unit,
    ): () -> Unit = {}
    override suspend fun unsubscribeAll(owner: PluginId) = Unit
}
