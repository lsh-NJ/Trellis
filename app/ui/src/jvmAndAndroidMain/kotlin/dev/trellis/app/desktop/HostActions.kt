package dev.trellis.app.desktop

import dev.trellis.kernel.action.ActionArgs
import dev.trellis.kernel.action.ActionParam
import dev.trellis.kernel.action.KernelAction
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.core.VersionRange
import dev.trellis.kernel.extension.KernelPoints
import dev.trellis.kernel.plugin.ContributionDecl
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest

/**
 * 宿主的**通用**原子动作。
 *
 * 为什么是「一组原子动作」而不是「每个需求加一个动作」：插件没有代码，只能点名宿主
 * 已经实现好的能力。以前每来一个需求（打开某页、写一篇笔记、发通知、开计时器）就加
 * 一个大而全的专用动作，宿主会越堆越像业务层；而插件真正需要的是**可组合的积木**——
 * 一个「读书记录」卡片 = 写笔记 + 打开知识库 + 起计时器，三块现成的动作拼起来，不必
 * 让宿主认识「读书」这个词。
 *
 * 四个动作全部 `capability = null`：
 *
 *  - 它们都由用户亲手点自己卡片上的按钮触发，和「新建任务」同级；
 *  - 作用范围只在本机应用内（切页 / 写自己的笔记 / 发一条通知 / 开自己的计时器），
 *    不读外部数据、不联网、不越权；
 *  - 挂 capability 的代价是每点一次都弹一次审批——对「一键开始」这种高频低风险
 *    操作是体验灾难。真要有风险，也是插件 manifest 声明的权限门管，不是这些动作。
 */
internal class HostPlugin(private val runtime: AppRuntime) : Plugin {

    override val manifest: PluginManifest = PluginManifest(
        id = PluginId("core.host"),
        version = SemVer.parse("1.0.0"),
        name = "宿主通用动作",
        sdkRange = VersionRange.parse(">=1.0.0 <2.0.0"),
        loader = "builtin",
        builtin = true,
        description = "把切页、写笔记、发通知、开计时器这些宿主能力开放给插件按钮",
        contributes = mapOf(
            KernelPoints.Action.id to listOf(
                ContributionDecl("openPage"),
                ContributionDecl("writeKnowledgeNote"),
                ContributionDecl("notify"),
                ContributionDecl("startFocusTimer"),
            ),
        ),
    )

    override suspend fun init(ctx: PluginContext) {
        ctx.extensions.register(KernelPoints.Action, OpenPageAction(runtime))
        ctx.extensions.register(KernelPoints.Action, WriteKnowledgeNoteAction(runtime))
        ctx.extensions.register(KernelPoints.Action, NotifyAction(runtime))
        ctx.extensions.register(KernelPoints.Action, StartFocusTimerAction(runtime))
    }
}

/**
 * 打开某个页面，可选地把目标也带上。
 *
 * 页名解析**复用** [AppRuntime.openTab]：映射只维护一份，动作与页面不会各说各话。
 * `target` 的语义按页不同：知识库是笔记标题、计划是计划 id 或名字、活动是日期；
 * 其它页忽略 target。
 */
internal class OpenPageAction(private val runtime: AppRuntime) : KernelAction {

    override val id: String = "openPage"
    override val order: Int = 130
    override val label: String = "打开某个页面"

    override val params: List<ActionParam> = listOf(
        ActionParam(
            "page",
            "要打开的页面：任务 / 计划 / 知识库 / 对话 / 活动 / 设置 / 插件 / 诊断（中英文都认）",
        ),
        ActionParam(
            "target",
            "可选：知识库页填笔记标题，计划页填计划 id 或名字，活动页填日期 YYYY-MM-DD",
            required = false,
        ),
    )

    override val capability: CapabilityId? = null

    override suspend fun execute(args: ActionArgs): String {
        val page = args.require("page")
        // 先切页：顺带把「有没有这一页」的校验交给 openTab，未知页它会给人话和合法值。
        val switched = runtime.openTab(page)
        if (switched.startsWith("没有这一页")) return switched
        val target = args.get("target") ?: return switched

        return when (runtime.currentTab) {
            TAB_KNOWLEDGE -> openKnowledgeTarget(target)
            TAB_PLAN -> openPlanTarget(target)
            TAB_ACTIVITY -> runtime.openActivityDay(target)
            else -> switched
        }
    }

    private fun openKnowledgeTarget(target: String): String {
        val store = try {
            runtime.knowledgeStore
        } catch (_: UninitializedPropertyAccessException) {
            return "知识库还没准备好，暂时打不开笔记。"
        }
        val note = store.list().firstOrNull { it.title.trim() == target }
            ?: return "知识库里没有这篇笔记：" + target + "；要新建就用 openKnowledgeNote。"
        runtime.openKnowledgeReveal(note.id, -1, -1)
        return "打开了笔记「" + note.title.trim() + "」"
    }

    private fun openPlanTarget(target: String): String {
        // planAll 里没有 name 字段，计划的可读名是 title：先按 id 精确找，再按标题
        // 精确/包含匹配——插件作者多半只知道用户嘴里的计划名，不知道内部 id。
        val plan = runtime.planAll.firstOrNull { it.id == target }
            ?: runtime.planAll.firstOrNull { it.title.trim() == target }
            ?: runtime.planAll.firstOrNull { it.title.contains(target, ignoreCase = true) }
            ?: return "没有找到计划：" + target + "。现有计划：" +
                (runtime.planAll.map { it.title }.takeIf { it.isNotEmpty() }?.joinToString("、")
                    ?: "还没有计划")
        runtime.selectPlan(plan.id)
        runtime.currentTab = TAB_PLAN
        return "打开了计划「" + plan.title + "」"
    }
}

/**
 * 写一篇知识库笔记。
 *
 * `mode` 决定已存在同名笔记时的行为：append（默认）追加、replace 覆盖、createOnly
 * 原样不动。同名判定沿用 [OpenKnowledgeNoteAction]：`title.trim()` 精确匹配。
 */
internal class WriteKnowledgeNoteAction(private val runtime: AppRuntime) : KernelAction {

    override val id: String = "writeKnowledgeNote"
    override val order: Int = 131
    override val label: String = "写一篇知识库笔记"

    override val params: List<ActionParam> = listOf(
        ActionParam("title", "笔记标题"),
        ActionParam("body", "要写进笔记的正文（Markdown）"),
        ActionParam(
            "mode",
            "可选：append（默认，不存在就新建、存在就追加在末尾）/ replace（覆盖正文）/ createOnly（只新建）",
            required = false,
        ),
    )

    override val capability: CapabilityId? = null

    override suspend fun execute(args: ActionArgs): String {
        val title = args.require("title").trim()
        if (title.isEmpty()) throw IllegalArgumentException("笔记标题不能为空")
        val body = args.require("body")
        val mode = (args.get("mode") ?: "append").lowercase()
        if (mode != "append" && mode != "replace" && mode != "createonly") {
            return "不认识的写作方式：" + mode + "；只支持 append / replace / createOnly。"
        }

        val store = try {
            runtime.knowledgeStore
        } catch (_: UninitializedPropertyAccessException) {
            return "知识库还没准备好，暂时写不了笔记。"
        }
        val existing = store.list().firstOrNull { it.title.trim() == title }
        val now = runtime.clock.nowMs()

        if (existing == null) {
            val created = store.create(title)
            return if (store.save(created.copy(body = body, updatedAt = now))) {
                "新建了笔记「" + title + "」（" + created.id + "）"
            } else {
                "新建了笔记「" + title + "」，但正文没能写进去。"
            }
        }

        val note = store.read(existing.id)
            ?: return "笔记「" + title + "」读不出来，没能写进去。"
        return when (mode) {
            "createonly" ->
                "笔记「" + title + "」已存在（" + note.id + "），按 createOnly 没有改动。"
            "replace" -> if (store.save(note.copy(body = body, updatedAt = now))) {
                "已覆盖笔记「" + title + "」的正文（" + note.id + "）"
            } else {
                "覆盖笔记「" + title + "」失败，正文没改动。"
            }
            else -> {
                val merged = if (note.body.isBlank()) body else note.body.trimEnd() + "\n\n" + body
                if (store.save(note.copy(body = merged, updatedAt = now))) {
                    "已在笔记「" + title + "」末尾追加正文（" + note.id + "）"
                } else {
                    "追加正文到笔记「" + title + "」失败，内容没改动。"
                }
            }
        }
    }
}

/**
 * 发一条系统通知。
 *
 * 通知是**尽力而为**：平台没权限、命令不存在都静默吞掉。所以即使调用失败也回一句
 * 「已发通知（系统可能拦截）」——失败的细节对用户没有意义，通知没出现时应用内还有
 * 其它入口兜底。
 */
internal class NotifyAction(private val runtime: AppRuntime) : KernelAction {

    override val id: String = "notify"
    override val order: Int = 132
    override val label: String = "发一条系统通知"

    override val params: List<ActionParam> = listOf(
        ActionParam("title", "通知标题"),
        ActionParam("body", "可选：通知正文", required = false),
    )

    override val capability: CapabilityId? = null

    override suspend fun execute(args: ActionArgs): String {
        val title = args.require("title")
        val body = args.get("body").orEmpty()
        runCatching { runtime.notifier.notify(title, body) }
        return "已发通知（系统可能拦截）"
    }
}

/**
 * 打开计时器并立刻开始倒计时。
 *
 * 「先写笔记、顺手开 25 分钟计时」这类组合是插件最自然的用法，所以这个动作一步做完：
 * 设好标题/时长/模式就直接跑，不再要求用户去点小窗上的「开始」。
 *
 * 分钟数先按契约收在 1..240，再交给 [AppRuntime.chooseTimerMinutes] —— 它内部会用
 * `clampTimerMinutes` 把实际值钳到 5..180。返回值报**真正生效**的分钟数，避免卡片上
 * 写「1 分钟」而计时器实际跑 5 分钟这种自相矛盾。
 */
internal class StartFocusTimerAction(private val runtime: AppRuntime) : KernelAction {

    override val id: String = "startFocusTimer"
    override val order: Int = 133
    override val label: String = "打开计时器并开始倒计时"

    override val params: List<ActionParam> = listOf(
        ActionParam("minutes", "倒计时分钟数（整数）"),
        ActionParam("label", "可选：这次专注叫什么，默认「专注」", required = false),
    )

    override val capability: CapabilityId? = null

    override suspend fun execute(args: ActionArgs): String {
        val raw = args.require("minutes")
        val requested = raw.toIntOrNull()
            ?: throw IllegalArgumentException("分钟数要填整数：" + raw)
        val minutes = requested.coerceIn(1, 240)
        val label = args.get("label") ?: "专注"

        runtime.prepareTimer(label, minutes)
        runtime.startTimer()
        return "计时器已开始：" + runtime.timerMinutes + " 分钟（" + label + "）"
    }
}
