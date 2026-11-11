package dev.trellis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos

/**
 * 插件输入框的浮窗：插件卡片上只留一行「触发器」，点开才弹这个。
 *
 * 为什么是宿主画而不是插件画：卡片是看数据的地方，输入框常驻会把卡面撑成表单，
 * 同一件事的提示还容易写两遍。宿主统一收口成「一行字 + 浮窗」，所有插件一致，
 * 插件作者只写 hint / submitLabel / handler，不用自己拼控件。
 *
 * 提交后浮窗自动关掉，结果文字由调用方显示在卡片上（[onSubmit] 返回值就是那句人话）。
 */
@Composable
fun PluginInputDialog(
    hint: String,
    submitLabel: String,
    running: Boolean = false,
    onDismissRequest: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val focus = remember { FocusRequester() }
    val confirmLabel = submitLabel.ifBlank { "提交" }

    // 浮窗刚组合出来时 FocusRequester 可能还没挂到节点上（离屏渲染、宿主首次组合都会遇到），
    // 所以要重试几次、而且失败绝不能把异常扔给线程——那会污染同一个 JVM 里后面的测试和协程。
    // 实在抢不到焦点也没关系：用户点一下输入框就能开始打字。
    LaunchedEffect(Unit) {
        // 每帧试一次：浮窗的焦点节点要等 layout 挂上之后才存在，早一帧就抢不到。
        // 抢不到不算错误（用户点一下输入框也能输入），但绝不能把异常扔给线程。
        repeat(20) {
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    fun submit() {
        if (running) return
        onSubmit(field.text)
        field = TextFieldValue("")
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            val composing = field.composition != null
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !composing) {
                                submit()
                                true
                            } else {
                                false
                            }
                        },
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth()) {
                            if (field.text.isEmpty()) {
                                Text(
                                    hint,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = muted.copy(alpha = 0.65f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f))
            }
        },
        confirmButton = {
            TextButton(enabled = !running, onClick = { submit() }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(enabled = !running, onClick = onDismissRequest) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
