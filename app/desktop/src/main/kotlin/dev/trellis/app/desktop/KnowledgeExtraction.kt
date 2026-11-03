package dev.trellis.app.desktop

import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 抽取出来的一个候选概念。
 *
 * [name] 是规范名（概念库里用的那个）；[mention] 是正文里的原词原句——系统只会把这个
 * 原词包成 `[[mention => name]]`，JSON 里没给 mention 时退回 name。[aliases] 是同一个
 * 概念在别处的叫法（只参与匹配与高亮，不从 `=>` 左边自动学习）。
 */
internal data class ConceptCandidate(
    val name: String,
    val definition: String,
    val mention: String = "",
    val aliases: List<String> = emptyList(),
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
    val system = "你是个人知识库的整理助手。任务只有两件：①从笔记里挑出**作者确实讲过**、" +
        "值得长期回查的概念，每个给出规范名、正文里的原词（mention）、可选别名和一句话定义；" +
        "②把用户留的空位补上一句话。" +
        "笔记里整行是「标签：$AI_SLOT_MARKER」的行是用户留的空位。" +
        "系统只信任正文原文：它会把 mention 在正文里第一次出现的位置包成 " +
        "`[[mention => 规范名]]`（mention 与规范名相同时包成 `[[规范名]]`），" +
        "所以 mention 必须是正文里的原词，改写过的词会被丢掉。" +
        "不要抽标题行/章节名、单字母或两三个字母的缩写、纯符号、泛词——这些会在正文里" +
        "「绿色满天飞」，用户要的是真正被解释过的概念。" +
        "另外顺手给「分类路径」和「概念间关系」的建议（同样是建议：系统会让用户逐条确认，" +
        "编造的名字/路径会直接被丢弃）。只输出 JSON 对象，不输出解释。"
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
        append("1. 概念必须有「正文依据」，满足任一条才抽：\n")
        append("   a) 正文里对它做过解释、下定义、说明作用或步骤；\n")
        append("   b) 它和用户留的空位直接相关，补全那个空位后能看懂。\n")
        append("   只是被提到一次的名词、查不到解释的词、泛词（「笔记」「总结」「学习」）、")
            .append("人名、组织名、产品名都不要。\n")
        append("2. 准入门槛（不满足就不要抽）：概念名至少 2 个字，英文缩写至少 3 个字母；")
            .append("纯符号、单个字母、两个字母的缩写（M、ef、en 这种）、标题行/章节名、通用类别词")
            .append("（集合、段、索引、总结、学习）都不算概念。\n")
        append("3. mention 必须**逐字出现在正文里**（原词原句，大小写、空格、标点与原文一致）；")
            .append("系统只会把 mention 第一次出现的地方包成 `[[mention => name]]`，")
            .append("找不到的 mention 会被丢掉。正文原词和 name 相同时 mention 可以省略。\n")
        append("4. name 是规范名：想总结得更标准可以写在 name 里，但必须通过 mention 映射回正文原词；")
            .append("别人对同一个概念的不同叫法写进 aliases（不要自己发明没人用过的词，也不要把")
            .append("「集合」「段」这种通用词当别名）。\n")
        append("5. 数量不设上限：正文里确实讲过的概念都可以列，但每个都必须满足上面的依据；")
            .append("拿不准的就别抽。\n")
        append("6. definition 不超过 40 个字，一句能独立看懂的解释（不是复述名词）。\n")
        append("7. fills 只补上面列出的空位：label 必须和空位标签逐字一致，text 是一句话（不超过 ")
            .append(MAX_AI_FILL_CHARS).append(" 字）；没有空位就输出空数组。\n")
        append("8. 顺手给两类「知识网络建议」（拿不准就输出空数组，不要硬凑）：\n")
        append("   categories：给本篇概念建议一个所属分类路径。路径最多 2 层、每层不超过 12 个字，")
            .append("类似「检索/召回」；优先用常见的大类名，没有合适的分类就别给。最多 12 条。\n")
        append("   relations：本篇概念之间的关系。from/to 必须是上面 concepts 或已标记概念里的名字；")
            .append("kind 只能是 PREREQUISITE（from 是 to 的前置）、APPLIES（from 用于 to）、RELATED（相关）；")
            .append("PREREQUISITE/APPLIES 必须给 evidence，且 evidence 必须是正文里逐字出现的一句原话，")
            .append("找不到原话就别写这条。最多 6 条。\n")
        append("9. 输出严格 JSON 对象，格式：\n")
        append("```json\n")
        append("{\"concepts\":[{\"name\":\"规范名\",\"mention\":\"正文里的原词\",")
        append("\"aliases\":[\"别名\"],\"definition\":\"一句话定义\"}],")
        append("\"fills\":[{\"label\":\"空位标签\",\"text\":\"补全的一句话\"}],")
        append("\"categories\":[{\"concept\":\"概念名\",\"categoryPath\":\"大类/小类\",\"reason\":\"一句话\"}],")
        append("\"relations\":[{\"from\":\"概念A\",\"to\":\"概念B\",\"kind\":\"PREREQUISITE\",")
        append("\"confidence\":4,\"reason\":\"一句话\",\"evidence\":\"正文原话\"}]}\n")
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
            val mention = (obj["mention"] as? JsonPrimitive)?.content.orEmpty()
                .replace("\n", " ").trim().take(MAX_CONCEPT_NAME_LENGTH)
            val aliases = (obj["aliases"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
                ?.map(::normalizeConceptName)
                ?.filter { isAcceptableConceptName(it) && conceptIdentityKey(it) != conceptIdentityKey(name) }
                ?.distinctBy(::conceptIdentityKey)
                .orEmpty()
            // 模型偶尔还是抽 M / ef / 纯符号：这类名字进库就会让正文满天飞，解析阶段就挡掉。
            if (!isAcceptableConceptName(name)) null else ConceptCandidate(name, definition, mention, aliases)
        }
            .distinctBy { conceptIdentityKey(it.name) }
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
        .filter(::isAcceptableConceptName)
        .distinctBy(::conceptIdentityKey)

/** 候选里带定义的部分；写定义时只填缺失的，不覆盖用户改过的。 */
internal fun candidateDefinitions(candidates: List<ConceptCandidate>): Map<String, String> =
    candidates
        .filter { it.definition.isNotBlank() }
        .associate { normalizeConceptName(it.name) to it.definition.trim() }

/** 候选里的别名；写概念库时只合并、不覆盖用户已有的别名。 */
internal fun candidateAliases(candidates: List<ConceptCandidate>): Map<String, List<String>> =
    candidates.filter { it.aliases.isNotEmpty() }
        .associate { normalizeConceptName(it.name) to it.aliases }

/** 槽位补全也能当概念定义用：只填空定义，不覆盖用户改过的（见 [ConceptMetaStore.fillMissing]）。 */
internal fun fillDefinitions(fills: List<FillSuggestion>): Map<String, String> =
    fills
        .filter { it.text.isNotBlank() }
        .associate { normalizeConceptName(it.label) to it.text.trim() }
