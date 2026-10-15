package dev.trellis.kernel.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginStateMachineTest {

    @Test
    fun happyPathTransitionsAreLegal() {
        val path = listOf(
            PluginState.DISCOVERED,
            PluginState.RESOLVED,
            PluginState.LOADED,
            PluginState.INITIALIZED,
            PluginState.ACTIVE,
            PluginState.STOPPED,
            PluginState.ACTIVE,
            PluginState.DISPOSED,
        )
        for (i in 0 until path.size - 1) {
            assertTrue(
                PluginStateMachine.canTransition(path[i], path[i + 1]),
                "应当允许 ${path[i]} -> ${path[i + 1]}",
            )
        }
    }

    @Test
    fun skippingLifecycleStagesIsIllegal() {
        assertFalse(PluginStateMachine.canTransition(PluginState.DISCOVERED, PluginState.ACTIVE))
        assertFalse(PluginStateMachine.canTransition(PluginState.LOADED, PluginState.ACTIVE))
        assertFailsWith<IllegalStateTransition> {
            PluginStateMachine.requireTransition(PluginState.DISCOVERED, PluginState.ACTIVE)
        }
    }

    @Test
    fun disposedIsAbsorbing() {
        for (target in PluginState.entries) {
            assertFalse(
                PluginStateMachine.canTransition(PluginState.DISPOSED, target),
                "DISPOSED 不该再迁移到 $target",
            )
        }
    }

    @Test
    fun everyNonDisposedStateCanBeDisposed() {
        for (state in PluginState.entries) {
            if (state == PluginState.DISPOSED) continue
            assertTrue(
                PluginStateMachine.canTransition(state, PluginState.DISPOSED),
                "状态 $state 必须能被卸载，否则 shutdown 会卡住",
            )
        }
    }

    @Test
    fun onlyDisposedIsTerminal() {
        for (state in PluginState.entries) {
            assertEquals(
                state == PluginState.DISPOSED,
                PluginStateMachine.isTerminal(state),
                "终态判定错误: $state",
            )
        }
    }
}