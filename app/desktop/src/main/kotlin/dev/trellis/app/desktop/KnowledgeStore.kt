package dev.trellis.app.desktop

import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * 知识库笔记的文件读写。
 *
 * 只碰文件，不碰数据库：笔记是用户资产，放成普通 Markdown 文件才能备份、能导出、
 * 能被别的工具打开；以后要加 `knowledge.db` 索引，那是从这些文件重建出来的投影。
 *
 * 时间走注入时钟（`TrellisClock`），和计划/活动一样，测试里可以固定时间。
 * 失败不抛给界面：读坏一个文件就当它不存在，绝不把整个列表带崩。
 */
internal class KnowledgeStore(private val home: File, private val clock: TrellisClock) {

    /** `<home>/knowledge/notes/`；目录在构造时就建好。 */
    val notesDir: File = File(File(home, "knowledge"), "notes")

    init {
        notesDir.mkdirs()
    }

    /** 列表按「最近改过」排序；标题为空时显示占位名。 */
    fun list(): List<NoteSummary> {
        val summaries = noteFiles().mapNotNull { file ->
            val note = readFile(file) ?: return@mapNotNull null
            NoteSummary(note.id, note.title, note.updatedAt, note.body.length)
        }
        return summaries.sortedWith(compareByDescending<NoteSummary> { it.updatedAt }.thenBy { displayTitle(it.title) })
    }

    fun exists(id: String): Boolean = fileOf(id)?.isFile == true

    fun read(id: String): KnowledgeNote? = fileOf(id)?.takeIf { it.isFile }?.let { readFile(it) }

    /** 新建一篇空笔记；id 由时间 + 随机后缀组成，冲突就重试。 */
    fun create(title: String = ""): KnowledgeNote {
        val now = clock.nowMs()
        var id = newId(now)
        var guard = 0
        while (fileOf(id)?.exists() == true && guard++ < 100) id = newId(now)
        val note = KnowledgeNote(id = id, title = title, body = "", createdAt = now, updatedAt = now)
        writeFile(note)
        return note
    }

    /** 覆盖保存；返回是否真的写成功（写失败页面要能说人话，而不是假装保存了）。 */
    fun save(note: KnowledgeNote): Boolean = writeFile(note)

    fun delete(id: String): Boolean = fileOf(id)?.delete() == true

    /**
     * 全部笔记（含正文）。概念索引、概念详情、搜索都从文件现算——
     * 没有单独的索引库要同步，所以「删库重建」永远一致；代价是每次全量扫一遍目录，
     * 个人知识库几百篇的量级，扫目录比维护一致性便宜。
     */
    fun allNotes(): List<KnowledgeNote> = noteFiles().mapNotNull { readFile(it) }

    /** 概念索引：按概念名聚合，按最近更新排序。 */
    fun conceptIndex(notes: List<KnowledgeNote> = allNotes()): List<ConceptSummary> {
        class Acc(var name: String, var updatedAt: Long, var mentions: Int) {
            val noteIds = LinkedHashSet<String>()
        }

        val acc = LinkedHashMap<String, Acc>()
        for (note in notes) {
            for (raw in note.concepts) {
                val name = normalizeConceptName(raw)
                if (name.isEmpty()) continue
                val key = conceptKey(name)
                val entry = acc.getOrPut(key) { Acc(name, note.updatedAt, 0) }
                if (note.updatedAt >= entry.updatedAt) {
                    entry.name = name
                    entry.updatedAt = note.updatedAt
                }
                entry.noteIds.add(note.id)
                entry.mentions += conceptOccurrences(note.body, name)
            }
        }
        return acc.values
            .map { entry ->
                ConceptSummary(
                    name = entry.name,
                    noteCount = entry.noteIds.size,
                    mentionCount = entry.mentions.coerceAtLeast(entry.noteIds.size),
                    updatedAt = entry.updatedAt,
                )
            }
            .sortedWith(compareByDescending<ConceptSummary> { it.updatedAt }.thenBy { it.name })
    }

    /** 概念详情：引用按笔记最近更新排；相关概念按「同笔记共现」现算。 */
    fun conceptDetail(name: String, notes: List<KnowledgeNote> = allNotes()): ConceptDetail? {
        val key = conceptKey(name)
        if (key.isEmpty()) return null
        val members = notes.filter { note -> note.concepts.any { conceptKey(it) == key } }
        if (members.isEmpty()) return null
        val display = members
            .maxByOrNull { it.updatedAt }
            ?.concepts
            ?.firstOrNull { conceptKey(it) == key }
            ?: normalizeConceptName(name)

        var totalMentions = 0
        val mentions = members.sortedByDescending { it.updatedAt }.map { note ->
            val count = conceptOccurrences(note.body, display)
            totalMentions += count
            val start = if (count > 0) conceptFirstHit(note.body, display) else -1
            val end = if (start >= 0) start + display.length else -1
            ConceptMention(
                noteId = note.id,
                noteTitle = note.title,
                noteUpdatedAt = note.updatedAt,
                snippet = if (start >= 0) {
                    snippetAround(note.body, start, end)
                } else {
                    "（只在 frontmatter 里标注，正文未出现）"
                },
                start = start,
                end = end,
            )
        }
        return ConceptDetail(
            summary = ConceptSummary(
                name = display,
                noteCount = members.size,
                mentionCount = totalMentions.coerceAtLeast(members.size),
                updatedAt = members.maxOf { it.updatedAt },
            ),
            mentions = mentions,
            related = relatedConcepts(members, key, notes),
        )
    }

    /** 搜索：概念名 + 笔记标题/正文；空关键词返回全部笔记。 */
    fun search(query: String, notes: List<KnowledgeNote> = allNotes()): SearchHits {
        val keyword = query.trim()
        if (keyword.isEmpty()) return SearchHits(emptyList(), list())
        val concepts = conceptIndex(notes).filter { it.name.contains(keyword, ignoreCase = true) }
        val matched = notes.filter {
            it.title.contains(keyword, ignoreCase = true) || it.body.contains(keyword, ignoreCase = true)
        }
        val summaries = matched
            .map { NoteSummary(it.id, it.title, it.updatedAt, it.body.length) }
            .sortedWith(compareByDescending<NoteSummary> { it.updatedAt }.thenBy { displayTitle(it.title) })
        return SearchHits(concepts, summaries)
    }

    /** 出现过的其他概念（同笔记共现），按共现次数排；索引本身来自笔记，所以现算。 */
    private fun relatedConcepts(
        members: List<KnowledgeNote>,
        selfKey: String,
        notes: List<KnowledgeNote>,
    ): List<ConceptSummary> {
        val counts = LinkedHashMap<String, Int>()
        for (note in members) {
            for (raw in note.concepts) {
                val key = conceptKey(raw)
                if (key.isEmpty() || key == selfKey) continue
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        if (counts.isEmpty()) return emptyList()
        val index = conceptIndex(notes).associateBy { conceptKey(it.name) }
        return counts.entries
            .sortedByDescending { it.value }
            .mapNotNull { (key, _) -> index[key] }
            .take(8)
    }

    private fun noteFiles(): List<File> =
        notesDir.listFiles { file -> file.isFile && file.name.endsWith(noteFileExtension) }?.toList() ?: emptyList()

    private fun fileOf(id: String): File? {
        if (!noteIdPattern.matches(id)) return null
        return File(notesDir, id + noteFileExtension)
    }

    private fun readFile(file: File): KnowledgeNote? = runCatching {
        if (!file.isFile) return@runCatching null
        val raw = file.readText(Charsets.UTF_8)
        val fallback = file.lastModified().takeIf { it > 0L } ?: clock.nowMs()
        parseNoteMarkdown(file.nameWithoutExtension, raw, clock.zone, fallback)
    }.getOrNull()

    private fun writeFile(note: KnowledgeNote): Boolean {
        val target = fileOf(note.id) ?: return false
        notesDir.mkdirs()
        val text = formatNoteMarkdown(note, clock.zone)
        return runCatching {
            val temp = File(notesDir, target.name + ".tmp")
            temp.writeText(text, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                // Windows 上改名到已存在的文件会失败；退化成直接写，宁可少一次原子性也不能丢内容。
                temp.delete()
                target.writeText(text, Charsets.UTF_8)
            }
            true
        }.getOrDefault(false)
    }

    private fun newId(nowMs: Long): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .format(Instant.ofEpochMilli(nowMs).atZone(clock.zone))
        val suffix = Random.nextInt(0x10000).toString(16).padStart(4, '0')
        return "n_" + stamp + "_" + suffix
    }
}

/** 命中处所在的那一行（太长就截命中附近），给概念详情里的「来源片段」用。 */
private fun snippetAround(body: String, start: Int, end: Int): String {
    val from = body.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val to = body.indexOf('\n', end).let { if (it < 0) body.length else it }
    val line = body.substring(from, to).trim()
    if (line.length <= 120) return line
    val local = (start - from).coerceIn(0, (line.length - 1).coerceAtLeast(0))
    val head = (local - 40).coerceAtLeast(0)
    val tail = (local + 80).coerceAtMost(line.length)
    return (if (head > 0) "…" else "") + line.substring(head, tail).trim() + (if (tail < line.length) "…" else "")
}
