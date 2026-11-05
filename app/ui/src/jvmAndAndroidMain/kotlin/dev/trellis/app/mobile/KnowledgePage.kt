package dev.trellis.app.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.app.desktop.ConceptIdentity
import dev.trellis.app.desktop.ConceptMetaStore
import dev.trellis.app.desktop.KnowledgeRevealTarget
import dev.trellis.app.desktop.KnowledgeNote
import dev.trellis.app.desktop.KnowledgeStore
import dev.trellis.app.desktop.MarkdownText
import dev.trellis.app.desktop.NoteSummary
import dev.trellis.app.desktop.applyAiFills
import dev.trellis.app.desktop.buildKnowledgeExtractionMessages
import dev.trellis.app.desktop.displayTitle
import dev.trellis.app.desktop.extractionBodyChunks
import dev.trellis.app.desktop.markConceptAnnotations
import dev.trellis.app.desktop.mergeExtractionResults
import dev.trellis.app.desktop.parseExtractionResult
import dev.trellis.app.desktop.retainedCandidates
import dev.trellis.app.desktop.synchronizeConceptMembership
import dev.trellis.app.desktop.chooseMarkdownExportFile
import dev.trellis.app.desktop.markdownExportFileName
import dev.trellis.app.desktop.writeMarkdownExport
import kotlinx.coroutines.launch

/**
 * 知识：笔记 + 知识网络。
 *
 * 桌面上是「左栏笔记 / 中间编辑器 / 右栏概念」三栏；手机上只有一条路：
 * 列表 → 打开一篇 → 阅读（默认）或编辑。概念不单独占一页 —— 它们长在正文里（`[[…]]`），
 * 也长在「网络」那个分段里。
 *
 * AI 整理仍在这里：保存并整理 = 抽概念 + 补空位 + 在正文里标 `[[概念]]`。
 * 失败不影响保存，这是硬约束（模型不可用不是丢笔记的理由）。
 */
@Composable
fun KnowledgePage(runtime: AppRuntime, startTab: Int = 0, startGraphMode: Int = 0, startNoteId: String? = null) {
    var tab by remember { mutableStateOf(startTab.coerceIn(0, 1)) }
    var openNoteId by remember { mutableStateOf(startNoteId) }
    var reveal by remember { mutableStateOf<KnowledgeRevealTarget?>(null) }
    val store = remember(runtime) { runtime.knowledgeStore }

    // 对话里点「来自《…》」会写这个字段：跳到那篇笔记，而不是让用户自己找。
    LaunchedEffect(runtime.knowledgeNoteId) {
        val target = runtime.knowledgeNoteId
        if (target != null) {
            openNoteId = target
            tab = 0
            runtime.knowledgeNoteId = null
        }
    }

    // 计划页「AI 自动完成」点「看证据」会写这个字段：打开那篇笔记，并把当时那句证据
    // 顶到第一屏。手机上滚到正文中间反而丢上下文 —— 只有两三行的窗口，人不知道自己在哪。
    LaunchedEffect(runtime.pendingKnowledgeReveal) {
        val target = runtime.pendingKnowledgeReveal ?: return@LaunchedEffect
        runtime.pendingKnowledgeReveal = null
        tab = 0
        reveal = target
        openNoteId = target.noteId
    }

    Column(Modifier.fillMaxSize()) {
        if (openNoteId == null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Gap.l).padding(top = Gap.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("知识", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "笔记、概念与知识网络",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Gap.m))
            Box(Modifier.padding(horizontal = Gap.l)) {
                Segmented(
                    items = listOf("笔记", "网络"),
                    selected = tab,
                    onSelect = { tab = it },
                )
            }
            Spacer(Modifier.height(Gap.m))
        }

        if (openNoteId == null && tab == 0) {
            NoteListView(
                runtime = runtime,
                store = store,
                onOpen = {
                    // 从列表点进来是「自己找」，不是「别人指的」，把上一次的证据清掉。
                    reveal = null
                    openNoteId = it
                },
            )
        } else if (openNoteId == null) {
            KnowledgeGraphPage(runtime, startMode = startGraphMode, onOpenNote = { openNoteId = it })
        } else {
            NoteDetail(
                runtime = runtime,
                store = store,
                noteId = openNoteId!!,
                reveal = reveal,
                onDismissEvidence = { reveal = null },
                onBack = {
                    reveal = null
                    openNoteId = null
                },
            )
        }
    }
}

@Composable
private fun NoteListView(runtime: AppRuntime, store: KnowledgeStore, onOpen: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    // 列表本身很轻（只有标题与字数），每次重组读一遍目录不会成为瓶颈；
    // 真正的正文按需读（note detail）。
    val notes = remember(refresh, runtime.tick) { store.list() }
    val allNotes = remember(refresh, runtime.tick) { store.allNotes() }

    val filtered = remember(query, notes, allNotes) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            notes to emptyList<KnowledgeNote>()
        } else {
            val matched = allNotes.filter {
                it.title.contains(keyword, ignoreCase = true) || it.body.contains(keyword, ignoreCase = true)
            }
            notes.filter { note -> matched.any { it.id == note.id } } to matched
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Gap.l, end = Gap.l, bottom = Gap.xxl),
        verticalArrangement = Arrangement.spacedBy(Gap.m),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PhoneField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "搜标题或正文",
                    )
                }
                Spacer(Modifier.width(Gap.s))
                PhoneButton("新建", {
                    val created = store.create("")
                    refresh++
                    onOpen(created.id)
                })
            }
        }

        if (filtered.first.isEmpty()) {
            item {
                EmptyState(
                    if (query.isBlank()) "还没有笔记" else "没有匹配的笔记",
                    if (query.isBlank()) "「新建」写一篇；概念会在保存时自动整理" else "换个关键词试试",
                )
            }
        }

        filtered.first.forEach { summary ->
            item(key = summary.id) {
                NoteRow(summary, nowMs = runtime.clock.nowMs(), onOpen = { onOpen(summary.id) })
            }
        }
    }
}

@Composable
private fun NoteRow(summary: NoteSummary, nowMs: Long, onOpen: () -> Unit) {
    CardBox(onClick = onOpen) {
        Text(
            displayTitle(summary.title),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Gap.xs))
        Text(
            agoLabel(summary.updatedAt, nowMs) + " · " + summary.chars + " 字",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 一篇笔记：阅读 / 编辑两种模式。
 *
 * 为什么不直接把桌面编辑器搬过来：那套是「整篇一个输入面 + 光标处展开标记」，
 * 依赖键盘快捷键与精确光标，手指上不好用。手机上阅读是默认，编辑是一个明确的动作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteDetail(
    runtime: AppRuntime,
    store: KnowledgeStore,
    noteId: String,
    reveal: KnowledgeRevealTarget?,
    onDismissEvidence: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val metaStore = remember(runtime) { ConceptMetaStore(runtime.home) }

    var loaded by remember(noteId) { mutableStateOf(store.read(noteId)) }
    var title by remember(noteId) { mutableStateOf(loaded?.title.orEmpty()) }
    var body by remember(noteId) { mutableStateOf(loaded?.body.orEmpty()) }
    var editing by remember(noteId) { mutableStateOf(false) }
    var organizing by remember(noteId) { mutableStateOf(false) }
    var message by remember(noteId) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(noteId) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun save(): Boolean {
        val base = loaded ?: return false
        if (base.title == title && base.body == body) return true
        val identities = metaStore.identities()
        val next = synchronizeConceptMembership(
            base.copy(title = title, body = body, updatedAt = runtime.clock.nowMs()),
            identities,
        )
        val ok = store.save(next)
        if (ok) {
            loaded = next
            message = "已保存"
        } else {
            message = "保存失败：写不进 " + store.notesDir.absolutePath
        }
        return ok
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = Gap.l)) {
            SubPageBar(
                title = if (editing) "编辑" else "笔记",
                onBack = {
                    if (editing) save()
                    onBack()
                },
                action = {
                    Row {
                        if (!editing) {
                            PhoneButton("编辑", { editing = true }, filled = false)
                        } else {
                            PhoneButton("完成", { save(); editing = false })
                        }
                    }
                },
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Gap.l, end = Gap.l, bottom = Gap.xxl),
            verticalArrangement = Arrangement.spacedBy(Gap.m),
        ) {
            item {
                if (editing) {
                    PhoneField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = "标题",
                        singleLine = false,
                        minLines = 1,
                    )
                } else {
                    Text(
                        displayTitle(title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                val current = loaded
                if (current != null) {
                    Spacer(Modifier.height(Gap.xs))
                    Text(
                        agoLabel(current.updatedAt, runtime.clock.nowMs()) + " · " + body.length + " 字",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 证据：别人（计划页的自动完成）指过来的那一句，放在第一屏。
            // 偏移来自当时的正文；笔记被改过、越界了就只当「打开这篇」，不猜位置。
            val evidence = if (!editing && reveal != null && reveal.noteId == noteId) {
                val text = runCatching { body.substring(reveal.start, reveal.end) }.getOrNull()
                val line = if (text != null && reveal.start >= 0) {
                    body.take(reveal.start).count { it == '\n' } + 1
                } else {
                    null
                }
                text?.takeIf { it.isNotBlank() }?.let { it.trim() to line }
            } else {
                null
            }
            if (evidence != null) {
                item {
                    EvidenceCard(
                        quote = evidence.first,
                        line = evidence.second,
                        onDismiss = onDismissEvidence,
                    )
                }
            }

            if (editing) {
                item {
                    PhoneField(
                        value = body,
                        onValueChange = { body = it },
                        placeholder = "正文。整行写「标签：。。。」可以留一个空位，AI 整理时会补上。",
                        singleLine = false,
                        minLines = 12,
                    )
                }
            } else {
                item {
                    if (body.isBlank()) {
                        EmptyState("这一篇还是空的", "点右上「编辑」写点什么")
                    } else {
                        CardBox {
                            MarkdownText(
                                text = body,
                                conceptNames = loaded?.concepts.orEmpty(),
                                showLinkUrl = false,
                            )
                        }
                    }
                }
            }

            val concepts = loaded?.concepts.orEmpty()
            if (concepts.isNotEmpty()) {
                item {
                    SectionLabel("概念 " + concepts.size)
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Gap.xs)) {
                        concepts.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                                row.forEach { name ->
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                    ) {
                                        Text(
                                            name,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            message?.let { text ->
                item {
                    Text(
                        text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            item {
                CardBox {
                    SectionLabel("整理与导出")
                    Spacer(Modifier.height(Gap.m))
                    Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                        PhoneButton(
                            if (organizing) "整理中…" else "保存并整理",
                            enabled = !organizing,
                            onClick = {
                                if (!save()) return@PhoneButton
                                val note = store.read(noteId) ?: return@PhoneButton
                                val client = runtime.llmClientOrNull()
                                if (client == null) {
                                    message = "AI 没配置：先在「我的 → 模型」导入，才能抽概念与补空位"
                                    return@PhoneButton
                                }
                                organizing = true
                                scope.launch {
                                    message = organizeNote(runtime, store, metaStore, note, client)
                                    loaded = store.read(noteId)
                                    title = loaded?.title.orEmpty()
                                    body = loaded?.body.orEmpty()
                                    organizing = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        PhoneButton("导出 Markdown", {
                            val note = store.read(noteId)
                            if (note != null) {
                                val file = runCatching {
                                    chooseMarkdownExportFile(markdownExportFileName(note.title))
                                }.getOrNull()
                                message = if (file == null) {
                                    "导出取消"
                                } else {
                                    runCatching {
                                        writeMarkdownExport(note.copy(title = title, body = body), file)
                                        "已导出：" + file.absolutePath
                                    }.getOrElse { "导出失败：" + (it.message ?: "写不进文件") }
                                }
                            }
                        }, modifier = Modifier.weight(1f), filled = false)
                    }
                    Spacer(Modifier.height(Gap.s))
                    Text(
                        "整理只建议不改原文：概念会以 [[标注]] 落在正文里，空位补成 {{内容}}。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                PhoneButton(
                    "删除这篇",
                    { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                    filled = false,
                    danger = true,
                )
            }
        }
    }

    if (confirmDelete) {
        ModalBottomSheet(
            onDismissRequest = { confirmDelete = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
                SheetHeading("删除这篇笔记", "文件会立刻从磁盘删掉，不能撤销")
                Spacer(Modifier.height(Gap.l))
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                    PhoneButton("删除", {
                        store.delete(noteId)
                        confirmDelete = false
                        onBack()
                    }, modifier = Modifier.weight(1f), danger = true)
                    PhoneButton("再想想", { confirmDelete = false }, modifier = Modifier.weight(1f), filled = false)
                }
            }
        }
    }
}

/**
 * 「保存并整理」：抽概念 + 补空位 + 在正文里标 `[[概念]]`。
 *
 * 分段送模型（长笔记按标题切），失败不写盘。返回一句给人看的结果。
 */
private suspend fun organizeNote(
    runtime: AppRuntime,
    store: KnowledgeStore,
    metaStore: ConceptMetaStore,
    note: KnowledgeNote,
    client: dev.trellis.ai.LlmClient,
): String {
    if (note.body.isBlank()) return "正文是空的，没什么可整理"
    val chunks = extractionBodyChunks(note.body)
    val raws = ArrayList<String>()
    for ((index, chunk) in chunks.withIndex()) {
        val raw = runCatching {
            client.complete(
                buildKnowledgeExtractionMessages(
                    title = note.title,
                    body = chunk,
                    existing = note.concepts,
                    slots = emptyList(),
                    chunkIndex = index,
                    chunkCount = chunks.size,
                ),
            )
        }.getOrNull() ?: continue
        raws += raw
    }
    if (raws.isEmpty()) return "AI 整理失败，不影响笔记保存；可以稍后重试"
    val merged = mergeExtractionResults(raws.map(::parseExtractionResult))

    val filled = applyAiFills(note.body, merged.fills.associate { it.label to it.text })
    val identitiesForRetain = (
        metaStore.identities() + note.concepts.map { ConceptIdentity(it) } +
            merged.candidates.map { ConceptIdentity(it.name, it.aliases) }
        ).distinctBy { it.name }
    val kept = retainedCandidates(filled.body, merged.candidates, identitiesForRetain)
    val marked = markConceptAnnotations(filled.body, kept)

    // 身份表：已有概念 + 这次新抽到的（含别名），这样正文里的别名能落到同一个规范名上。
    val identities = (
        metaStore.identities() + note.concepts.map { ConceptIdentity(it) } +
            kept.map { ConceptIdentity(it.name, it.aliases) }
        ).distinctBy { it.name }

    val updated = synchronizeConceptMembership(
        note.copy(body = marked, updatedAt = runtime.clock.nowMs()),
        identities,
    )
    if (!store.save(updated)) return "保存失败：写不进 " + store.notesDir.absolutePath
    // 概念定义写进概念库：网络视图的节点说明来自这里。
    val definitions = kept.associate { it.name to it.definition }.filterValues { it.isNotBlank() }
    if (definitions.isNotEmpty()) metaStore.fillMissing(definitions)
    return "整理完成：概念 " + updated.concepts.size + " 个，补全 " + merged.fills.size + " 处空位"
}

/**
 * 「AI 是凭这句勾掉的」卡片。
 *
 * 不做「滚到正文中间并高亮」：390dp 宽下滚过去只剩两三行字，人反而不知道自己在哪。
 * 把原句顶到第一屏，再标上原文行号，既能核对，也能自己翻回去看上下文。
 */
@Composable
private fun EvidenceCard(quote: String, line: Int?, onDismiss: () -> Unit) {
    CardBox(padding = Gap.m) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "AI 依据的这句",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            if (line != null) {
                Text(
                    "第 " + line + " 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Gap.s))
        Text(
            "「" + quote + "」",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .padding(horizontal = Gap.s, vertical = Gap.s),
        )
        Spacer(Modifier.height(Gap.s))
        Text(
            "知道了",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = Gap.xs, vertical = Gap.xs),
        )
    }
}
