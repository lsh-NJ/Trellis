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
 * 知识库的宿主动作。
 *
 * 为什么单独做成一个内置插件：脚本/DSL 插件没有代码，只能「点名一个宿主已经实现好的
 * 原子动作」。缺了「打开/新建笔记」这个动作，像「一键创建读书笔记」这样的卡片就
 * 永远只能显示一句话——这是 ai.reading-log 失败案例里最核心的能力缺口。
 *
 * 它不碰任何界面渲染，只把用户的点击翻译成「知识库里的那篇笔记，打开它」；
 * 真正的跳转与选中由 [AppRuntime.openKnowledgeReveal] 交给知识库页消费。
 */
internal class KnowledgeNotePlugin(private val runtime: AppRuntime) : Plugin {

    override val manifest: PluginManifest = PluginManifest(
        id = PluginId("core.knowledge"),
        version = SemVer.parse("1.0.0"),
        name = "知识库动作",
        sdkRange = VersionRange.parse(">=1.0.0 <2.0.0"),
        loader = "builtin",
        builtin = true,
        description = "让插件卡片能一键打开或新建知识库笔记并跳过去",
        contributes = mapOf(
            KernelPoints.Action.id to listOf(ContributionDecl("openKnowledgeNote")),
        ),
    )

    override suspend fun init(ctx: PluginContext) {
        ctx.extensions.register(KernelPoints.Action, OpenKnowledgeNoteAction(runtime))
    }
}

/**
 * 打开或新建一篇知识库笔记。
 *
 * 同名（按 `title.trim()` 精确匹配）笔记已存在时只打开、不改写；不存在时新建，
 * 并可选把 `body` 写成正文——「一键创建读书笔记」就是靠这两步拼出来的。
 */
internal class OpenKnowledgeNoteAction(
    private val runtime: AppRuntime,
) : KernelAction {

    override val id: String = "openKnowledgeNote"
    override val order: Int = 120
    override val label: String = "打开或新建知识库笔记"

    override val params: List<ActionParam> = listOf(
        ActionParam("title", "笔记标题；已存在同名笔记时直接打开它"),
        ActionParam(
            "body",
            "可选：新建时的正文（已存在的笔记不会被覆盖）",
            required = false,
        ),
    )

    /**
     * 不需要额外能力。
     *
     * 这个动作由用户亲手点自己卡片上的按钮触发，和「新建任务」同级：只会打开知识库里
     * 一篇用户自己的笔记，不读写外部数据、不越权。挂 capability 的代价是每点一次都弹
     * 一次审批，对「一键打开」这种高频低风险操作是体验灾难。
     */
    override val capability: CapabilityId? = null

    override suspend fun execute(args: ActionArgs): String {
        val title = args.require("title").trim()
        if (title.isEmpty()) throw IllegalArgumentException("笔记标题不能为空")

        // knowledgeStore 在 AppRuntime.create 里初始化，但动作可能被更早/更古怪的路径触发，
        // 未初始化时给一句人话，而不是把 UninitializedPropertyAccessException 丢给用户。
        val store = try {
            runtime.knowledgeStore
        } catch (_: UninitializedPropertyAccessException) {
            return "知识库还没准备好，暂时打不开笔记。"
        }

        val existing = store.list().firstOrNull { it.title.trim() == title }
        if (existing != null) {
            runtime.openKnowledgeReveal(existing.id, -1, -1)
            return "已打开笔记「" + title + "」"
        }

        val created = store.create(title)
        val body = args.get("body")
        if (body != null && !store.save(created.copy(body = body))) {
            return "新建了笔记「" + title + "」，但正文没能写进去。"
        }
        runtime.openKnowledgeReveal(created.id, -1, -1)
        return "新建并打开了笔记「" + title + "」"
    }
}
