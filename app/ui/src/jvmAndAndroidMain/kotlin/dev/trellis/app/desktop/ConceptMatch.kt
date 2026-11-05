package dev.trellis.app.desktop

/**
 * 概念在正文里的命中匹配。
 *
 * 概念索引（次数/定位）和预览里的绿色高亮共用这一套规则，避免「列表说 2 次、
 * 预览里高亮 3 处」这种对不上的情况。规则只有一条但很关键：
 *
 * - 概念名以拉丁字母/数字打头或收尾时要求词边界，否则 `RAG` 会命中 `GraphRAG`；
 * - 中文概念没有空格分词，按子串算（`向量` 命中 `向量检索`），和用户预期一致。
 */
internal data class ConceptHit(val first: Int, val last: Int)

/** 正文里 [name] 出现的次数（大小写不敏感，遵守 [conceptMatchesAt] 的边界规则）。 */
internal fun conceptOccurrences(text: String, name: String): Int {
    if (name.isEmpty()) return 0
    var index = conceptNextHit(text, name, 0)
    var count = 0
    while (index >= 0) {
        count++
        index = conceptNextHit(text, name, index + name.length)
    }
    return count
}

/** 正文里第一次命中 [name] 的偏移；没有命中返回 -1。 */
internal fun conceptFirstHit(text: String, name: String): Int = conceptNextHit(text, name, 0)

/** 一篇文章里所有概念名的命中（同一位置取最长匹配，避免 `RAG` 抢 `GraphRAG` 的位置）。 */
internal fun conceptHits(text: String, names: Collection<String>): List<ConceptHit> {
    val candidates = names
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .sortedByDescending { it.length }
    if (candidates.isEmpty() || text.isEmpty()) return emptyList()

    val hits = mutableListOf<ConceptHit>()
    var index = 0
    while (index < text.length) {
        var matched = false
        for (name in candidates) {
            if (index + name.length > text.length) continue
            if (!text.regionMatches(index, name, 0, name.length, ignoreCase = true)) continue
            if (!conceptMatchesAt(text, index, name)) continue
            hits += ConceptHit(index, index + name.length - 1)
            index += name.length
            matched = true
            break
        }
        if (!matched) index++
    }
    return hits
}

/** 找下一处满足边界规则的命中。 */
internal fun conceptNextHit(text: String, name: String, from: Int): Int {
    if (name.isEmpty() || from > text.length) return -1
    var index = text.indexOf(name, from, ignoreCase = true)
    while (index >= 0 && !conceptMatchesAt(text, index, name)) {
        index = text.indexOf(name, index + 1, ignoreCase = true)
    }
    return index
}

/**
 * 这个位置算不算一次独立命中。
 *
 * 只对「ASCII 字母/数字打头或收尾」的概念名要求边界；中文名是子串命中。
 */
internal fun conceptMatchesAt(text: String, index: Int, name: String): Boolean {
    if (name.isEmpty()) return false
    val before = if (index > 0) text[index - 1] else ' '
    val afterIndex = index + name.length
    val after = if (afterIndex < text.length) text[afterIndex] else ' '
    val leftOk = !name[0].isAsciiWordChar() || !before.isAsciiWordChar()
    val rightOk = !name[name.length - 1].isAsciiWordChar() || !after.isAsciiWordChar()
    return leftOk && rightOk
}

private fun Char.isAsciiWordChar(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
