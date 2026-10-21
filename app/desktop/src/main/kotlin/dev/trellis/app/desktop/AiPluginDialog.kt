package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.trellis.ai.AgentOutcome
import kotlinx.coroutines.launch

/**
 * 「让 AI 写一个插件」。
 *
 * 界面刻意做得很朴素：一个输入框、一个按钮、一段流水账。
 * 因为**这一版要验证的是循环能不能闭合**，不是好不好看。
 * 流水账是重点：用户要能看见「它第几次尝试、卡在哪」，
 * 否则失败了就是个黑盒。
 */
@Composable
fun AiPluginDialog(
    runtime: AppRuntime,
    initialRequest: String = "",
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf(initialRequest) }
    var verdict by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!runtime.agentBusy) onClose() }) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.width(620.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (initialRequest.isBlank()) "让 AI 写一个插件" else "让 AI 改一个插件",
                    style = MaterialTheme.typography.titleMedium,
                )

                if (!runtime.llmConfigured()) {
                    // 没配 key 不是错误，是「还没开始用这个功能」。
                    // 所以给配置指引，而不是弹一个失败。
                    Text(
                        runtime.llmSetupHint(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClose) { Text("知道了") }
                    }
                    return@Column
                }

                Text(
                    "说一句你想要什么，它会生成一张卡片并直接装上。不满意可以在列表里删掉。",
                    style = MaterialTheme.typography.bodySmall,
                )

                OutlinedTextField(
                    value = request,
                    onValueChange = { request = it },
                    label = { Text("比如：一个提醒我今天还剩多少事的卡片") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !runtime.agentBusy,
                    maxLines = 3,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            verdict = null
                            scope.launch {
                                verdict = when (val outcome = runtime.generatePlugin(request)) {
                                    null -> "没有配置模型 key"
                                    is AgentOutcome.Installed ->
                                        "装好了：" + outcome.pluginId + "（第 " + outcome.attempts + " 次尝试）"
                                    is AgentOutcome.Refused ->
                                        "它没做，因为：" + outcome.reason
                                    is AgentOutcome.GaveUp ->
                                        "放弃了。最后一轮的毛病：\n" +
                                            outcome.problems.joinToString("\n") { "· " + it }
                                }
                            }
                        },
                        enabled = !runtime.agentBusy && request.isNotBlank(),
                    ) { Text(if (runtime.agentBusy) "生成中…" else "生成") }

                    if (runtime.agentBusy) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose, enabled = !runtime.agentBusy) { Text("关闭") }
                }

                if (runtime.agentLog.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        LazyColumn(Modifier.heightIn(max = 220.dp).padding(10.dp)) {
                            items(runtime.agentLog) { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }

                verdict?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                runtime.lastGenerated?.let { GeneratedPreviewView(it) }
                Spacer(Modifier.height(0.dp))
            }
        }
    }
}

/**
 * 「刚才到底生成了什么」。
 *
 * 这是这次改动里最要紧的一块。以前生成完只有一句「装好了」，
 * 用户得回首页一个字一个字地找不同 —— 而且找不到的时候
 * 根本不知道是「没生成」还是「挂在了一个界面上还不存在的位置」。
 *
 * 现在：卡片长什么样、挂在哪儿、**眼下看不看得见**，全摊在这儿。
 * 数据直接读内核里的真实贡献，不是模型说了什么。
 */
@Composable
private fun GeneratedPreviewView(preview: GeneratedPreview) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                preview.name + "　" + preview.pluginId,
                style = MaterialTheme.typography.titleSmall,
            )
            if (preview.description.isNotBlank()) {
                Text(preview.description, style = MaterialTheme.typography.bodySmall)
            }
            if (preview.usage.isNotBlank()) {
                Text("怎么用：" + preview.usage, style = MaterialTheme.typography.bodySmall)
            }
            // 模型自己承认退了一步。这句话必须显眼：不摆出来，
            // 用户就会以为这就是他要的，然后觉得这个插件很没用。
            if (preview.limitation.isNotBlank()) {
                Text(
                    "注意，它退了一步：" + preview.limitation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (preview.cards.isEmpty()) {
                Text(
                    "这个插件没有往界面上放任何卡片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                HorizontalDivider()
                for (card in preview.cards) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "挂在：" + card.mountPointLabel,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (!card.visible) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "这个位置界面上还没做，装了也看不见",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Text(card.title, style = MaterialTheme.typography.titleSmall)
                        Text(card.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
