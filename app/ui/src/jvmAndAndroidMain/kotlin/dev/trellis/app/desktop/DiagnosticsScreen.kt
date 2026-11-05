package dev.trellis.app.desktop

import androidx.compose.foundation.clickable
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
import dev.trellis.kernel.audit.Actor
import dev.trellis.kernel.audit.AuditEntry
import dev.trellis.kernel.audit.AuditOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 诊断：内核记下的事。
 *
 * 上面是「最近操作」—— 装、启用、停用、删除、彻底删除都留一行，重启之后还在
 * （`~/.trellis/audit.log`）。用户问「谁把插件删了」时，这里就是答案。
 * 下面是「出过什么事」—— 全部内核错误，按发生顺序。
 *
 * 这一页不在导航里：从设置页的「诊断」那块进来（左侧导航会把「设置」点亮）。
 */
@Composable
fun DiagnosticsScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    val actions = runtime.auditEntries
    val errors = runtime.kernelErrors

    Column(modifier) {
        ChromeSecondaryHeader(
            title = "诊断",
            subtitle = "内核记下的事，不是插件自己说的",
            onBack = { runtime.currentTab = TAB_SETTINGS },
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("最近操作", style = MaterialTheme.typography.titleSmall)
                Text(
                    "装 / 启用 / 停用 / 删除 / 彻底删除，只记内核自己做的事",
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(6.dp))
            }

            if (actions.isEmpty()) {
                item {
                    Text("还没有操作记录。下次装插件或删插件，这里会多一行。")
                    Spacer(Modifier.height(18.dp))
                }
            } else {
                // 最近的排最上面：查「刚才谁干的」没人想从最后一行往上翻。
                itemsIndexed(actions.asReversed().take(20)) { _, entry ->
                    AuditCard(entry)
                }
                item { Spacer(Modifier.height(18.dp)) }
            }

            item {
                Text("内核错误", style = MaterialTheme.typography.titleSmall)
                Text("诊断页只收错误；正常动作在上面", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
            }

            if (errors.isEmpty()) {
                item { Text("干干净净，一条错误都没有。") }
            } else {
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
}

@Composable
private fun AuditCard(entry: AuditEntry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                actorLabel(entry.actor) + " · " + actionLabel(entry.action) +
                    " · " + entry.pluginId?.value.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = if (entry.outcome == AuditOutcome.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                clockText(entry.atMs) + " · " + entry.detail,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun actorLabel(actor: Actor): String = when (actor) {
    Actor.USER -> "你"
    Actor.AGENT -> "管理员"
    Actor.SYSTEM -> "系统"
}

private fun actionLabel(action: String): String = when (action) {
    "install" -> "装上"
    "enable" -> "启用"
    "disable" -> "停用"
    "remove" -> "删除"
    "purge" -> "彻底删除"
    "purge_expired" -> "到期清理"
    else -> action
}

private val auditClock: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

private fun clockText(atMs: Long): String =
    Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).format(auditClock)
