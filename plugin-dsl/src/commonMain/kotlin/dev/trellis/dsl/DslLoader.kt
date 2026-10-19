package dev.trellis.dsl

import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.core.VersionRange
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.diag.KernelError
import dev.trellis.kernel.plugin.ContributionDecl
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.plugin.PluginRequirement
import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.slot.TemplateSlot
import dev.trellis.kernel.slot.TextSlot
import dev.trellis.kernel.spi.DiscoveredPlugin
import dev.trellis.kernel.spi.DiscoveryResult
import dev.trellis.kernel.spi.Platform
import dev.trellis.kernel.spi.PluginDiscovery
import dev.trellis.kernel.spi.PluginLoader
import dev.trellis.kernel.time.KernelClock
import dev.trellis.kernel.time.SystemClock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 轨道 A：声明式插件加载器。
 *
 * 插件目录结构：
 *
 *   plugins/
 *     ai.review/
 *       manifest.json      身份、依赖、权限
 *       main.dsl.json      内容（这里只有 slots）
 *
 * **这个类存在的意义是：让「写一份 JSON」和「写一个 Kotlin 插件」在内核眼里完全等价。**
 * 于是 Agent 不需要编译器、不需要构建系统、不需要重启进程，
 * 就能给正在运行的程序加功能。
 *
 * 一条刻意的设计：`contributes` **不从 manifest.json 读，而是 discovery 阶段从 entry
 * 文件推导出来**。理由是这样 manifest 与内容不可能漂移  只有一处真相，
 * 而这个真相是内核自己算出来的，不是作者手写的一份副本。
 */
class DslLoader(
    private val fileSystem: PluginFileSystem,
    private val clock: KernelClock = SystemClock,
) : PluginLoader, PluginDiscovery {

    /**
     * 刻意不进构造函数：Json 是本模块的实现细节（implementation 依赖），
     * 让每个使用方都被迫把 kotlinx-serialization 加进自己的类路径是不礼貌的。
     * 严格模式也不是可调项  它是正确性决定，不是偏好。
     */
    private val json: Json = DEFAULT_JSON

    override val scheme: String = SCHEME

    override val name: String = "dsl-directory"

    override val platforms: Set<Platform> = Platform.entries.toSet()

    /** discovery 的产物，供 load 复用  避免「发现时解析一遍、装载时再解析一遍」产生分歧。 */
    private val catalogue: MutableMap<PluginId, ParsedPlugin> = mutableMapOf()

    override suspend fun discover(root: String): DiscoveryResult {
        val plugins = mutableListOf<DiscoveredPlugin>()
        val errors = mutableListOf<KernelError>()

        for (directoryName in fileSystem.listDirectories(root).sorted()) {
            val directory = fileSystem.join(root, directoryName)
            val record = parse(directory, errors) ?: continue
            catalogue[record.manifest.id] = record
            plugins += DiscoveredPlugin(record.manifest, directory)
        }

        return DiscoveryResult(plugins, errors)
    }

    override suspend fun load(manifest: PluginManifest, ctx: PluginContext): Plugin {
        val record = catalogue[manifest.id]
            ?: error(
                "DslLoader 没有发现过插件 " + manifest.id.value +
                    "；load 必须发生在 discover 之后（内核的正常流程总是如此）",
            )
        return DslPlugin(record.manifest, record.slots)
    }

    // ---------------------------------------------------------------- 解析

    private fun parse(directory: String, errors: MutableList<KernelError>): ParsedPlugin? {
        val manifestPath = fileSystem.join(directory, MANIFEST_FILE)
        if (!fileSystem.exists(manifestPath)) return null

        val manifestText = fileSystem.readText(manifestPath)
        if (manifestText == null) {
            errors += reject(null, "读不出 " + manifestPath)
            return null
        }

        val raw = try {
            json.decodeFromString(DslManifestJson.serializer(), manifestText)
        } catch (t: SerializationException) {
            errors += reject(null, manifestPath + " 解析失败：" + t.message)
            return null
        }

        // ---- 身份与版本：这几项缺了就没法继续，直接失败 ----
        val rawId = raw.id.trim()
        if (rawId.isEmpty()) {
            errors += reject(null, manifestPath + " 的 id 是空的")
            return null
        }
        val pluginId = PluginId(rawId)

        val version = parseVersion(raw.version, pluginId, manifestPath, "version", errors) ?: return null
        val sdkRange = parseRange(raw.sdkRange, pluginId, manifestPath, "sdkRange", errors) ?: return null

        val dependsOn = mutableListOf<PluginRequirement>()
        for (dep in raw.dependsOn) {
            val depId = dep.id.trim()
            if (depId.isEmpty()) {
                errors += reject(pluginId, manifestPath + " 的 dependsOn 里有空的 id")
                return null
            }
            val range = parseRange(dep.range, pluginId, manifestPath, "dependsOn[" + depId + "].range", errors)
                ?: return null
            dependsOn += PluginRequirement(PluginId(depId), range, dep.optional)
        }

        // ---- entry 文件 ----
        val entryPath = fileSystem.join(directory, raw.entry)
        if (!fileSystem.exists(entryPath)) {
            errors += reject(pluginId, "entry 文件不存在：" + entryPath)
            return null
        }
        val entryText = fileSystem.readText(entryPath)
        if (entryText == null) {
            errors += reject(pluginId, "读不出 " + entryPath)
            return null
        }
        val entry = try {
            json.decodeFromString(DslEntryJson.serializer(), entryText)
        } catch (t: SerializationException) {
            errors += reject(pluginId, entryPath + " 解析失败：" + t.message)
            return null
        }

        // ---- slot 校验：一次把所有问题都报出来，别让 Agent 改一个跑一次 ----
        val slots = mutableListOf<DslSlotJson>()
        val seenIds = mutableSetOf<String>()
        var slotProblems = 0

        entry.slots.forEachIndexed { index, slot ->
            val where = entryPath + " 的第 " + (index + 1) + " 个 slot"
            val slotId = slot.id.trim()
            val pointId = ExtensionPointId(slot.point.trim())

            if (slotId.isEmpty()) {
                errors += reject(pluginId, where + " 缺少 id")
                slotProblems++
            } else if (!seenIds.add(slotId)) {
                errors += reject(pluginId, where + " 的 id 与前面的重复：" + slotId)
                slotProblems++
            }

            if (!Slots.isKnown(pointId)) {
                errors += reject(
                    pluginId,
                    where + " 指向了不存在的挂载点 「" + pointId.value + "」；内核认识的是：" +
                        Slots.knownIds.sortedBy { it.value }.joinToString { it.value },
                )
                slotProblems++
            }

            if (slot.order < 0) {
                errors += reject(pluginId, where + " 的 order 不能为负（实际 " + slot.order + "）")
                slotProblems++
            }

            if (slotProblems == 0) slots += slot.copy(id = slotId, point = pointId.value)
        }

        if (slotProblems > 0) return null

        // ---- contributes 由内容推导 ----
        val contributes = slots
            .groupBy { ExtensionPointId(it.point) }
            .mapValues { (_, group) -> group.map { ContributionDecl(it.id, it.order) } }

        val manifest = PluginManifest(
            id = pluginId,
            version = version,
            name = raw.name.ifBlank { rawId },
            description = raw.description,
            usage = raw.usage,
            sdkRange = sdkRange,
            loader = raw.loader,
            dependsOn = dependsOn,
            capabilities = raw.capabilities.map { CapabilityId(it) }.toSet(),
            contributes = contributes,
            entry = raw.entry,
            builtin = raw.builtin,
            required = raw.required,
        )
        return ParsedPlugin(manifest, slots, directory)
    }

    private fun parseVersion(
        text: String,
        pluginId: PluginId,
        path: String,
        field: String,
        errors: MutableList<KernelError>,
    ): SemVer? = try {
        SemVer.parse(text)
    } catch (t: IllegalArgumentException) {
        errors += reject(pluginId, path + " 的 " + field + " 不是合法版本号：" + text)
        null
    }

    private fun parseRange(
        text: String,
        pluginId: PluginId,
        path: String,
        field: String,
        errors: MutableList<KernelError>,
    ): VersionRange? = try {
        VersionRange.parse(text)
    } catch (t: IllegalArgumentException) {
        errors += reject(pluginId, path + " 的 " + field + " 不是合法版本区间：" + text)
        null
    }

    private fun reject(pluginId: PluginId?, detail: String): KernelError =
        KernelError(ErrorCode.MANIFEST_INVALID, pluginId, detail, clock.nowMs())

    private data class ParsedPlugin(
        val manifest: PluginManifest,
        val slots: List<DslSlotJson>,
        val directory: String,
    )

    companion object {
        const val SCHEME: String = "dsl"
        const val MANIFEST_FILE: String = "manifest.json"

        /**
         * 严格模式。Agent 把 "title" 写成 "tittle" 时必须炸出来 
         * 静默渲染一张空卡片是比报错糟糕得多的失败模式，因为没人会去看。
         */
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }
    }
}

private fun containsPlaceholder(text: String): Boolean =
    text.contains("{{") && text.contains("}}")

/** 一个只会注册卡片、不会做别的事的插件。它的全部内容都来自磁盘。 */
private class DslPlugin(
    override val manifest: PluginManifest,
    private val slots: List<DslSlotJson>,
) : Plugin {

    override suspend fun init(ctx: PluginContext) {
        for (slot in slots) {
            val point = Slots.byId(ExtensionPointId(slot.point))
            if (point == null) {
                ctx.log("跳过未知挂载点 " + slot.point)
                continue
            }
            // 有占位符就产 TemplateSlot（渲染时求值），没有就产 TextSlot（静态）。
            // 区分不是为了省事：静态卡片没有「重新求值」这回事，
            // 让它永远不刷新，比每秒白跑一遍求值器更诚实。
            val card = if (containsPlaceholder(slot.title) || containsPlaceholder(slot.body)) {
                TemplateSlot(slot.id, slot.order, slot.title, slot.body)
            } else {
                TextSlot(slot.id, slot.order, slot.title, slot.body)
            }
            val accepted = ctx.extensions.register(point, card)
            if (!accepted) {
                ctx.log("挂载点 " + slot.point + " 拒绝了卡片 " + slot.id + "（见诊断）")
            }
        }
    }
}