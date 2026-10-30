package dev.trellis.app.desktop

import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 抽取出来的一个候选概念。 */
internal data class ConceptCandidate(
    val name: String,
    val definition: String,
)

/** 一次抽取最多写回几个概念：防止模型把整篇笔记拆成几十个词。 */
internal const val MAX_EXTRACTED_CONCEPTS: Int = 8

/** 正文送进模型的上限：再长就不是「这篇笔记在讲什么」而是「整本书」。 */
private const val MAX_EXTRACT_BODY_CHARS: Int = 6000

/**
 * 让模型从笔记里抽概念。
 *
 * 只抽概念名 + 一句话定义，不碰原文（§4.4「只建议不改原文」）。
 * 保存本身永远不依赖这次调用：失败就当没抽到，笔记照样在磁盘上。
 */
internal fun buildConceptExtractionMessages(
    title: String,
    body: String,
    existing: List<String>,
): List<LlmMessage> {
    val system = "你是个人知识库的整理助手。从用户的 Markdown 笔记里找出值得长期回查的概念" +
        "（名词性知识单元：技术名词、方法、模型、定理、算法、工具），每个给一句话定义。" +
        "只输出 JSON 数组，不输出解释。"
    val user = buildString {
        append("笔记标题：").append(title.ifBlank { "（无标题）" }).append("\n\n")
        append("笔记正文：\n").append(body.take(MAX_EXTRACT_BODY_CHARS)).append("\n\n")
        if (existing.isNotEmpty()) {
            append("已经标记过的概念（不要重复）：").append(existing.joinToString("、")).append("\n\n")
        }
        append("要求：\n")
        append("1. 只抽真正的概念，不抽「笔记」「总结」「学习」这类泛词，不抽人名和组织名。\n")
        append("2. 最多 ").append(MAX_EXTRACTED_CONCEPTS).append(" 个，宁少勿多。\n")
        append("3. definition 不超过 40 个字，一句能独立看懂的解释。\n")
        append("4. 输出严格 JSON 数组，格式：\n")
        append("```json\n")
        append("[{\"name\":\"概念名\",\"definition\":\"一句话定义\"}]\n")
        append("```")
    }
    return listOf(
        LlmMessage(LlmRole.System, system),
        LlmMessage(LlmRole.User, user),
    )
}

/**
 * 解析模型输出里的概念数组。
 *
 * 模型经常带 ```json 围栏或前后废话，所以先定位第一个 `[` 到最后一个 `]` 再解析；
 * 任何一段坏了都返回空列表——抽取是增强功能，不能因为解析失败让页面报错。
 */
internal fun parseConceptCandidates(raw: String): List<ConceptCandidate> {
    val start = raw.indexOf('[')
    val end = raw.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    val payload = raw.substring(start, end + 1)
    return runCatching {
        Json.parseToJsonElement(payload).jsonArray
            .mapNotNull { element ->
                val obj = element.jsonObject
                val name = normalizeConceptName((obj["name"] as? JsonPrimitive)?.content.orEmpty())
                val definition = (obj["definition"] as? JsonPrimitive)?.content.orEmpty().trim().take(120)
                if (name.isEmpty()) null else ConceptCandidate(name, definition)
            }
            .distinctBy { conceptKey(it.name) }
            .take(MAX_EXTRACTED_CONCEPTS)
    }.getOrElse { emptyList() }
}

/** 合并已有概念与候选：保留原顺序，按 [conceptKey] 去重。 */
internal fun mergeConceptNames(existing: List<String>, candidates: List<ConceptCandidate>): List<String> =
    (existing + candidates.map { it.name })
        .map(::normalizeConceptName)
        .filter { it.isNotBlank() }
        .distinctBy(::conceptKey)

/** 候选里带定义的部分；写定义时只填缺失的，不覆盖用户改过的。 */
internal fun candidateDefinitions(candidates: List<ConceptCandidate>): Map<String, String> =
    candidates
        .filter { it.definition.isNotBlank() }
        .associate { normalizeConceptName(it.name) to it.definition.trim() }
