package dev.trellis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.trellis.kernel.api.ActionOutcome
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.slot.DynamicSlot
import dev.trellis.kernel.slot.SlotAction
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiTap
import dev.trellis.kernel.slot.UiTextStyle
import kotlinx.coroutines.launch

/**
 * 画一张**由代码生成**的卡片。
 *
 * 这里有一个刻意的分工：插件只交出 [UiNode] 这棵**数据树**，
 * 真正画像素的是下面这些 Compose 代码。于是：
 *
 *  - 插件画不出宿主不认识的东西（它交上来的树会被逐节点校验）
 *  - 插件崩了只影响这张卡片（求值在协程里，异常落成一句提示）
 *  - 主题、字号、间距由宿主说了算，插件生态不会长成一片拼贴画
 */
@Composable
fun DynamicSlotView(
    slot: DynamicSlot,
    refreshKey: Any?,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
) {
    var root by remember(slot.id) { mutableStateOf<UiNode?>(null) }
    var failure by remember(slot.id) { mutableStateOf("") }

    LaunchedEffect(slot.id, refreshKey) {
        try {
            root = slot.build(currentDataContext())
            failure = ""
        } catch (t: Throwable) {
            failure = t.message ?: "未知原因"
        }
    }

    val current = root
    when {
        failure.isNotEmpty() -> Text(
            "这张卡片画不出来：" + failure,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        current == null -> Text("正在准备…", style = MaterialTheme.typography.bodySmall)
        else -> UiNodeView(current, slot, onAction)
    }
}

@Composable
private fun UiNodeView(
    node: UiNode,
    slot: DynamicSlot,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
) {
    when (node) {
        is UiNode.Column -> Column(verticalArrangement = Arrangement.spacedBy(node.gap.dp)) {
            for (child in node.children) UiNodeView(child, slot, onAction)
        }
        is UiNode.Row -> Row(horizontalArrangement = Arrangement.spacedBy(node.gap.dp)) {
            for (child in node.children) UiNodeView(child, slot, onAction)
        }
        is UiNode.Text -> Text(
            node.text,
            style = when (node.style) {
                UiTextStyle.Title -> MaterialTheme.typography.titleSmall
                UiTextStyle.Body -> MaterialTheme.typography.bodyMedium
                UiTextStyle.Caption -> MaterialTheme.typography.bodySmall
            },
        )
        is UiNode.Divider -> Box(
            Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant),
        )
        is UiNode.Notice -> Text(
            node.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        is UiNode.Button -> DynamicButton(node, slot, onAction)
        is UiNode.Input -> DynamicInput(node, slot)
    }
}

@Composable
private fun DynamicButton(
    node: UiNode.Button,
    slot: DynamicSlot,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
) {
    val scope = rememberCoroutineScope()
    var feedback by remember(slot.id, node.label) { mutableStateOf("") }
    var running by remember(slot.id, node.label) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            enabled = !running,
            onClick = {
                running = true
                scope.launch {
                    feedback = try {
                        when (val tap = node.onTap) {
                            is UiTap.Plugin -> slot.perform(tap.handler, tap.args).message
                            is UiTap.Host -> onAction?.invoke(null, SlotAction(node.label, tap.call))?.message
                                ?: "这个动作现在不能用"
                        }
                    } catch (t: Throwable) {
                        t.message ?: "执行失败"
                    } finally {
                        running = false
                    }
                }
            },
        ) {
            Text(node.label)
        }
        if (feedback.isNotEmpty()) {
            Text(feedback, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DynamicInput(node: UiNode.Input, slot: DynamicSlot) {
    var value by remember(slot.id, node.hint) { mutableStateOf("") }
    var feedback by remember(slot.id, node.hint) { mutableStateOf("") }
    var running by remember(slot.id, node.hint) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = { Text(node.hint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !running,
                onClick = {
                    val tap = node.onSubmit
                    if (tap !is UiTap.Plugin) return@OutlinedButton
                    running = true
                    scope.launch {
                        feedback = try {
                            val payload = tap.args + mapOf("value" to value)
                            val outcome = slot.perform(tap.handler, payload)
                            if (outcome.ok) value = ""
                            outcome.message
                        } catch (t: Throwable) {
                            t.message ?: "没记上"
                        } finally {
                            running = false
                        }
                    }
                },
            ) {
                Text(node.submitLabel)
            }
            if (feedback.isNotEmpty()) {
                Text(feedback, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
