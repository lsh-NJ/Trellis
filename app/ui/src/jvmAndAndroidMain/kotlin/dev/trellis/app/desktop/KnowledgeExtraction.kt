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

/**
 * 一个槽位（行尾 `。。。`）的补全建议。
 *
 * [id] 是新格式的槽位标识（`slot@行号`）；旧会话/旧模型只回 `label`，这时 [id] 留空，
 * 回填时按标签兜底（见 [alignAiFills]）。
 */
internal data class FillSuggestion(
    val label: String,
    val text: String,
    val id: String = "",
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

/** 单次送进模型的正文上限：再长就按段落切成多段分别整理（见 [extractionBodyChunks]）。 */
internal const val MAX_EXTRACT_BODY_CHARS: Int = 6000

/** 一次整理最多分这么多段（≈ [MAX_EXTRACT_BODY_CHARS] × 8 字）；再长只整理前面这几段。 */
internal const val MAX_EXTRACT_CHUNKS: Int = 8

/** 一/二级标题行：`# 标题`、`## 标题`（`###` 不会命中一级/二级模式）。 */
private val firstLevelHeadingLine = Regex("^#\\s+\\S")
private val secondLevelHeadingLine = Regex("^##\\s+\\S")

/**
 * 把长正文按「标题结构」切成不超过 [MAX_EXTRACT_BODY_CHARS] 的段。
 *
 * 优先按**一级标题**分段；某个一级段太大就降级按**二级标题**拆；仍然太大才按行断开
 * （单行超长硬切）。相邻的小段会拼在一起，避免每节都单独打一次请求。
 * 最多返回 [MAX_EXTRACT_CHUNKS] 段，更长的笔记尾部不再送模型（避免一次整理打十几个请求）。
 * 短正文原样返回一段。
 */
internal fun extractionBodyChunks(body: String): List<String> {
    if (body.length <= MAX_EXTRACT_BODY_CHARS) return listOf(body)
    val pieces = ArrayList<String>()
    for (topSection in splitAtHeadingLevel(body, firstLevelHeadingLine)) {
        if (topSection.length <= MAX_EXTRACT_BODY_CHARS) {
            pieces += topSection
            continue
        }
        for (secondSection in splitAtHeadingLevel(topSection, secondLevelHeadingLine)) {
            if (secondSection.length <= MAX_EXTRACT_BODY_CHARS) {
                pieces += secondSection
            } else {
                pieces += splitIntoLines(secondSection)
            }
        }
    }
    return packPieces(pieces).take(MAX_EXTRACT_CHUNKS)
}

/** 按标题行把正文切成若干节；每节从标题行开始，第一节是第一个标题之前的内容（可能为空）。 */
private fun splitAtHeadingLevel(body: String, heading: Regex): List<String> {
    val sections = ArrayList<String>()
    val current = StringBuilder()
    val lines = body.split('\n')
    val headingLines = lines.indices.filter { heading.containsMatchIn(lines[it]) }
    if (headingLines.isEmpty()) return listOf(body)
    for ((index, line) in lines.withIndex()) {
        val piece = if (index == lines.lastIndex) line else line + "\n"
        if (index in headingLines && current.isNotEmpty()) {
            sections += current.toString()
            current.clear()
        }
        current.append(piece)
    }
    if (current.isNotEmpty()) sections += current.toString()
    return sections.filter { it.isNotEmpty() }
}

/** 单节还是太大：按行断开（优先行尾，单行超长硬切）。 */
private fun splitIntoLines(text: String): List<String> {
    if (text.length <= MAX_EXTRACT_BODY_CHARS) return listOf(text)
    val pieces = ArrayList<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotEmpty()) {
            pieces += current.toString()
            current.clear()
        }
    }
    fun hardSplit(rest: String) {
        var remaining = rest
        while (remaining.length > MAX_EXTRACT_BODY_CHARS) {
            flush()
            pieces += remaining.substring(0, MAX_EXTRACT_BODY_CHARS)
            remaining = remaining.substring(MAX_EXTRACT_BODY_CHARS)
        }
        current.append(remaining)
    }
    val lines = text.split('\n')
    for ((index, line) in lines.withIndex()) {
        val piece = if (index == lines.lastIndex) line else line + "\n"
        if (current.length + piece.length > MAX_EXTRACT_BODY_CHARS && current.isNotEmpty()) flush()
        if (piece.length > MAX_EXTRACT_BODY_CHARS) hardSplit(piece) else current.append(piece)
    }
    flush()
    return pieces
}

/** 把相邻的小段拼成不超过上限的块：标题边界优先，但不为每节单独打一次请求。 */
private fun packPieces(pieces: List<String>): List<String> {
    val packed = ArrayList<String>()
    val current = StringBuilder()
    for (piece in pieces) {
        if (current.isNotEmpty() && current.length + piece.length > MAX_EXTRACT_BODY_CHARS) {
            packed += current.toString()
            current.clear()
        }
        current.append(piece)
    }
    if (current.isNotEmpty()) packed += current.toString()
    return packed
}

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
    slots: List<AiFillSlot>,
    headings: List<String> = emptyList(),
    chunkIndex: Int = 0,
    chunkCount: Int = 1,
): List<LlmMessage> {
    val system = "你是个人知识库的整理助手。任务只有两件：①从笔记里挑出**作者确实讲过**、" +
        "值得长期回查的概念，每个给出规范名、正文里的原词（mention）、可选别名和一句话定义；" +
        "②把用户留的空位补上一句话。" +
        "笔记里某一行以三个全角句号 $AI_SLOT_MARKER 结尾，就是用户留的空位：" +
        "三个句号前面可以有任意内容（标签、列表、命令、概念标注都行），也可以只有这三个句号。" +
        "系统只信任正文原文：它会把 mention 在正文里第一次出现的位置包成 " +
        "`[[mention => 规范名]]`（mention 与规范名相同时包成 `[[规范名]]`），" +
        "所以 mention 必须是正文里的原词，改写过的词会被丢掉。" +
        "不要抽标题行/章节名、单字母或两三个字母的缩写、纯符号、泛词——这些会在正文里" +
        "「绿色满天飞」，用户要的是真正被解释过的概念。" +
        "另外顺手给「分类路径」和「概念间关系」的建议（同样是建议：系统会让用户逐条确认，" +
        "编造的名字/路径会直接被丢弃）。只输出 JSON 对象，不输出解释。"
    val user = buildString {
        append("笔记标题：").append(title.ifBlank { "（无标题）" }).append("\n\n")
        val chunkHint = if (chunkCount > 1) {
            "（这是整篇正文的第 " + (chunkIndex + 1) + "/" + chunkCount +
                " 段，其余段落这里省略；概念与空位都只看这一段）\n\n"
        } else {
            ""
        }
        append("笔记正文").append(chunkHint).append("：\n").append(body.take(MAX_EXTRACT_BODY_CHARS)).append("\n\n")
        if (headings.isNotEmpty()) {
            append("本笔记的标题结构（按出现顺序，仅供建图参考）：\n")
            headings.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        if (existing.isNotEmpty()) {
            append("已经标记过的概念（不要重复）：").append(existing.joinToString("、")).append("\n\n")
        }
        if (slots.isNotEmpty()) {
            append("用户留的空位（行尾 $AI_SLOT_MARKER 是空位；id 是系统给的行标识）：\n")
            slots.forEach { slot ->
                append("- id=").append(slot.id)
                append("，标签=").append(slot.label.ifEmpty { "（无）" })
                append("，原行：").append(slot.lineText.ifBlank { AI_SLOT_MARKER }).append('\n')
            }
            append('\n')
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
        append("7. fills 只补上面列出的空位：每条返回 {\"id\":\"slot@行号\",\"text\":\"补全内容\"}，")
            .append("id 必须和列出的逐字一致（不要自己编 id）；text 是一句话（不超过 ")
            .append(MAX_AI_FILL_CHARS).append(" 字）；没有空位就输出空数组。\n")
        append("8. 顺手给两类「知识网络建议」（拿不准就输出空数组，不要硬凑）：\n")
        append("   categories：给本篇概念建议一个所属分类路径。路径最多 2 层、每层不超过 12 个字，")
            .append("类似「检索/召回」；优先用常见的大类名，没有合适的分类就别给。最多 12 条。")
            .append("如果笔记有标题结构，优先用大标题当父分类名：可以概括成不超过 12 字的短名，")
            .append("不必与标题一字不差；同一大标题下的概念尽量归到同一个父分类（这是「按笔记逻辑建图」）。\n")
        append("   relations：本篇概念之间的关系。from/to 必须是上面 concepts 或已标记概念里的名字；")
            .append("kind 只能是 PREREQUISITE（from 是 to 的前置）、APPLIES（from 用于 to）、RELATED（相关）；")
            .append("PREREQUISITE/APPLIES 必须给 evidence，且 evidence 必须是正文里逐字出现的一句原话，")
            .append("找不到原话就别写这条。最多 6 条。\n")
        append("9. 输出严格 JSON 对象，格式：\n")
        append("```json\n")
        append("{\"concepts\":[{\"name\":\"规范名\",\"mention\":\"正文里的原词\",")
        append("\"aliases\":[\"别名\"],\"definition\":\"一句话定义\"}],")
        append("\"fills\":[{\"id\":\"slot@12\",\"text\":\"补全的一句话\"}],")
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
 * 编辑器里的「AI 补全」：只补光标所在的那一行，只给这一段的上下文。
 *
 * 和全量整理的区别：不带概念抽取/建图任务、不返回 JSON，模型输出直接当补全文本用，
 * 所以上下文越聚焦越好——整篇正文只在用户没写标签时才用（见 `KnowledgeScreen.fillSingleSlot`）。
 */
internal fun buildSingleSlotFillMessages(
    title: String,
    headingChain: List<String>,
    context: String,
    lineText: String,
    label: String,
): List<LlmMessage> {
    val system = "你是个人知识库的写作助手。用户在笔记的某一行末尾留了 $AI_SLOT_MARKER 空位，" +
        "你只补这一处：输出一句简体中文（不超过 $MAX_AI_FILL_CHARS 字），直接给句子本身。" +
        "不要解释、不要加引号、不要 Markdown 强调、不要 JSON、不要重复原行。"
    val user = buildString {
        append("笔记标题：").append(title.ifBlank { "（无标题）" }).append("\n\n")
        if (headingChain.isNotEmpty()) {
            append("所在章节：").append(headingChain.joinToString(" > ")).append("\n\n")
        }
        append("上下文：\n").append(context.take(MAX_EXTRACT_BODY_CHARS)).append("\n\n")
        append("要补全的这一行（行尾 ").append(AI_SLOT_MARKER).append(" 是空位）：\n")
        append(lineText).append("\n\n")
        if (label.isNotEmpty()) append("空位标签：").append(label).append("\n\n")
        append("只输出补全的那一句话。")
    }
    return listOf(
        LlmMessage(LlmRole.System, system),
        LlmMessage(LlmRole.User, user),
    )
}

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

/**
 * 解析模型返回的 fills。
 *
 * 新格式是 `{"id":"slot@12","text":"…"}`；旧格式只有 `label`，这里一并收下
 * （[FillSuggestion.id] 留空，回填时按标签兜底）。两者都没有或 text 为空就丢弃。
 */
private fun parseFillsFrom(element: JsonElement?): List<FillSuggestion> {
    val array = runCatching { element?.jsonArray }.getOrNull() ?: return emptyList()
    return runCatching {
        array.mapNotNull { item ->
            val obj = item.jsonObject
            val id = (obj["id"] as? JsonPrimitive)?.content.orEmpty().trim()
            val label = (obj["label"] as? JsonPrimitive)?.content.orEmpty()
                .replace(Regex("\\s+"), " ").trim().take(AI_SLOT_LABEL_DISPLAY_MAX)
            val text = normalizeAiFillText((obj["text"] as? JsonPrimitive)?.content.orEmpty())
            when {
                text.isEmpty() -> null
                id.isEmpty() && label.isEmpty() -> null
                else -> FillSuggestion(label = label, text = text, id = id)
            }
        }
            .distinctBy { if (it.id.isNotEmpty()) "id:" + it.id else "label:" + conceptKey(it.label) }
            .take(MAX_AI_FILL_SLOTS)
    }.getOrElse { emptyList() }
}

/**
 * 把模型返回的 fills 对到正文槽位上：新格式按 id，旧格式（只有标签）按标签兜底。
 *
 * 返回 id → 补全文本，交给 [applyAiFills] 落行。对不上的建议直接丢掉（不猜行号）。
 */
internal fun alignAiFills(slots: List<AiFillSlot>, fills: List<FillSuggestion>): Map<String, String> {
    if (slots.isEmpty() || fills.isEmpty()) return emptyMap()
    val byId = slots.associateBy { it.id }
    val byLabel = slots.filter { it.label.isNotEmpty() }.groupBy { conceptKey(it.label) }
    val aligned = LinkedHashMap<String, String>()
    for (fill in fills) {
        val slot = byId[fill.id]
            ?: fill.label.takeIf { it.isNotEmpty() }?.let { byLabel[conceptKey(it)]?.firstOrNull() }
            ?: continue
        aligned.putIfAbsent(slot.id, fill.text)
    }
    return aligned
}

/**
 * 跨段统一分析：各段分别抽到的概念可能有重复/叫法不同/定义不一致，把它们放在一起再请模型整理一遍。
 *
 * 只整理「已有候选」，不允许新增：模型漏掉的候选会原样保留（见 [applyConsolidatedCandidates]），
 * mention 也永远用原来的正文原词，避免模型改写导致打不上标记。
 */
internal fun buildConceptConsolidationMessages(
    title: String,
    candidates: List<ConceptCandidate>,
): List<LlmMessage> {
    val system = "你是个人知识库的整理助手。下面这些概念是从同一篇长笔记的不同段落分别抽出来的，" +
        "可能有重复（同一个概念换了个说法）、定义不一致、或者别名分散在多条里。" +
        "请把它们合并成一份：同一个概念只留一条，选最合适的规范名；别名取并集；" +
        "定义用最完整、能独立看懂的一句（不超过 40 个字）；不要新增原文里没有的概念。" +
        "name 只能从下面给出的名字/别名里选（或它们的合理写法），不要编造。只输出 JSON 对象。"
    val user = buildString {
        append("笔记标题：").append(title.ifBlank { "（无标题）" }).append("\n\n")
        append("候选概念（共 ").append(candidates.size).append(" 条）：\n")
        candidates.forEach { candidate ->
            append("- ").append(candidate.name)
            if (candidate.mention.isNotBlank() && conceptKey(candidate.mention) != conceptKey(candidate.name)) {
                append("（正文原词：").append(candidate.mention).append("）")
            }
            if (candidate.aliases.isNotEmpty()) append("（别名：").append(candidate.aliases.joinToString("、")).append("）")
            if (candidate.definition.isNotBlank()) append("：").append(candidate.definition)
            append('\n')
        }
        append("\n输出严格 JSON 对象，格式：")
        append("{\"concepts\":[{\"name\":\"规范名\",\"aliases\":[\"别名\"],\"definition\":\"一句话定义\"}]}")
    }
    return listOf(
        LlmMessage(LlmRole.System, system),
        LlmMessage(LlmRole.User, user),
    )
}

/**
 * 把统一分析的结果套回原始候选。
 *
 * - 只接受「能在原始候选里找到」的条目（规范名或别名匹配身份键），模型新增的概念一律丢掉；
 * - mention 永远沿用原始候选的正文原词（模型改写过的会标不上）；
 * - 模型漏掉的原始候选原样保留（宁多勿丢，准入规则在解析阶段已经挡过垃圾）。
 */
internal fun applyConsolidatedCandidates(
    original: List<ConceptCandidate>,
    consolidated: List<ConceptCandidate>,
): List<ConceptCandidate> {
    if (original.isEmpty() || consolidated.isEmpty()) return original
    val byKey = LinkedHashMap<String, ConceptCandidate>()
    for (candidate in original) byKey[conceptIdentityKey(candidate.name)] = candidate
    val covered = HashSet<String>()
    val merged = ArrayList<ConceptCandidate>()
    for (item in consolidated) {
        if (!isAcceptableConceptName(item.name)) continue
        val matches = ArrayList<ConceptCandidate>()
        byKey[conceptIdentityKey(item.name)]?.let { matches += it }
        for (alias in item.aliases) {
            if (alias.isBlank()) continue
            byKey[conceptIdentityKey(alias)]?.let { candidate ->
                if (matches.none { it === candidate }) matches += candidate
            }
        }
        val uncovered = matches.filter { conceptIdentityKey(it.name) !in covered }
        if (uncovered.isEmpty()) continue
        uncovered.forEach { covered += conceptIdentityKey(it.name) }
        val mention = uncovered.firstNotNullOfOrNull { it.mention.ifBlank { null } }
            ?: uncovered.first().mention
        val aliases = (uncovered.flatMap { it.aliases } + item.aliases)
            .map(::normalizeConceptName)
            .filter { it.isNotBlank() && isAcceptableConceptName(it) }
            .filter { conceptIdentityKey(it) != conceptIdentityKey(item.name) }
            .distinctBy(::conceptIdentityKey)
        merged += ConceptCandidate(
            name = normalizeConceptName(item.name),
            definition = item.definition.ifBlank { uncovered.first().definition },
            mention = mention,
            aliases = aliases,
        )
    }
    for (candidate in original) {
        if (conceptIdentityKey(candidate.name) in covered) continue
        merged += candidate
    }
    return merged
}

/**
 * 长笔记分段整理后的合并：概念按身份键去重（定义取信息更全的那份、别名取并集、mention 取首个非空），
 * 补全按槽位 id 去重（旧格式没有 id 时按标签），保留先出现的那份。
 */
internal fun mergeExtractionResults(results: List<ExtractionResult>): ExtractionResult {
    if (results.isEmpty()) return ExtractionResult()
    val candidates = LinkedHashMap<String, ConceptCandidate>()
    for (result in results) {
        for (candidate in result.candidates) {
            val key = conceptIdentityKey(candidate.name)
            val existing = candidates[key]
            if (existing == null) {
                candidates[key] = candidate
            } else {
                candidates[key] = existing.copy(
                    definition = if (candidate.definition.length > existing.definition.length) {
                        candidate.definition
                    } else {
                        existing.definition
                    },
                    mention = existing.mention.ifBlank { candidate.mention },
                    aliases = (existing.aliases + candidate.aliases)
                        .distinctBy(::conceptIdentityKey),
                )
            }
        }
    }
    val fills = LinkedHashMap<String, FillSuggestion>()
    for (result in results) {
        for (fill in result.fills) {
            val key = if (fill.id.isNotEmpty()) "id:" + fill.id else "label:" + conceptKey(fill.label)
            fills.putIfAbsent(key, fill)
        }
    }
    return ExtractionResult(candidates = candidates.values.toList(), fills = fills.values.toList())
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
        .filter { it.text.isNotBlank() && it.label.isNotBlank() }
        .associate { normalizeConceptName(it.label) to it.text.trim() }
