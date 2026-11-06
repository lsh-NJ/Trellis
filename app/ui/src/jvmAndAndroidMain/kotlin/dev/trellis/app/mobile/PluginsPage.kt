package dev.trellis.app.mobile

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.kernel.plugin.PluginState
import kotlinx.coroutines.launch

/**
 * 插件列表。
 *
 * 手机上原来只做三件事：看它活着没、不想让它跑就关掉、缺权限补授权 ——
 * 装和卸都指望桌面端。理由当时写的是「没有文件选择器那套交互」，但安卓有 SAF，
 * 这个理由不成立。现在四件事都能做：**装（从 zip）**、看、开关、**卸**。
 *
 * 「必需」插件不给开关：关掉它整个应用就没有任务/计划了，那不是用户想表达的意图。
 */
@Composable
fun PluginsPage(
    runtime: AppRuntime,
    onPickArchive: (((java.io.File) -> Unit) -> Unit)?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var granting by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingUninstall by remember { mutableStateOf<String?>(null) }

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
                        if (onPickArchive != null) {
                            // 安卓上脚本插件的 loader 是刻意不注册的（ScriptLoader.platforms 只有 JVM），
                            // 所以用户选完压缩包一定会看到「没有可用于 [script] 的 loader」。
                            // 与其让人白选一次文件，不如在动手前就说清楚能装什么、不能装什么。
                            "手机上目前只能装「声明式」插件（manifest.json + main.dsl.json）。" +
                                "带脚本的压缩包（入口是 main.js）需要桌面端 —— 安卓暂不提供脚本沙箱，" +
                                "硬装会提示「没有可用的 loader」。"
                        } else {
                            "把 manifest.json + main.dsl.json 放进下面这个目录，重启 App 就能看到。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onPickArchive != null) {
                        Spacer(Modifier.height(Gap.m))
                        PhoneButton(
                            if (installing) "安装中…" else "从文件安装",
                            {
                                if (installing) return@PhoneButton
                                onPickArchive { file ->
                                    installing = true
                                    scope.launch {
                                        val error = runtime.installPluginArchive(file)
                                        // 选中的文件是壳复制出来的临时副本，装完就没用了。
                                        runCatching { file.delete() }
                                        message = error ?: "已安装，列表里往下找"
                                        installing = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(Gap.s))
                    Text(
                        runtime.pluginsDir.absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            message?.let {
                item {
                    Text(
                        it,
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

                        // 卸载：内置的不给（那是应用的一部分），第三方的要两步 ——
                        // 装一个插件可能花了很久，误触一下就没了太粗暴。
                        if (!info.manifest.builtin) {
                            Spacer(Modifier.height(Gap.s))
                            val confirming = pendingUninstall == info.manifest.id.value
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (confirming) "卸载后数据进回收站，可以恢复" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (confirming) "确认卸载" else "卸载",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (confirming) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            if (!confirming) {
                                                pendingUninstall = info.manifest.id.value
                                            } else {
                                                pendingUninstall = null
                                                scope.launch {
                                                    message = runtime.uninstallPlugin(info.manifest.id.value)
                                                }
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
