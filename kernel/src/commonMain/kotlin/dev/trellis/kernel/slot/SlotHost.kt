package dev.trellis.kernel.slot

import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.extension.ExtensionPoint
import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.extension.ExtensionRegistry
import dev.trellis.kernel.slot.TemplateSlot

/** 渲染单个贡献。**允许抛异常**  ErrorBoundary 在 [SlotHost] 里。 */
fun interface SlotRenderer {
    fun renderOne(slotId: ExtensionPointId, contribution: SlotContribution): String
}

/** 控制台假渲染器：把贡献打成一行文本。用来在 M2 验证装卸，不负责好看。 */
class ConsoleSlotRenderer : SlotRenderer {
    override fun renderOne(slotId: ExtensionPointId, contribution: SlotContribution): String =
        when (contribution) {
            is TextSlot -> "  [" + contribution.id + "] " + contribution.title + " | " + contribution.body
            else -> "  [" + contribution.id + "] <当前渲染器不认识这种贡献>"
        }
}

/**
 * 挂载点宿主。设计文档 5.2 的 ErrorBoundary 落在这里：
 *
 *   - 单个贡献抛异常 -> 只把它自己降级，其他贡献照常渲染
 *   - 降级过的贡献本次会话内不再尝试，避免每次重组都炸一遍
 *   - 失败原因交给诊断收集器，不吞掉
 *
 * 这条是硬要求：一个插件的 UI 崩了，用户看到的是「此卡片暂时不可用」，
 * 而不是整个 App 白屏。
 */
class SlotHost(
    private val registry: ExtensionRegistry,
    private val renderer: SlotRenderer,
    private val onFailure: (slotId: ExtensionPointId, contribution: SlotContribution, error: Throwable) -> Unit =
        { _, _, _ -> },
) {

    private val quarantined = mutableSetOf<String>()

    fun render(point: ExtensionPoint<SlotContribution>): List<String> =
        registry.all(point).map { renderSafely(point.id, it) }

    /**
     * 求值之后再渲染。
     *
     * [TemplateSlot] 的内容要问数据源，而那是挂起的  所以有了这个变体。
     * 求值失败的部分降级成 `--`，**单张卡片照样能画出来**。
     */
    suspend fun renderResolved(
        point: ExtensionPoint<SlotContribution>,
        context: DataContext,
        onFailure: (String) -> Unit = {},
    ): List<String> =
        registry.all(point).map { contribution ->
            renderSafely(point.id, resolveTemplate(contribution, context, onFailure))
        }

    private suspend fun resolveTemplate(
        contribution: SlotContribution,
        context: DataContext,
        onFailure: (String) -> Unit,
    ): SlotContribution = if (contribution is TemplateSlot) {
        TextSlot(
            id = contribution.id,
            order = contribution.order,
            title = registry.renderTemplate(contribution.title, context, onFailure),
            body = registry.renderTemplate(contribution.body, context, onFailure),
        )
    } else {
        contribution
    }

    fun render(pointId: ExtensionPointId, contributions: List<SlotContribution>): List<String> =
        contributions.map { renderSafely(pointId, it) }

    private fun renderSafely(slotId: ExtensionPointId, contribution: SlotContribution): String {
        val key = slotId.value + "#" + contribution.id
        if (key in quarantined) return degraded(contribution, "本次会话已隔离")

        return try {
            renderer.renderOne(slotId, contribution)
        } catch (t: Throwable) {
            quarantined += key
            onFailure(slotId, contribution, t)
            degraded(contribution, "此卡片暂时不可用")
        }
    }

    private fun degraded(contribution: SlotContribution, reason: String): String =
        "  [" + contribution.id + "] <" + reason + ">"
}