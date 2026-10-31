package dev.trellis.app.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.delay

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
 * - 抽取：保存后停手 2.5s，让模型补一轮概念（只建议、默认点亮、可删），
 *    失败不影响保存；没配模型就跳过，用户也可以手动添加。
 */
@Composable
internal fun KnowledgeScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    val store = runtime.knowledgeStore
    val metaStore = remember(runtime) { ConceptMetaStore(runtime.home) }
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
    var preview by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<NoteSummary?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showConcepts by remember { mutableStateOf(false) }
    var selectedConcept by remember { mutableStateOf<String?>(null) }
    var conceptDefinition by remember(selectedConcept) {
        mutableStateOf(selectedConcept?.let { metaStore.definition(it) }.orEmpty())
    }
    var definitionStatus by remember(selectedConcept) { mutableStateOf("") }
    var revealRange by remember { mutableStateOf<TextRange?>(null) }
    var revealToken by remember { mutableStateOf(0) }
    var pendingReveal by remember { mutableStateOf<PendingReveal?>(null) }
    var extracting by remember { mutableStateOf(false) }
    // 记「哪个正文已经整理过」：避免同一次编辑里反复调模型（页面重进时不需要持久化）。
    val extractedBodies = remember { mutableStateMapOf<String, String>() }

    val concepts = remember(notes, loaded) { store.conceptIndex() }
    val conceptDetail = remember(selectedConcept, notes, loaded) {
        selectedConcept?.let { store.conceptDetail(it) }
    }
    val searchHits = remember(notes, query, loaded) {
        if (query.isBlank()) null else store.search(query)
    }

    /** 只写文件、不动界面状态；返回是否成功。离开组合时用它兜底。 */
    fun saveCurrent(): Boolean {
        val note = loaded ?: return true
        if (note.title == title && note.body == body) return true
        val updated = note.copy(title = title, body = body, updatedAt = clock.nowMs())
        return store.save(updated)
    }

    /** 把当前编辑缓冲落盘并刷新状态；没有变化则不碰文件（也不刷新列表顺序）。 */
    fun persistNow() {
        val note = loaded ?: return
        val id = selectedId ?: return
        if (note.title == title && note.body == body) return
        val updated = note.copy(title = title, body = body, updatedAt = clock.nowMs())
        if (!store.save(updated)) {
            message = "保存失败：写不进 " + store.notesDir.absolutePath
            return
        }
        loaded = updated
        savedAtMs = updated.updatedAt
        notes = store.list()
        runtime.knowledgeNoteId = id
    }

    /** 概念改动写回；正文没变，所以不动 revision，也不触发重新载入。 */
    fun applyConcepts(next: List<String>) {
        val note = loaded ?: return
        val updated = note.copy(concepts = next, updatedAt = clock.nowMs())
        if (!store.save(updated)) {
            message = "保存失败：写不进 " + store.notesDir.absolutePath
            return
        }
        loaded = updated
        notes = store.list()
        savedAtMs = updated.updatedAt
        runtime.knowledgeNoteId = updated.id
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
        nextConcepts: List<String>,
        nextBody: String = base.body,
    ): Boolean {
        val updated = base.copy(
            concepts = nextConcepts,
            body = nextBody,
            updatedAt = clock.nowMs(),
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

    fun createNote() {
        persistNow()
        val note = store.create()
        notes = store.list()
        selectedId = note.id
        preview = false
        message = null
    }

    fun selectNote(id: String) {
        if (id == selectedId) return
        persistNow()
        preview = false
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
            preview = false
            selectedId = notes.firstOrNull()?.id
        }
    }

    /** 打开概念详情（名字按索引里的最新写法统一，避免大小写/空白变体分成两条）。 */
    fun openConcept(name: String) {
        val display = concepts.firstOrNull { conceptKey(it.name) == conceptKey(name) }?.name
            ?: normalizeConceptName(name)
        showConcepts = true
        selectedConcept = display
        message = null
    }

    fun addConcept(name: String) {
        val note = loaded ?: return
        val merged = mergeConceptNames(note.concepts, listOf(ConceptCandidate(normalizeConceptName(name), "")))
        if (merged.size == note.concepts.size) {
            message = "「" + name + "」已经在概念里了"
            return
        }
        applyConcepts(merged)
        extractedBodies[note.id] = body
        message = "已点亮「" + normalizeConceptName(name) + "」"
    }

    fun removeConcept(name: String) {
        val note = loaded ?: return
        val next = note.concepts.filterNot { conceptKey(it) == conceptKey(name) }
        if (next.size == note.concepts.size) return
        applyConcepts(next)
        extractedBodies[note.id] = body
        message = "已熄灭「" + name + "」（这篇笔记不再引用它）"
    }

    /** 概念详情里点「来源」：切回笔记并选中正文命中处。 */
    fun locateMention(mention: ConceptMention) {
        preview = false
        showConcepts = false
        if (selectedId != mention.noteId) selectNote(mention.noteId)
        if (mention.start < 0) {
            message = "这篇笔记只在 frontmatter 里标了这个概念，正文没有出现"
            return
        }
        pendingReveal = PendingReveal(mention.noteId, mention.start, mention.end)
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

    // 概念自动整理 + 空位补全：保存后停手 2.5s 再调模型。
    // 抽到的概念默认点亮；补到的内容包成 `{{…}}`，预览里画成绿色大引号（点 × 可删）。
    LaunchedEffect(savedAtMs) {
        savedAtMs ?: return@LaunchedEffect
        val id = selectedId ?: return@LaunchedEffect
        val note = loaded ?: return@LaunchedEffect
        if (note.id != id) return@LaunchedEffect
        if (note.body.isBlank()) return@LaunchedEffect
        val slots = findAiFillSlots(note.body)
        if (note.body.length < 40 && slots.isEmpty()) return@LaunchedEffect
        if (extractedBodies[id] == note.body) return@LaunchedEffect
        val client = runtime.llmClientOrNull()
        if (client == null) {
            if (slots.isNotEmpty()) message = "AI 没配置：补全空位需要先在设置里导入模型"
            return@LaunchedEffect
        }
        delay(2500)
        // 防抖期间又编辑的话 savedAtMs 会变、本 effect 已被取消；这里看到的是最新正文。
        val current = store.read(id) ?: return@LaunchedEffect
        if (current.body != note.body) return@LaunchedEffect
        extracting = true
        val raw = try {
            client.complete(
                buildKnowledgeExtractionMessages(
                    current.title,
                    current.body,
                    current.concepts,
                    slots.map { it.label },
                ),
            )
        } catch (cancelled: CancellationException) {
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
            return@LaunchedEffect
        }
        val result = parseExtractionResult(raw)
        val filled = applyAiFills(current.body, result.fills.associate { it.label to it.text })
        val merged = mergeConceptNames(
            current.concepts + result.fills.map { it.label },
            result.candidates,
        )
        extractedBodies[id] = filled.body
        if (merged.size == current.concepts.size && filled.body == current.body) return@LaunchedEffect
        metaStore.fillMissing(
            candidateDefinitions(result.candidates) + fillDefinitions(result.fills),
        )
        if (!applyExtracted(id, current, merged, filled.body)) return@LaunchedEffect
        val conceptsAdded = merged.size - current.concepts.size
        message = if (filled.applied.isNotEmpty()) {
            "AI 补全 " + filled.applied.size + " 处（绿引号标记，点 × 可删），整理了 " + conceptsAdded + " 个概念"
        } else {
            "AI 整理了 " + conceptsAdded + " 个概念（点 × 可删除）"
        }
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
            it.startsWith("AI 没配置")
    } == true

    Column(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(pageFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val command = event.isCtrlPressed || event.isMetaPressed
                if (event.type == KeyEventType.KeyDown && command) {
                    when (event.key) {
                        Key.S -> {
                            persistNow(); message = "已保存"; true
                        }

                        Key.N -> {
                            createNote(); true
                        }

                        Key.E -> {
                            preview = !preview; true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("知识库", style = MaterialTheme.typography.titleLarge)
                Text(
                    "写下来就点亮：共 " + notes.size + " 篇笔记 · " + concepts.size +
                        " 个概念。保存后 AI 会整理概念，点概念能追回原笔记。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ChromeButton(label = "新建笔记", onClick = { createNote() }, filled = true)
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
            )
        }

        Spacer(Modifier.height(12.dp))

        if (notes.isEmpty() && concepts.isEmpty()) {
            KnowledgeEmptyState(onCreate = { createNote() })
        } else {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(264.dp).fillMaxHeight()) {
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
                            onClick = { showConcepts = false },
                        )
                        KnowledgePaneTab(
                            label = "概念",
                            selected = showConcepts,
                            onClick = { showConcepts = true },
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
                            onOpenNote = { selectNote(it) },
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
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
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
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        NoteTitleField(
                            title = title,
                            loaded = loaded != null,
                            onTitleChange = {
                                title = it
                                revision++
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        NoteConceptChips(
                            concepts = loaded?.concepts.orEmpty(),
                            onOpenConcept = { openConcept(it) },
                            onRemoveConcept = { removeConcept(it) },
                            onAddConcept = { addConcept(it) },
                        )
                        Spacer(Modifier.height(8.dp))
                        MarkdownEditor(
                            noteId = selectedId.orEmpty(),
                            body = body,
                            onBodyChange = {
                                body = it
                                revision++
                            },
                            preview = preview,
                            onTogglePreview = { preview = !preview },
                            statusText = status,
                            modifier = Modifier.weight(1f),
                            reveal = revealRange,
                            revealToken = revealToken,
                            conceptNames = concepts.map { it.name },
                            onDeleteAiLine = { line ->
                                val next = removeAiBlockLine(body, line)
                                if (next != null) {
                                    body = next
                                    revision++
                                    message = "已删掉一处 AI 内容"
                                }
                            },
                        )
                    }
                }
            }
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
