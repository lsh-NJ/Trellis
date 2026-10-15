package dev.trellis.kernel.plugin

/** 插件状态。迁移合法性由 [PluginStateMachine] 裁决。见设计文档 4.5 */
enum class PluginState {
    DISCOVERED,
    RESOLVED,
    LOADED,
    INITIALIZED,
    ACTIVE,
    STOPPED,
    FAILED,
    SKIPPED,
    UNAVAILABLE,
    DISPOSED,
}

class IllegalStateTransition(val from: PluginState, val to: PluginState) :
    IllegalStateException("非法插件状态迁移: $from -> $to")

/**
 * 状态机迁移表。这是内核里性价比最高的一段代码：
 * 它把「插件不可能处于一个诡异状态」变成了一条断言。
 */
object PluginStateMachine {

    private val transitions: Map<PluginState, Set<PluginState>> = mapOf(
        PluginState.DISCOVERED to setOf(
            PluginState.RESOLVED, PluginState.SKIPPED, PluginState.FAILED, PluginState.DISPOSED,
        ),
        PluginState.RESOLVED to setOf(
            PluginState.LOADED, PluginState.UNAVAILABLE, PluginState.FAILED, PluginState.DISPOSED,
        ),
        PluginState.LOADED to setOf(
            PluginState.INITIALIZED, PluginState.FAILED, PluginState.DISPOSED,
        ),
        PluginState.INITIALIZED to setOf(
            PluginState.ACTIVE, PluginState.FAILED, PluginState.DISPOSED,
        ),
        PluginState.ACTIVE to setOf(
            PluginState.STOPPED, PluginState.FAILED, PluginState.DISPOSED,
        ),
        PluginState.STOPPED to setOf(
            PluginState.ACTIVE, PluginState.FAILED, PluginState.DISPOSED,
        ),
        PluginState.FAILED to setOf(PluginState.DISPOSED),
        PluginState.SKIPPED to setOf(PluginState.DISPOSED),
        PluginState.UNAVAILABLE to setOf(PluginState.DISPOSED),
        PluginState.DISPOSED to emptySet(),
    )

    fun canTransition(from: PluginState, to: PluginState): Boolean =
        transitions[from]?.contains(to) == true

    fun requireTransition(from: PluginState, to: PluginState) {
        if (!canTransition(from, to)) throw IllegalStateTransition(from, to)
    }

    fun allowedFrom(from: PluginState): Set<PluginState> = transitions[from] ?: emptySet()

    /** 终态：不会再发生任何迁移。 */
    fun isTerminal(state: PluginState): Boolean = transitions[state].isNullOrEmpty()
}