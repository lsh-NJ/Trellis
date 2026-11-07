package dev.trellis.ai.manager

import dev.trellis.ai.CapabilityCatalog
import dev.trellis.ai.tool.AgentTool

/**
 * 一轮对话的装配：**一份提示词 + 一套工具**。
 *
 * 2026-11-07 起不再做意图分类（见 `docs/agent-harness.md` 的 P1 落地）：
 * 模型每轮都拿到全量工具，由它自己判断这一句是要聊天、要动手、还是先问一句。
 * 删掉关键词分类器的直接原因是真实事故 —— 「我想做一个 RAG 项目」踩中 `createWords`
 * 里的「做一个」，被判成「造插件」，于是模型只拿到插件工具，怎么想都走不出去
 * （只能硬造壳插件，或 cannotDo 收场）。
 *
 * 现在固定的只有一件事：工具清单按名字排序 —— 提示词里的工具目录逐字节稳定，
 * 前缀缓存才不会被装配顺序的抖动顶掉。
 */
class ManagerRouter(
    val prompt: ManagerPrompt,
    val tools: List<AgentTool>,
) {

    /** 按能力清单和 guidance 现拼提示词（生产装配点用）。 */
    constructor(
        catalog: CapabilityCatalog,
        allTools: List<AgentTool>,
        guidance: String = "",
    ) : this(
        prompt = ManagerPrompt(ManagerPrompt.buildBase(catalog, allTools, guidance)),
        tools = sortedToolset(allTools),
    )
}

/**
 * 工具目录顺序：按工具名排。
 *
 * 为什么排序：前缀缓存要求同一份静态前缀逐字节一致。工具列表是启动时装配的，
 * 顺序其实也稳定；但排序是零成本的保险 —— 将来任何一处装配顺序变了，
 * 也不会悄悄让缓存整段失效。
 */
internal fun sortedToolset(all: List<AgentTool>): List<AgentTool> = all.sortedBy { it.name }
