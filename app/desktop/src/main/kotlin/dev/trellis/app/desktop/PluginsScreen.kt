package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.trellis.kernel.api.PluginInfo
import dev.trellis.kernel.capability.Capabilities
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.extension.Contribution
import dev.trellis.kernel.extension.ServiceBinding
import dev.trellis.kernel.extension.displayName
import dev.trellis.kernel.plugin.PluginState
import kotlinx.coroutines.launch

/**
 * 插件管理。
 *
 * **默认折叠**：不点开时一行只回答两个问题  「这是谁」「它活着吗」。
 * 贡献清单和错误都在详情里，因为那是要看的时候才看的，
 * 平时铺开会把「哪些插件出问题了」这个最重要的信息淹掉。
 */
@Composable
fun PluginsScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(emptySet<String>()) }

    Column(modifier) {
        // 标题、目录、一个按钮挤在一行：这一页真正要看的是下面的列表，
        // 头部占两行只是把「最近一次出事的插件」往下推。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("插件", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Text(
                runtime.pluginsDir.absolutePath + "　·　把两份 JSON 丢进去，重启就会多一张卡片",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { runtime.openPluginsFolder() }) { Text("打开插件目录") }
        }
        Spacer(Modifier.height(12.dp))

        // 「彻底删除」不可撤销，所以要点两下：第一下只是把按钮换成确认，第二下才真删。
        // 记住的是 id 不是整条记录  记录会被刷新换掉，id 不会。
        // 声明必须放在 LazyColumn 外面：LazyListScope 不是 @Composable 上下文，remember 在那里不合法。
        var purgeArmed by remember { mutableStateOf<String?>(null) }

        val plugins = runtime.pluginInfos
        if (plugins.isEmpty()) {
            // 不能在这里 return：插件全删光的时候，回收站恰恰是最该看见的东西。
            Text("没有插件在跑。")
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(plugins, key = { it.manifest.id.value }) { info ->
                val id = info.manifest.id.value
                val (declared, granted) = runtime.capabilitiesOf(info.manifest.id)
                PluginCard(
                    info = info,
                    expanded = id in expanded,
                    lastUsedMs = runtime.lastUsedAt(id),
                    providesTheme = runtime.pluginProvidesTheme(id),
                    themeActive = runtime.themeIsActiveFrom(id),
                    onToggle = { expanded = if (id in expanded) expanded - id else expanded + id },
                    contributions = runtime.contributionsOf(info.manifest.id),
                    services = runtime.servicesOf(info.manifest.id),
                    declared = declared,
                    granted = granted,
                    onGrant = {
                        scope.launch { runtime.grantAndReload(info.manifest.id, declared) }
                    },
                    onEdit = { runtime.askInChat("改造插件 " + id + "：") },
                    onDisable = { scope.launch { runtime.disablePlugin(id) } },
                    onRemove = { scope.launch { runtime.removePlugin(id) } },
                )
            }

            // 停用的插件单独列在下面  它们不加载，但用户应该能看见并启用回来
            val bin = runtime.recycleBin
            // 删掉的插件同时被记进了停用清单（否则重启会自己回来），
            // 但它属于回收站，不该混在「已停用」里  混着看会以为删除没生效。
            val disabled = runtime.disabledPlugins - bin.map { it.id }.toSet()
            if (disabled.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(10.dp))
                    Text("已停用", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "不加载，但文件和数据都还在。启用一次就回来。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                items(disabled.map { it.value }.sorted(), key = { "disabled-" + it }) { rawId ->
                    DisabledRow(
                        runtime = runtime,
                        rawId = rawId,
                        onEnable = { scope.launch { runtime.enablePlugin(rawId) } },
                        onRemove = { scope.launch { runtime.removePlugin(rawId) } },
                    )
                }
            }

            if (bin.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(10.dp))
                    Text("回收站", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "删掉的插件在这里等 7 天，到期连插件文件带数据一起清掉。" +
                            "点「还原」就全回来；不想等这 7 天就点「彻底删除」，" +
                            "那一下不可撤销，只有日志留着。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                items(bin, key = { "bin-" + it.id.value }) { entry ->
                    val raw = entry.id.value
                    RecycleRow(
                        entry = entry,
                        armed = purgeArmed == raw,
                        onArm = { purgeArmed = raw },
                        onCancel = { purgeArmed = null },
                        onRestore = { scope.launch { runtime.enablePlugin(raw) } },
                        onPurge = {
                            purgeArmed = null
                            scope.launch { runtime.purgePlugin(raw) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecycleRow(
    entry: RecycleEntry,
    armed: Boolean,
    onArm: () -> Unit,
    onCancel: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(Color(0xFFBDBDBD), CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (armed) {
                        "确认之后，插件文件和它的全部数据立刻清掉，只有日志留着。"
                    } else {
                        entry.id.value + "　还有 " + entry.daysLeft(System.currentTimeMillis()) + " 天"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (armed) {
                TextButton(onClick = onCancel) { Text("算了") }
                TextButton(onClick = onPurge) {
                    Text(
                        "确认彻底删除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                TextButton(onClick = onRestore) { Text("还原") }
                TextButton(onClick = onArm) { Text("彻底删除") }
            }
        }
    }
}

@Composable
private fun DisabledRow(
    runtime: AppRuntime,
    rawId: String,
    onEnable: () -> Unit,
    onRemove: () -> Unit,
) {
    val id = dev.trellis.kernel.core.PluginId(rawId)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(Color(0xFF9E9E9E), CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(runtime.nameOf(id), style = MaterialTheme.typography.titleSmall)
                Text(rawId, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onEnable) { Text("启用") }
            TextButton(onClick = onRemove) { Text("删除") }
        }
    }
}

@Composable
private fun PluginCard(
    info: PluginInfo,
    expanded: Boolean,
    lastUsedMs: Long?,
    providesTheme: Boolean,
    themeActive: Boolean,
    onToggle: () -> Unit,
    contributions: List<Contribution>,
    services: List<ServiceBinding>,
    declared: Set<CapabilityId>,
    granted: Set<CapabilityId>,
    onGrant: () -> Unit,
    onEdit: () -> Unit,
    onDisable: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {

            // ---- 折叠时只有这一行 ----
            //
            // 悬停时多给一句「上次用」。插件页原本只能回答「这是谁、活着没」，
            // 回答不了「我上次用它是什么时候」——主题这种不产生卡片的插件尤其明显。
            var hovered by remember { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            when (awaitPointerEvent().type) {
                                PointerEventType.Enter -> hovered = true
                                PointerEventType.Exit -> hovered = false
                                else -> Unit
                            }
                        }
                    }
                },
            ) {
                StatusDot(info.state)
                Spacer(Modifier.width(10.dp))
                Text(
                    info.manifest.name.ifBlank { info.manifest.id.value },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (hovered) {
                    val hint = usageHint(System.currentTimeMillis(), lastUsedMs, providesTheme, themeActive)
                    if (hint != null) {
                        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                    }
                }
                Text(stateLabel(info.state), style = MaterialTheme.typography.labelMedium, color = stateColor(info.state))
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onToggle) { Text(if (expanded) "收起" else "详情") }
            }

            // ---- 展开后的详情 ----
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Field("标识", info.manifest.id.value)
                Field("版本", info.manifest.version.toString())
                Field("来源", loaderLabel(info.manifest.loader))
                Field(
                    "类型",
                    if (info.manifest.required) "必需插件（不可卸载）" else "普通插件（可卸载）",
                )
                // 说明和用法：没有这两行，用户只能靠反复开关插件找不同
                if (info.manifest.description.isNotBlank()) {
                    Field("说明", info.manifest.description)
                }
                if (info.manifest.usage.isNotBlank()) {
                    Field("用法", info.manifest.usage)
                }
                if (info.manifest.limitation.isNotBlank()) {
                    Field("局限", info.manifest.limitation)
                }

                // ---- 权限 ----
                //
                // 「授权」放在这里而不是启动时的模态框，有两个原因：
                //  1. 内核启动过程中界面还没起来，弹框会死锁
                //  2. 它本来就该是一个**看得见、改得动**的状态，
                //     而不是弹一次就消失、之后不知道去哪儿找
                if (declared.isNotEmpty()) {
                    Section("权限（" + granted.size + " / " + declared.size + "）")
                    for (capability in declared.sortedBy { it.value }) {
                        val ok = capability in granted
                        Text(
                            (if (ok) " 已授予　" else " 未授予　") + Capabilities.describe(capability),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    val missing = declared - granted
                    if (missing.isNotEmpty()) {
                        TextButton(onClick = onGrant) { Text("授予缺少的 " + missing.size + " 项（会重载插件）") }
                    }
                }

                val total = contributions.size + services.size
                Section("贡献（" + total + "）")
                if (total == 0) {
                    Text(
                        "什么都没贡献  它加载成功了，但没往任何挂载点上放东西。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                for (s in services) {
                    Text(" 服务　" + s.contract.displayName(), style = MaterialTheme.typography.bodySmall)
                }
                for (c in contributions) {
                    Text(
                        " 卡片　" + c.pointId.value + "    " + c.id,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (info.errors.isNotEmpty()) {
                    Section("错误（" + info.errors.size + "）")
                    for (e in info.errors) {
                        Text(
                            "[" + e.code.code + "] " + e.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (!info.manifest.required && info.state != PluginState.DISPOSED) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onEdit) { Text("让 AI 改") }
                        TextButton(onClick = onDisable) { Text("停用") }
                        if (info.manifest.builtin) {
                            // 内置插件的代码编译在应用里，删不掉  只能停用
                            Text(
                                "内置插件无法删除（代码在应用里）",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            TextButton(onClick = onRemove) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun StatusDot(state: PluginState) {
    Box(Modifier.size(10.dp).background(stateColor(state), CircleShape))
}

private fun stateColor(state: PluginState): Color = when (state) {
    PluginState.ACTIVE -> Color(0xFF2E7D32)
    PluginState.FAILED -> Color(0xFFC62828)
    PluginState.SKIPPED, PluginState.UNAVAILABLE -> Color(0xFFEF6C00)
    else -> Color(0xFF757575)
}

private fun stateLabel(state: PluginState): String = when (state) {
    PluginState.ACTIVE -> "已加载"
    PluginState.FAILED -> "失败"
    PluginState.SKIPPED -> "已跳过"
    PluginState.UNAVAILABLE -> "本设备不可用"
    PluginState.DISPOSED -> "已卸载"
    else -> state.name
}

/**
 * 悬停时那句提示。
 *
 * 主题类插件先说「在用 / 上次用」，普通插件只说「上次用」；
 * 没有任何可说的就返回 null —— 这一行不长东西，悬停时布局才不跳。
 */
internal fun usageHint(
    nowMs: Long,
    lastUsedMs: Long?,
    providesTheme: Boolean,
    themeActive: Boolean,
): String? = when {
    themeActive && lastUsedMs != null ->
        "正在使用 · " + PluginUsageStore.describe(nowMs, lastUsedMs)
    themeActive -> "正在使用"
    lastUsedMs != null -> "上次用：" + PluginUsageStore.describe(nowMs, lastUsedMs)
    providesTheme -> "还没用过"
    else -> null
}

private fun loaderLabel(loader: String): String = when (loader) {
    "builtin" -> "内置（编译进应用）"
    "dsl" -> "声明式（磁盘上的 JSON）"
    "script" -> "脚本（会跑的代码，关在沙箱里）"
    "catalog" -> "目录"
    else -> loader
}
