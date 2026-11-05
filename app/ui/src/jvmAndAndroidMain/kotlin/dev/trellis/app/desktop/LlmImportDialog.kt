package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import dev.trellis.ui.LocalCardStyle
import dev.trellis.ai.LlmImport
import dev.trellis.ai.LlmPreset

/**
 * 「一键导入模型 API」。
 *
 * 设计取舍：
 * - **只有一块输入框**：用户手里可能是一串 key，也可能是服务商给的一整段 JSON，
 *   两种都往同一个地方粘，解析交给 [LlmImport]（它认三种形态）。
 * - **预设只决定「只有 key 时配哪家」**：粘了完整 JSON 时，JSON 里的地址优先。
 * - **失败不关窗**：错误原样显示在输入框下面，用户改一下再点一次；关掉就得重粘。
 * - 剪贴板按钮是给「复制了 key 再来设置」这条路径的，省一次 Ctrl+V。
 */
@Composable
internal fun LlmImportDialog(
    runtime: AppRuntime,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var raw by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(LlmImport.PRESETS.first()) }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    fun submit() {
        val outcome = runtime.importLlmConfig(raw, preset.endpoint, preset.model)
        if (outcome.ok) {
            onDone(outcome.message)
        } else {
            error = outcome.message
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // 对话框也走宿主的圆角与底色：M3 默认是 28dp 的浅紫面，和这套壳子不是一家人。
        shape = RoundedCornerShape(LocalCardStyle.current.radius),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("导入模型 API", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "粘贴 API key，或服务商给的一整段配置。导入后立刻生效，不用重启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 预设只影响「只粘了 key」的情况：地址和模型从这儿来。
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LlmImport.PRESETS.forEach { option ->
                        PresetChip(
                            preset = option,
                            selected = option == preset,
                            onClick = { preset = option },
                        )
                    }
                }

                ChromeTextField(
                    value = raw,
                    onValueChange = {
                        raw = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    placeholder = "sk-…   或者   {\"endpoint\":\"…\",\"apiKey\":\"…\",\"model\":\"…\"}",
                    singleLine = false,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChromeButton(
                        label = "从剪贴板粘贴",
                        onClick = {
                            val text = clipboard.getText()?.text
                            if (text.isNullOrBlank()) {
                                error = "剪贴板是空的。先把 key 复制下来。"
                            } else {
                                raw = text
                                error = null
                            }
                        },
                        filled = false,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        preset.name + " · " + preset.model,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            ChromeButton(
                label = "导入",
                onClick = { submit() },
                enabled = raw.isNotBlank(),
            )
        },
        dismissButton = {
            ChromeButton(label = "取消", onClick = onDismiss, filled = false)
        },
    )
}

/** 服务商胶囊：选中用主色淡底，未选中只有一条细边。 */
@Composable
private fun PresetChip(preset: LlmPreset, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val foreground = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(shape)
            .background(background)
            .border(
                1.dp,
                if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(preset.name, style = MaterialTheme.typography.labelSmall, color = foreground)
    }
}
