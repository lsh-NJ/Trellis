package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 诊断：内核记下的全部错误。上面那张「插件」表看的是「谁活着」，这里看的是「出过什么事」。 */
@Composable
fun DiagnosticsScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("诊断", style = MaterialTheme.typography.titleLarge)
        Text("内核记下的全部错误，按发生顺序", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(12.dp))

        val errors = runtime.kernelErrors
        if (errors.isEmpty()) {
            Text("干干净净，一条记录都没有。")
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(errors) { _, e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "[" + e.code.code + "] " + e.code.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (e.code.recoverable) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        // 跨模块的公开属性不能智能转换，先取到局部变量
                        val owner = e.pluginId
                        if (owner != null) {
                            Text("插件 " + owner.value, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(e.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
