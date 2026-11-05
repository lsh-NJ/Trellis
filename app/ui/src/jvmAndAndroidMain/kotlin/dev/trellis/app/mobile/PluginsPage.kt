package dev.trellis.app.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.kernel.plugin.PluginState
import kotlinx.coroutines.launch

/**
 * 插件列表。
 *
 * 手机上不做「装插件」（没有文件选择器那套交互），只做三件日常会做的事：
 * **看它是不是活着**、**不想让它跑就关掉**、**缺权限时补授权**。卸载留给桌面端。
 *
 * 「必需」插件不给开关：关掉它整个应用就没有任务/计划了，那不是用户想表达的意图。
 */
@Composable
fun PluginsPage(runtime: AppRuntime, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var granting by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = Gap.l)) {
            SubPageBar("插件", onBack)
        }
        LazyColumn(
            contentPadding = PaddingValues(start = Gap.l, end = Gap.l, bottom = Gap.xxl),
            verticalArrangement = Arrangement.spacedBy(Gap.m),
        ) {
            item {
                CardBox {
                    SectionLabel("插件从哪来")
                    Spacer(Modifier.height(Gap.s))
                    Text(
                        "把 manifest.json + main.dsl.json 放进下面这个目录，重启 App 就能看到。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Gap.s))
                    Text(
                        runtime.pluginsDir.absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            runtime.pluginInfos.forEach { info ->
                item(key = info.manifest.id.value) {
                    val active = info.state == PluginState.ACTIVE
                    val failed = info.state == PluginState.FAILED || info.errors.isNotEmpty()
                    CardBox {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(info.manifest.name, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.width(Gap.s))
                                    Pill(
                                        when {
                                            failed -> "出错"
                                            active -> "运行中"
                                            else -> "已停用"
                                        },
                                        when {
                                            failed -> Tone.Warn
                                            active -> Tone.Good
                                            else -> Tone.Neutral
                                        },
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    info.manifest.id.value + " · v" + info.manifest.version +
                                        (if (info.manifest.builtin) " · 内置" else " · 第三方"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (info.manifest.required) {
                                Text(
                                    "必需",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Switch(
                                    checked = active,
                                    onCheckedChange = { next ->
                                        scope.launch {
                                            if (next) runtime.enablePlugin(info.manifest.id.value)
                                            else runtime.disablePlugin(info.manifest.id.value)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            }
                        }
                        if (info.manifest.description.isNotBlank()) {
                            Spacer(Modifier.height(Gap.s))
                            Text(info.manifest.description, style = MaterialTheme.typography.bodySmall)
                        }

                        // 能力：声明了但没授予的，插件会优雅降级（拿不到能力返回 false）。
                        // 手机上给一个明确的补授权入口，比让用户去桌面端点一次更合理。
                        val (declared, granted) = runtime.capabilitiesOf(info.manifest.id)
                        if (declared.isNotEmpty()) {
                            Spacer(Modifier.height(Gap.s))
                            val missing = declared - granted
                            Text(
                                "能力 " + granted.size + "/" + declared.size +
                                    declared.joinToString("、", prefix = "（", postfix = "）") { it.value },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (missing.isNotEmpty()) {
                                Spacer(Modifier.height(Gap.s))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "缺 " + missing.size + " 项授权",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f),
                                    )
                                    PhoneButton(
                                        if (granting == info.manifest.id.value) "授权中…" else "全部授权",
                                        {
                                            granting = info.manifest.id.value
                                            scope.launch {
                                                runtime.grantAndReload(info.manifest.id, declared)
                                                granting = null
                                            }
                                        },
                                        filled = false,
                                    )
                                }
                            }
                        }

                        if (info.errors.isNotEmpty()) {
                            Spacer(Modifier.height(Gap.s))
                            info.errors.take(3).forEach { err ->
                                Text(
                                    "· " + err.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
