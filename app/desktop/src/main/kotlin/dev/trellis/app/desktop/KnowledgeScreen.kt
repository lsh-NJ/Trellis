package dev.trellis.app.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 「定位」请求：等目标笔记真正载入后再选中命中处。 */
private data class PendingReveal(val noteId: String, val start: Int, val end: Int)

/**
 * 知识库页：左栏「笔记 / 概念 / 搜索结果」，右栏「笔记编辑器或概念详情」。
 *
 * 三块东西的关系（讨论稿 `docs/knowledge-base-draft.md` §2–§4）：
 *
 * - 笔记：`<home>/knowledge/notes/<id>.md`，frontmatter 里记 `concepts:`；
 * - 概念：索引每次从笔记现算，点亮 = 至少一篇笔记的 frontmatter 提到它；
 *    「熄灭」就是从所有笔记里删掉这条引用（本轮从当前笔记删）；
 * - 抽取：焦点离开正文时（不是每次保存）才让模型补一轮概念与空位
 *    （只建议、默认点亮、可删），失败不影响保存；没配模型就跳过，用户也可以手动添加。
 */
@Composable
internal fun KnowledgeScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    val store = runtime.knowledgeStore
    val metaStore = remember(runtime) { ConceptMetaStore(runtime.home) }
    // 图建议（G1）与网络视图共用同一个缓存文件；整理完一篇顺手写进去，图那边下次打开就能看到「建议」。
    val graphStore = remember(runtime) { KnowledgeGraphStore(store.home, runtime.clock) }
    val clock = runtime.clock
    // 页面根保持一个兜底焦点，键盘事件才有接收者；编辑器组成后再把焦点交给它。
    val pageFocus = remember { FocusRequester() }

    var notes by remember { mutableStateOf(store.list()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf<KnowledgeNote?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    var savedAtMs by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<NoteSummary?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // 三个视图页签：笔记 / 概念 / 网络。放页内对象，切到别的导航页回来还停在原 tab。
    var tab by remember { mutableStateOf(KnowledgeGraphViewState.tab) }
    val showConcepts = tab == KnowledgeTab.Concepts
    var selectedConcept by remember { mutableStateOf<String?>(null) }
    var conceptDefinition by remember(selectedConcept) {
        mutableStateOf(selectedConcept?.let { metaStore.definition(it) }.orEmpty())
    }
    var definitionStatus by remember(selectedConcept) { mutableStateOf("") }
    var revealRange by remember { mutableStateOf<TextRange?>(null) }
    var revealToken by remember { mutableStateOf(0) }
    var shortcutsOpen by remember { mutableStateOf(false) }
    // 布局：左右两栏都能收起（宽度不记忆，每次进页面先都展开；目录宽度例外，见 MarkdownSidebarState）。
    var leftCollapsed by remember { mutableStateOf(false) }
    var collapsedOutline by remember { mutableStateOf(emptySet<Int>()) }
    var pendingReveal by remember { mutableStateOf<PendingReveal?>(null) }
    var extracting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var aiJob by remember { mutableStateOf<Job?>(null) }
    // 每篇笔记「上次交给 AI 时的正文基准」：比较时忽略 AI 标记/AI 补全/`:::ai` 块
    // （见 bodyForAiComparison），所以删标记、AI 补全本身都不算修过正文。
    val aiBaselines = remember { mutableStateMapOf<String, String>() }

    val concepts = remember(notes, loaded) { store.conceptIndex() }
    val outlineItems = remember(body) { parseOutline(body) }

    /** 目录「全部收起」要收的节点：所有带子标题的标题 offset。 */
    fun parentOffsets(items: List<OutlineItem>): List<Int> = items.flatMap { item ->
        if (item.children.isEmpty()) emptyList() else listOf(item.offset) + parentOffsets(item.children)
    }

    /** 概念库的身份表（规范名 + 别名）+ 索引里已有的概念：正文标注靠它解析成规范名。 */
    fun currentIdentities(): List<ConceptIdentity> {
        val known = LinkedHashMap<String, ConceptIdentity>()
        // 空白变体（「Matryoshka 表示学习 / Matryoshka表示学习」）算同一个身份。
        for (identity in metaStore.identities()) {
            if (isAcceptableConceptName(identity.name)) known.putIfAbsent(conceptIdentityKey(identity.name), identity)
        }
        for (summary in concepts) {
            if (isAcceptableConceptName(summary.name)) known.putIfAbsent(conceptIdentityKey(summary.name), ConceptIdentity(summary.name))
        }
        return known.values.toList()
    }

    // 概念详情 / 搜索要认识别名，所以身份表算在前面（概念库 + 索引现算）。
    val identities = remember(notes, loaded) { currentIdentities() }
    val conceptDetail = remember(selectedConcept, notes, loaded) {
        selectedConcept?.let { store.conceptDetail(it, identities = identities) }
    }
    val searchHits = remember(notes, query, loaded) {
        if (query.isBlank()) null else store.search(query, identities = identities)
    }

    /** 正文里要高亮的名字：只取「本文概念」+ 它们的别名（见 [highlightNamesForNote]）。 */
    fun conceptHighlightNames(): List<String> =
        highlightNamesForNote(loaded?.concepts.orEmpty(), currentIdentities())

    /** 只写文件、不动界面状态；返回是否成功。离开组合时用它兜底。 */
    fun saveCurrent(): Boolean {
        val note = loaded ?: return true
        if (note.title == title && note.body == body) return true
        val updated = synchronizeConceptMembership(
            note.copy(title = title, body = body, updatedAt = clock.nowMs()),
            currentIdentities(),
        )
        return store.save(updated)
    }

    /** 把当前编辑缓冲落盘并刷新状态；没有变化则不碰文件（也不刷新列表顺序）。 */
    fun persistNow() {
        val note = loaded ?: return
        val id = selectedId ?: return
        if (note.title == title && note.body == body) return
        val updated = synchronizeConceptMembership(
            note.copy(title = title, body = body, updatedAt = clock.nowMs()),
            currentIdentities(),
        )
        if (!store.save(updated)) {
            message = "保存失败：写不进 " + store.notesDir.absolutePath
            return
        }
        loaded = updated
        savedAtMs = updated.updatedAt
        notes = store.list()
        runtime.knowledgeNoteId = id
    }

    /** 切换视图页签：离开笔记时先落盘（网络视图要从文件现算）。 */
    fun switchTab(next: KnowledgeTab) {
        if (tab == next) return
        if (tab == KnowledgeTab.Notes) persistNow()
        tab = next
        KnowledgeGraphViewState.tab = next
        message = null
    }

    /**
     * 导出当前笔记为独立 Markdown 文件（去掉 frontmatter 与 AI 标记）。
     *
     * 用编辑缓冲而不是文件里的旧内容：自动保存有 600ms 延迟，导出时看到什么就导出什么。
     */
    fun exportCurrentNote() {
        val base = loaded ?: return
        val snapshot = base.copy(title = title, body = body)
        val target = try {
            chooseMarkdownExportFile(markdownExportFileName(snapshot.title))
        } catch (error: Throwable) {
            message = "导出失败：打不开保存面板（" + (error.message ?: "未知错误") + "）"
            return
        } ?: return
        try {
            writeMarkdownExport(snapshot, target)
            message = "已导出 Markdown：" + target.name
        } catch (error: Throwable) {
            message = "导出失败：" + (error.message ?: "写不进 " + target.absolutePath)
        }
    }

    /**
     * 抽取/补全结果写回它所属的那篇笔记。
     *
     * 抽取在后台跑（2.5s 防抖 + 一次模型调用），期间用户可能已经切到别的笔记——
     * 所以不能拿「当前 loaded」当目标，否则会把 A 的概念写到 B 上。当前仍停在这篇才同步界面状态。
     */
    fun applyExtracted(
        id: String,
        base: KnowledgeNote,
        nextBody: String = base.body,
    ): Boolean {
        val updated = synchronizeConceptMembership(
            base.copy(body = nextBody, updatedAt = clock.nowMs()),
            currentIdentities(),
        )
        if (!store.save(updated)) {
            message = "保存失败：写不进 " + store.notesDir.absolutePath
            return false
        }
        notes = store.list()
        if (selectedId == id && loaded?.id == id) {
            loaded = updated
            savedAtMs = updated.updatedAt
            // 正文补全只在用户没接着改的时候同步回编辑缓冲，避免盖掉刚打的字。
            if (nextBody != base.body && body == base.body) {
                body = nextBody
                revision++
            }
        }
        if (selectedId == id) runtime.knowledgeNoteId = id
        return true
    }

    /**
     * 把「整理这篇笔记」顺带得到的图建议写进 `graph.cache.json`（G1，设计 §6.1）。
     *
     * 建议不是事实：不建分类、不改归属；用户在网络视图里确认后才写 `graph.user.json`。
     * 写失败也不影响笔记整理结果——图那边最多看不见这条建议。
     */
    fun persistGraphSuggestions(noteId: String, bodyHash: String, suggestions: NoteGraphSuggestions) {
        val cache = graphStore.loadCache()
        val next = cache.noteSuggestions + (noteId to AiNoteSuggestions(
            bodyHash = bodyHash,
            categories = suggestions.categories,
            relations = suggestions.relations,
        ))
        graphStore.saveCache(
            cache.copy(
                noteSuggestions = next,
                promptVersion = GRAPH_PROMPT_VERSION,
                updatedAt = clock.nowMs(),
            ),
        )
    }

    /**
     * 焦点离开正文时整理这一篇：抽概念 + 补空位。
     *
     * 只在「人写的内容真的变了」时才调模型——比较基准见 [bodyForAiComparison]：
     * AI 补全、删/加 `[[概念]]` 标记都不算正文变化，所以不会反复解析。
     */
    fun organizeNote(id: String) {
        val note = store.read(id) ?: return
        if (note.body.isBlank()) return
        val slots = findAiFillSlots(note.body)
        if (note.body.length < 40 && slots.isEmpty()) return
        if (bodyForAiComparison(note.body) == aiBaselines[id]) return
        val client = runtime.llmClientOrNull()
        if (client == null) {
            if (slots.isNotEmpty()) message = "AI 没配置：补全空位需要先在设置里导入模型"
            return
        }
        // 同一篇的整理请求后到先得：新请求取消旧的，避免两轮结果互相盖。
        aiJob?.cancel()
        aiJob = scope.launch {
            extracting = true
            val raw = try {
                client.complete(
                    buildKnowledgeExtractionMessages(
                        note.title,
                        note.body,
                        note.concepts,
                        slots.map { it.label },
                    ),
                )
            } catch (cancelled: CancellationException) {
                extracting = false
                throw cancelled
            } catch (failed: Exception) {
                null
            }
            extracting = false
            if (raw == null) {
                message = if (slots.isEmpty()) {
                    "AI 整理失败，不影响笔记保存；可以稍后重试或在笔记里手动添加概念"
                } else {
                    "AI 补全失败，空位原样保留；可以稍后重试"
                }
                return@launch
            }
            val current = store.read(id) ?: return@launch
            // 调模型期间人又改了：这轮结果作废，等下次焦点离开再整理（不覆盖新内容）。
            if (bodyForAiComparison(current.body) != bodyForAiComparison(note.body)) return@launch
            val result = parseExtractionResult(raw)
            val filled = applyAiFills(current.body, result.fills.associate { it.label to it.text })
            // 抽到的概念在正文第一次出现处包成 [[概念]]：标记才是「本文概念」的唯一来源，
            // frontmatter 的 concepts 只是按标注重算出来的缓存（见 synchronizeConceptMembership）。
            val markedBody = markConceptAnnotations(filled.body, result.candidates)
            aiBaselines[id] = bodyForAiComparison(markedBody)
            val next = synchronizeConceptMembership(
                current.copy(body = markedBody),
                currentIdentities(),
            )
            if (next.concepts == current.concepts && markedBody == current.body) return@launch
            // mention 在正文里找不到的候选已经被丢掉了，别在概念库里留下没有出处的定义。
            val keptCandidates = retainedCandidates(markedBody, result.candidates, currentIdentities())
            metaStore.fillMissing(
                candidateDefinitions(keptCandidates) + fillDefinitions(result.fills),
            )
            candidateAliases(keptCandidates).forEach { (name, aliases) ->
                metaStore.mergeAliases(name, aliases)
            }
            if (!applyExtracted(id, current, markedBody)) return@launch
            // 顺手解析图建议（与概念/补全同一次调用，不额外花钱）；解析失败就当没有建议。
            persistGraphSuggestions(
                noteId = id,
                bodyHash = noteBodyHash(current.copy(body = markedBody)),
                suggestions = parseNoteGraphSuggestions(
                    raw = raw,
                    noteConceptNames = next.concepts,
                    body = markedBody,
                    now = clock.nowMs(),
                ),
            )
            val conceptsAdded = next.concepts.size - current.concepts.size
            message = if (filled.applied.isNotEmpty()) {
                "AI 补全 " + filled.applied.size + " 处（绿引号标记，点 × 可删），整理了 " +
                    conceptsAdded + " 个概念"
            } else {
                "AI 整理了 " + conceptsAdded + " 个概念（点 × 可删除）"
            }
        }
    }

    fun createNote() {
        persistNow()
        loaded?.id?.let { previous -> organizeNote(previous) }
        val note = store.create()
        notes = store.list()
        selectedId = note.id
        message = null
    }

    fun selectNote(id: String) {
        if (id == selectedId) return
        persistNow()
        loaded?.id?.takeIf { it != id }?.let { previous -> organizeNote(previous) }
        selectedId = id
    }

    fun deleteNote(target: NoteSummary) {
        if (!store.delete(target.id)) {
            message = "删除失败：文件删不掉（可能被别的程序占用）"
            return
        }
        notes = store.list()
        message = "已删除「" + displayTitle(target.title) + "」"
        if (target.id == selectedId) {
            loaded = null
            savedAtMs = null
            title = ""
            body = ""
            selectedId = notes.firstOrNull()?.id
        }
    }

    /** 打开概念详情（名字按索引里的最新写法统一，避免大小写/空白变体分成两条）。 */
    fun openConcept(name: String) {
        val display = concepts.firstOrNull { conceptKey(it.name) == conceptKey(name) }?.name
            ?: normalizeConceptName(name)
        switchTab(KnowledgeTab.Concepts)
        selectedConcept = display
        message = null
    }

    /**
     * 人工标注概念：在正文里插 `[[原文=>概念名]]`（原文不在正文里就追加到文末）。
     *
     * 归属不在这里直接写 frontmatter——保存时统一按正文标记重算，
     * 所以人工和 AI 走的是同一条路（见 [synchronizeConceptMembership]）。
     */
    fun addConcept(name: String) {
        val note = loaded ?: return
        if (!isAcceptableConceptName(name)) {
            message = "「" + name + "」太短或太泛，不作为概念（至少 2 个字；英文缩写至少 3 个字母）"
            return
        }
        val canonical = resolveConceptName(name, currentIdentities())
        if (canonical.isEmpty()) return
        val nextBody = addConceptAnnotation(body, canonical, currentIdentities())
        if (nextBody == body) {
            persistNow()
            val pinned = loaded?.concepts?.any { conceptKey(it) == conceptKey(canonical) } == true
            message = if (pinned) {
                "「" + canonical + "」已经在概念里了"
            } else {
                "「" + canonical + "」已在概念库里，但正文里没有这个词；先把它写进正文再标注"
            }
            return
        }
        metaStore.ensureConcepts(listOf(canonical))
        body = nextBody
        revision++
        aiBaselines[note.id] = bodyForAiComparison(nextBody)
        persistNow()
        message = "已标注「" + canonical + "」（正文里插入了 [[…]] 标记）"
    }

    /** 右栏「已删除」里点 ↩：重新在正文里插一处标注（墓碑由同步函数自动清掉）。 */
    fun restoreConcept(name: String) {
        addConcept(name)
        message = "已恢复「" + name + "」的标注"
    }

    /** 右栏 ×：把正文里这个概念的标记降级成纯文本 → 进「已删除」（墓碑，可恢复）。 */
    fun removeConcept(name: String) {
        val note = loaded ?: return
        val key = conceptIdentityKey(name)
        val nextBody = stripConceptAnnotations(body, name, currentIdentities())
        if (nextBody == body) {
            message = "「" + name + "」正文里没有标记，无法删除标注"
            return
        }
        // 这里手动把概念写进墓碑：删掉的若是最后一个标记，正文已经没有 `[[…]]`，
        // 只靠同步函数会被「老笔记不做迁移」的分支当成旧数据保留下来。
        val canonical = resolveConceptName(name, currentIdentities()).ifBlank { name }
        val seeded = note.copy(
            title = title,
            body = nextBody,
            updatedAt = clock.nowMs(),
            concepts = note.concepts.filterNot { conceptIdentityKey(it) == key },
            removedConcepts = (note.removedConcepts + canonical).distinctBy(::conceptIdentityKey),
        )
        if (!store.save(seeded)) {
            message = "保存失败：写不进 " + store.notesDir.absolutePath
            return
        }
        body = nextBody
        revision++
        loaded = seeded
        savedAtMs = seeded.updatedAt
        notes = store.list()
        aiBaselines[note.id] = bodyForAiComparison(nextBody)
        message = "已删除「" + name + "」的标注（原文保留，可在已删除里恢复）"
    }

    /** 概念详情里点「来源」：切回笔记并选中正文命中处。 */
    fun locateMention(mention: ConceptMention) {
        switchTab(KnowledgeTab.Notes)
        if (selectedId != mention.noteId) selectNote(mention.noteId)
        if (mention.start < 0) {
            message = "这篇笔记只在 frontmatter 里标了这个概念，正文没有出现"
            return
        }
        pendingReveal = PendingReveal(mention.noteId, mention.start, mention.end)
    }

    /**
     * 搜索结果里点一篇笔记：切到这篇；正文里命中就选中命中处并滚动过去（标题命中只打开）。
     *
     * 复用概念详情的「定位」管线：设 pendingReveal，等笔记载入后交给编辑器。
     */
    fun openSearchHit(hit: NoteSearchHit) {
        val id = hit.summary.id
        showConcepts = false
        if (selectedId != id) selectNote(id)
        if (hit.start < 0) return
        pendingReveal = PendingReveal(id, hit.start, hit.end)
    }

    // 进页面：接回上次那篇（AppRuntime 记着），没有就选最近更新的。
    // 焦点请求要等布局跑完，否则焦点节点还没挂上（见 MarkdownEditor.requestFocusWhenReady）。
    LaunchedEffect(Unit) {
        pageFocus.requestFocusWhenReady()
        selectedId = runtime.knowledgeNoteId?.takeIf { store.exists(it) } ?: notes.firstOrNull()?.id
    }

    // 切换选中：从文件载入。读不到（被外部删了）就退到下一篇。
    LaunchedEffect(selectedId) {
        val id = selectedId
        if (id == null) {
            loaded = null
            title = ""
            body = ""
            savedAtMs = null
            revision = 0
            return@LaunchedEffect
        }
        val note = store.read(id)
        if (note == null) {
            message = "这篇笔记的文件不见了：" + id
            selectedId = notes.firstOrNull { it.id != id }?.id
            return@LaunchedEffect
        }
        loaded = note
        title = note.title
        body = note.body
        savedAtMs = note.updatedAt
        revision = 0
        runtime.knowledgeNoteId = id
        aiBaselines[id] = bodyForAiComparison(note.body)
    }

    // 定位请求：等目标笔记载入后，把命中范围交给编辑器选中。
    LaunchedEffect(pendingReveal, selectedId, loaded) {
        val pending = pendingReveal ?: return@LaunchedEffect
        val note = loaded ?: return@LaunchedEffect
        if (note.id != pending.noteId || selectedId != pending.noteId) return@LaunchedEffect
        pendingReveal = null
        val start = pending.start.coerceIn(0, note.body.length)
        val end = pending.end.coerceIn(start, note.body.length)
        revealRange = TextRange(start, end)
        revealToken++
    }

    // 自动保存：停手 600ms 落盘。每次编辑 revision++，旧定时器自动作废。
    LaunchedEffect(revision) {
        if (revision == 0 || loaded == null) return@LaunchedEffect
        delay(600)
        persistNow()
    }

    // 切页/关窗口时兜底：正在打的字不能留在内存里。
    DisposableEffect(Unit) {
        onDispose { saveCurrent() }
    }

    // 概念定义的防抖保存：只写 concepts.json，不动笔记文件。
    LaunchedEffect(selectedConcept, conceptDefinition) {
        val name = selectedConcept ?: return@LaunchedEffect
        if (conceptDefinition == metaStore.definition(name).orEmpty()) return@LaunchedEffect
        delay(600)
        val ok = metaStore.saveDefinition(name, conceptDefinition)
        definitionStatus = if (ok) "定义已保存" else "定义写不进 " + metaStore.filePath
    }

    val dirty = loaded != null && (loaded!!.title != title || loaded!!.body != body)
    val status = buildString {
        append(
            when {
                loaded == null -> "还没有选中笔记"
                dirty -> "编辑中…（自动保存）"
                savedAtMs != null -> "已保存 · " + knowledgeTimeLabel(savedAtMs!!, clock.zone)
                else -> ""
            },
        )
        if (extracting) append(" · AI 整理中…")
    }
    val filteredNotes = remember(notes, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            notes
        } else {
            notes.filter { displayTitle(it.title).contains(keyword, ignoreCase = true) }
        }
    }
    val messageIsProblem = message?.let {
        it.startsWith("保存失败") || it.startsWith("删除失败") ||
            it.startsWith("AI 整理失败") || it.startsWith("AI 补全失败") ||
            it.startsWith("AI 没配置") || it.startsWith("导出失败")
    } == true

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(pageFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val command = event.isCtrlPressed || event.isMetaPressed
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else if (command && event.key == Key.Slash) {
                    // 菜单挂在页面上：编辑面没有焦点时，按键也只会走到这里。
                    shortcutsOpen = !shortcutsOpen
                    true
                } else if (event.key == Key.Escape && shortcutsOpen) {
                    shortcutsOpen = false
                    true
                } else if (command) {
                    when (event.key) {
                        Key.S -> {
                            persistNow(); message = "已保存"; true
                        }

                        Key.N -> {
                            createNote(); true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            // 页头固定：只留（目录、导出、菜单）；页面标题由左侧导航承担，这里不再重复。
            // 标题和正文一起滚，所以这些按钮不能跟着正文跑。
            // 页面本身已经贴边（AppScreen 对 flush 页不再套 20dp），页头自己留 20dp。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (tab == KnowledgeTab.Notes) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditorChip(
                            label = "目录",
                            active = MarkdownSidebarState.visible,
                            onClick = { MarkdownSidebarState.visible = !MarkdownSidebarState.visible },
                        )
                        EditorChip(
                            label = "导出",
                            active = false,
                            onClick = { exportCurrentNote() },
                        )
                        EditorChip(
                            label = "菜单",
                            active = shortcutsOpen,
                            onClick = { shortcutsOpen = !shortcutsOpen },
                        )
                    }
                }
            }

            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    message!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (messageIsProblem) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            if (notes.isEmpty() && concepts.isEmpty()) {
                KnowledgeEmptyState(onCreate = { createNote() })
            } else {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (tab == KnowledgeTab.Graph) {
                        // 网络视图自带左栏/画布/右栏，整块替换「笔记 + 概念」那套布局。
                        KnowledgeGraphView(
                            runtime = runtime,
                            notesVersion = notes.hashCode(),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onOpenNote = { noteId, start, end ->
                                persistNow()
                                if (selectedId != noteId) {
                                    loaded?.id?.takeIf { it != noteId }?.let { previous -> organizeNote(previous) }
                                    selectedId = noteId
                                }
                                pendingReveal = PendingReveal(noteId, start, end)
                                switchTab(KnowledgeTab.Notes)
                            },
                        )
                    } else {

                    // 左栏：笔记列表 / 全局概念索引。可以收成一条窄边。
                    // 和对话页共用 ListSidebar：标题/数量/动作/行样式同一套，宽度可拖。
                    if (leftCollapsed) {
                        Column(
                            Modifier.fillMaxHeight().width(36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(2.dp))
                            EditorChip(
                                label = "›",
                                active = false,
                                onClick = { leftCollapsed = false },
                            )
                        }
                    } else {
                        ListSidebar(
                            title = if (showConcepts) "概念" else "笔记",
                            count = if (showConcepts) concepts.size else filteredNotes.size,
                            actionLabel = "新建笔记",
                            onAction = { createNote() },
                            trailing = {
                                EditorChip(
                                    label = "‹",
                                    active = false,
                                    onClick = { leftCollapsed = true },
                                )
                            },
                        ) {
                            ChromeTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = "搜索标题和正文",
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                KnowledgePaneTab(
                                    label = "笔记",
                                    selected = !showConcepts,
                                    onClick = { switchTab(KnowledgeTab.Notes) },
                                )
                                KnowledgePaneTab(
                                    label = "概念",
                                    selected = showConcepts,
                                    onClick = { switchTab(KnowledgeTab.Concepts) },
                                )

                                KnowledgePaneTab(
                                    label = "网络",
                                    selected = tab == KnowledgeTab.Graph,
                                    onClick = { switchTab(KnowledgeTab.Graph) },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            val hits = searchHits
                            if (hits != null) {
                                KnowledgeSearchResults(
                                    hits = hits,
                                    selectedNoteId = selectedId,
                                    selectedConceptName = selectedConcept,
                                    zone = clock.zone,
                                    onOpenNote = { openSearchHit(it) },
                                    onOpenConcept = { openConcept(it) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else if (showConcepts) {
                                ConceptListPanel(
                                    concepts = concepts,
                                    selectedName = selectedConcept,
                                    zone = clock.zone,
                                    emptyHint = "还没有概念。保存笔记后 AI 会自动整理，也可以在笔记里手动添加。",
                                    onSelect = { openConcept(it) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                NoteListPanel(
                                    notes = filteredNotes,
                                    selectedId = selectedId,
                                    zone = clock.zone,
                                    emptyHint = "没有匹配的笔记",
                                    onSelect = { selectNote(it) },
                                    onDelete = { deleteTarget = it },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    ListSidebarSplitter()
                    // 主区：概念详情，或者「标题 + 正文」一整块可滚动内容。
                    if (showConcepts) {
                        ConceptDetailPanel(
                            detail = conceptDetail,
                            definition = conceptDefinition,
                            definitionStatus = definitionStatus,
                            zone = clock.zone,
                            onDefinitionChange = {
                                conceptDefinition = it
                                definitionStatus = ""
                            },
                            onLocate = { locateMention(it) },
                            onOpenRelated = { openConcept(it) },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 18.dp, end = 20.dp),
                        )
                    } else {
                        Column(
                            Modifier.weight(1f).fillMaxHeight().padding(
                                start = 18.dp,
                                // 右栏可见时正文不需要再留右边距（右栏自己贴到窗口右边界）。
                                end = if (MarkdownSidebarState.visible) 0.dp else 4.dp,
                            ),
                        ) {
                            // 标题和正文共用一条滚动轴（需求：标题跟着正文走）。
                            // 文档编辑器自己不再滚动，高度随内容长；滚动交给这里。
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    NoteTitleField(
                                        title = title,
                                        loaded = loaded != null,
                                        onTitleChange = {
                                            title = it
                                            revision++
                                        },
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    MarkdownEditor(
                                        noteId = selectedId.orEmpty(),
                                        body = body,
                                        onBodyChange = {
                                            body = it
                                            revision++
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        reveal = revealRange,
                                        revealToken = revealToken,
                                        conceptNames = conceptHighlightNames(),
                                        onDeleteAiLine = { line ->
                                            val next = removeAiBlockLine(body, line)
                                            if (next != null) {
                                                body = next
                                                revision++
                                                message = "已删掉一处 AI 内容"
                                            }
                                        },
                                        // 焦点离开正文（点标题/列表/别的地方）才算一次「写完了」：
                                        // 先落盘，再决定要不要请 AI 整理（正文真变了才调模型）。
                                        onEditingFinished = {
                                            persistNow()
                                            loaded?.id?.let { id -> organizeNote(id) }
                                        },
                                    )
                                    Spacer(Modifier.height(20.dp))
                                }
                            }
                            // 底部状态条固定，不跟着正文滚走。
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (loaded == null) "还没有选中笔记" else body.length.toString() + " 字符",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (messageIsProblem) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    },
                                )
                            }
                        }
                        // 右栏：本篇概念 + 目录。目录宽度可拖（OutlineSplitter 在面板外留了口子），
                        // 收起/展开走页头「目录」、目录面板自己的「收起」，或这一栏顶部的箭头；
                        // 收起后留一条窄边（和左栏一致），点箭头再展开。
                        Spacer(Modifier.width(if (MarkdownSidebarState.visible) 12.dp else 6.dp))
                        if (MarkdownSidebarState.visible) {
                            Column(Modifier.width(MarkdownSidebarState.width).fillMaxHeight()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(end = 6.dp),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    EditorChip(
                                        label = "›",
                                        active = false,
                                        onClick = { MarkdownSidebarState.visible = false },
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                NoteConceptPanel(
                                    concepts = loaded?.concepts.orEmpty(),
                                    removedConcepts = loaded?.removedConcepts.orEmpty(),
                                    onOpenConcept = { openConcept(it) },
                                    onRemoveConcept = { removeConcept(it) },
                                    onAddConcept = { addConcept(it) },
                                    onRestoreConcept = { restoreConcept(it) },
                                )
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                                MarkdownOutlinePanel(
                                    roots = outlineItems,
                                    collapsed = collapsedOutline,
                                    modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp),
                                    onToggle = { item ->
                                        collapsedOutline = if (item.offset in collapsedOutline) {
                                            collapsedOutline - item.offset
                                        } else {
                                            collapsedOutline + item.offset
                                        }
                                    },
                                    onCollapseAll = {
                                        collapsedOutline = parentOffsets(outlineItems).toSet()
                                    },
                                    onExpandAll = { collapsedOutline = emptySet() },
                                    onJump = { item ->
                                        // 跳转复用「定位」管线：设 pendingReveal，等编辑器选中并滚动过去。
                                        val id = selectedId
                                        if (id != null) {
                                            pendingReveal = PendingReveal(id, item.offset, item.offset)
                                        }
                                    },
                                    onHide = { MarkdownSidebarState.visible = false },
                                )
                            }
                        } else {
                            // 收起态：留一条窄边，点箭头展开（和左栏收起的样式一致）。
                            Column(
                                Modifier.fillMaxHeight().width(36.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Spacer(Modifier.height(2.dp))
                                EditorChip(
                                    label = "‹",
                                    active = false,
                                    onClick = { MarkdownSidebarState.visible = true },
                                )
                            }
                        }
                    }
                                    }
}
            }
        }
        if (shortcutsOpen) {
            MarkdownMenuOverlay(onClose = { shortcutsOpen = false })
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这篇笔记？") },
            text = { Text("「" + displayTitle(target.title) + "」会从磁盘删除，暂不进回收站。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteNote(target)
                    deleteTarget = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
        )
    }
}

/** 标题行：编辑区顶部的大标题；没有笔记时禁用并给占位。 */
@Composable
private fun NoteTitleField(
    title: String,
    loaded: Boolean,
    onTitleChange: (String) -> Unit,
) {
    BasicTextField(
        value = title,
        onValueChange = onTitleChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        enabled = loaded,
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.merge(
            TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            ),
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box {
                if (title.isEmpty()) {
                    Text(
                        displayTitle(""),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun KnowledgeEmptyState(onCreate: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("还没有笔记", style = MaterialTheme.typography.titleMedium)
            Text(
                "先写一篇：标题 + Markdown 正文，存成本地 .md 文件；保存后 AI 会帮着整理概念。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            ChromeButton(label = "新建第一篇笔记", onClick = onCreate, filled = true)
        }
    }
}
