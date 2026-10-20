package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.trellis.kernel.capability.Capabilities

/**
 * 审批框  设计文档里「危险操作的自动审查、手动批准」的**界面那一半**。
 * 没有它，内核只能默认拒绝（理由是「没有可用的确认界面」）。
 *
 * 注意它只负责**第二道闸**：
 *  - 授权（「这个插件有没有删数据的**权力**」）在插件详情页里给，一次性
 *  - 审批（「它**现在**要删，准不准」）在这里问，每次都要
 *
 * 为什么不是弹窗：Compose Desktop 的 Dialog 是**另一个操作系统窗口**，
 * 主窗口最大化时它会被盖在下面。用户看到的是「什么都没发生」，
 * 而规则其实卡在那里等一个永远看不到的回答  这个坑真的踩到了。
 * 所以它现在长在窗口内部最上方，切到哪个标签都在，不可能错过。
 *
 * 也不做成启动时的模态框：内核启动过程中界面还没起来，弹不出来也答不了，只会死锁。
 */
@Composable
fun AskDialogs(runtime: AppRuntime) {
    val request = runtime.pendingApproval ?: return

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text("需要你确认", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(request.summary, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(10.dp))
            Text(
                "发起者 " + actorLabel(request.actor) +
                    "　插件 " + request.pluginId.value +
                    "　能力 " + Capabilities.describe(request.capability) +
                    "　动作 " + request.action,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "规则是后台触发的，它正卡在这里等你点头；不理它，它就一直不动。",
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { runtime.answerApproval(false) }) { Text("拒绝") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { runtime.answerApproval(true) }) { Text("允许") }
            }
        }
    }
}

private fun actorLabel(actor: dev.trellis.kernel.audit.Actor): String = when (actor) {
    dev.trellis.kernel.audit.Actor.USER -> "你"
    dev.trellis.kernel.audit.Actor.AGENT -> "智能体"
    dev.trellis.kernel.audit.Actor.SYSTEM -> "系统"
}
