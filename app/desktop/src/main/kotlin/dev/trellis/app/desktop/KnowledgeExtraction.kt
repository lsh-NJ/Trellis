package dev.trellis.app.desktop

import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 抽取出来的一个候选概念。 */
internal data class ConceptCandidate(
    val name: String,
    val definition: String,
)

/** 一个槽位（`标签：。。。`）的补全建议。 */
internal data class FillSuggestion(
    val label: String,
    val text: String,
)

/**
 * 一次整理的完整结果：概念 + 槽位补全。
 *
 * 两者是「同一次模型调用」返回的，所以保存后的 2.5s 防抖只花一次网络往返；
 * 概念写 frontmatter，补全写正文（包成 `{{…}}`，预览渲染成绿色大引号）。
 */
internal data class ExtractionResult(
    val candidates: List<ConceptCandidate> = emptyList(),
    val fills: List<FillSuggestion> = emptyList(),
)

/** 一次抽取最多写回几个概念：防止模型把整篇笔记拆成几十个词。 */
internal const val MAX_EXTRACTED_CONCEPTS: Int = 8

/** 正文送进模型的上限：再长就不是「这篇笔记在讲什么」而是「整本书」。 */
private const val MAX_EXTRACT_BODY_CHARS: Int = 6000

/**
 * 让模型从笔记里抽概念、顺便补用户留的空位。
 *
 * 只抽概念名 + 一句话定义 + 槽位补全，不碰原文其它部分（§4.4「只建议不改原文」）。
 * 保存本身永远不依赖这次调用：失败就当没抽到，笔记照样在磁盘上。
 */
internal fun buildKnowledgeExtractionMessages(
    title: String,
    body: String,
    existing: List<String>,
    slots: List<String>,
): List<LlmMessage> {
    val system = "你是个人知识库的整理助手。从用户的 Markdown 笔记里找出值得长期回查的概念" +
        "（名词性知识单元：技术名词、方法、模型、定理、算法、工具），每个给一句话定义；" +
        "笔记里整行是「标签：$AI_SLOT_MARKER」的行是用户留的空位，要各补一句话。" +
        "只输出 JSON 对象，不输出解释。"
    val user = buildString {
        append("笔记标题：").append(title.ifBlank { "（无标题）" }).append("\n\n")
        append("笔记正文：\n").append(body.take(MAX_EXTRACT_BODY_CHARS)).append("\n\n")
        if (existing.isNotEmpty()) {
            append("已经标记过的概念（不要重复）：").append(existing.joinToString("、")).append("\n\n")
        }
        if (slots.isNotEmpty()) {
            append("用户留的空位（整行是「标签：").append(AI_SLOT_MARKER).append("」）：")
                .append(slots.joinToString("、")).append("\n\n")
        }
        append("要求：\n")
        append("1. 只抽真正的概念，不抽「笔记」「总结」「学习」这类泛词，不抽人名和组织名。\n")
        append("2. 最多 ").append(MAX_EXTRACTED_CONCEPTS).append(" 个，宁少勿多。\n")
        append("3. definition 不超过 40 个字，一句能独立看懂的解释。\n")
        append("4. fills 只补上面列出的空位：label 必须和空位标签逐字一致，text 是一句话（不超过 ")
            .append(MAX_AI_FILL_CHARS).append(" 字）；没有空位就输出空数组。\n")
        append("5. 输出严格 JSON 对象，格式：\n")
        append("```json\n")
        append("{\"concepts\":[{\"name\":\"概念名\",\"definition\":\"一句话定义\"}],")
        append("\"fills\":[{\"label\":\"空位标签\",\"text\":\"补全的一句话\"}]}\n")
        append("```")
    }
    return listOf(
        LlmMessage(LlmRole.System, system),
        LlmMessage(LlmRole.User, user),
    )
}

/** 兼容旧调用：只要概念、没有槽位。 */
internal fun buildConceptExtractionMessages(
    title: String,
    body: String,
    existing: List<String>,
): List<LlmMessage> = buildKnowledgeExtractionMessages(title, body, existing, emptyList())

/**
 * 解析模型输出。
 *
 * 新格式是 `{"concepts":[...],"fills":[...]}`；模型有时还是只给旧版数组，
 * 或带 ```json 围栏/前后废话，所以这里先找对象、再退回数组，任何一段坏了都当空。
 */
internal fun parseExtractionResult(raw: String): ExtractionResult {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start >= 0 && end > start) {
        val parsed = runCatching {
            Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        }.getOrNull()
        // 只认带 concepts/fills 字段的对象；模型偶尔还是只给旧版数组，那种要退回下面的数组解析。
        if (parsed != null && (parsed.containsKey("concepts") || parsed.containsKey("fills"))) {
            return ExtractionResult(
                candidates = parseCandidatesFrom(parsed["concepts"]),
                fills = parseFillsFrom(parsed["fills"]),
            )
        }
    }
    return ExtractionResult(candidates = parseConceptCandidates(raw))
}

/**
 * 解析模型输出里的概念数组（旧格式，保留给容错路径和测试）。
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
        parseCandidatesFrom(Json.parseToJsonElement(payload))
    }.getOrElse { emptyList() }
}

private fun parseCandidatesFrom(element: JsonElement?): List<ConceptCandidate> {
    val array = runCatching { element?.jsonArray }.getOrNull() ?: return emptyList()
    return runCatching {
        array.mapNotNull { item ->
            val obj = item.jsonObject
            val name = normalizeConceptName((obj["name"] as? JsonPrimitive)?.content.orEmpty())
            val definition = (obj["definition"] as? JsonPrimitive)?.content.orEmpty().trim().take(120)
            if (name.isEmpty()) null else ConceptCandidate(name, definition)
        }
            .distinctBy { conceptKey(it.name) }
            .take(MAX_EXTRACTED_CONCEPTS)
    }.getOrElse { emptyList() }
}

private fun parseFillsFrom(element: JsonElement?): List<FillSuggestion> {
    val array = runCatching { element?.jsonArray }.getOrNull() ?: return emptyList()
    return runCatching {
        array.mapNotNull { item ->
            val obj = item.jsonObject
            val label = normalizeConceptName((obj["label"] as? JsonPrimitive)?.content.orEmpty())
            val text = normalizeAiFillText((obj["text"] as? JsonPrimitive)?.content.orEmpty())
            if (label.isEmpty() || text.isEmpty()) null else FillSuggestion(label, text)
        }
            .distinctBy { conceptKey(it.label) }
            .take(MAX_AI_FILL_SLOTS)
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

/** 槽位补全也能当概念定义用：只填空定义，不覆盖用户改过的（见 [ConceptMetaStore.fillMissing]）。 */
internal fun fillDefinitions(fills: List<FillSuggestion>): Map<String, String> =
    fills
        .filter { it.text.isNotBlank() }
        .associate { normalizeConceptName(it.label) to it.text.trim() }
