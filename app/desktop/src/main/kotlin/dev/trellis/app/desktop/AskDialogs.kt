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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.trellis.kernel.capability.Capabilities

/**
 * 审批框  设计文档里「危险操作的自动审查、手动批准」的**界面那一半**。
 * 没有它，内核只能默认拒绝（理由是「没有可用的确认界面」）。
 *
 * 注意它只负责**第二道闸**：
 *  - 授权（「这个插件有没有删数据的**权力**」）在插件详情页里给，一次性
 *  - 审批（「它**现在**要删，准不准」）在这里问，每次都要
 *
 * 之所以不把授权也做成启动时的模态框：内核启动过程中界面还没起来，
 * 弹不出来也答不了，只会死锁  这个坑真的踩到了。
 */
@Composable
fun AskDialogs(runtime: AppRuntime) {
    runtime.pendingApproval?.let { request ->
        Dialog(onDismissRequest = { runtime.answerApproval(false) }) {
            Card {
                Column(Modifier.padding(20.dp).width(380.dp)) {
                    Text("需要你确认", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    Text(request.summary, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    Text("发起者　" + actorLabel(request.actor), style = MaterialTheme.typography.labelSmall)
                    Text("插件　　" + request.pluginId.value, style = MaterialTheme.typography.labelSmall)
                    Text("能力　　" + Capabilities.describe(request.capability), style = MaterialTheme.typography.labelSmall)
                    Text("动作　　" + request.action, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "这个操作不可撤销，所以每次都要你来点。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { runtime.answerApproval(false) }) { Text("拒绝") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { runtime.answerApproval(true) }) { Text("允许") }
                    }
                }
            }
        }
    }
}

private fun actorLabel(actor: dev.trellis.kernel.audit.Actor): String = when (actor) {
    dev.trellis.kernel.audit.Actor.USER -> "你"
    dev.trellis.kernel.audit.Actor.AGENT -> "智能体"
    dev.trellis.kernel.audit.Actor.SYSTEM -> "系统"
}
